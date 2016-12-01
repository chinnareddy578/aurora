/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.common.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.apache.aurora.common.base.Command;
import org.apache.aurora.common.net.InetSocketAddressHelper;
import org.apache.aurora.common.quantity.Amount;
import org.apache.aurora.common.quantity.Time;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a connection to a ZooKeeper cluster.
 */
public class ZooKeeperClient {

  /**
   * Indicates an error connecting to a zookeeper cluster.
   */
  public class ZooKeeperConnectionException extends Exception {
    ZooKeeperConnectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final class SessionState {
    private final long sessionId;
    private final byte[] sessionPasswd;

    private SessionState(long sessionId, byte[] sessionPasswd) {
      this.sessionId = sessionId;
      this.sessionPasswd = sessionPasswd;
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperClient.class);

  private static final Amount<Long,Time> WAIT_FOREVER = Amount.of(0L, Time.MILLISECONDS);

  private final int sessionTimeoutMs;
  private final Optional<Credentials> credentials;
  private final String zooKeeperServers;
  // GuardedBy "this", but still volatile for tests, where we want to be able to see writes
  // made from within long synchronized blocks.
  private volatile ZooKeeper zooKeeper;
  private SessionState sessionState;

  private final Set<Watcher> watchers = new CopyOnWriteArraySet<Watcher>();
  private final BlockingQueue<WatchedEvent> eventQueue = new LinkedBlockingQueue<WatchedEvent>();

  private static Iterable<InetSocketAddress> combine(InetSocketAddress address,
      InetSocketAddress... addresses) {
    return ImmutableSet.<InetSocketAddress>builder().add(address).add(addresses).build();
  }

  /**
   * Creates an unconnected client that will lazily attempt to connect on the first call to
   * {@link #get()}.
   *
   * @param sessionTimeout the ZK session timeout
   * @param zooKeeperServer the first, required ZK server
   * @param zooKeeperServers any additional servers forming the ZK cluster
   */
  public ZooKeeperClient(Amount<Integer, Time> sessionTimeout, InetSocketAddress zooKeeperServer,
      InetSocketAddress... zooKeeperServers) {
    this(sessionTimeout, combine(zooKeeperServer, zooKeeperServers));
  }

  /**
   * Creates an unconnected client that will lazily attempt to connect on the first call to
   * {@link #get}.
   *
   * @param sessionTimeout the ZK session timeout
   * @param zooKeeperServers the set of servers forming the ZK cluster
   */
  public ZooKeeperClient(Amount<Integer, Time> sessionTimeout,
      Iterable<InetSocketAddress> zooKeeperServers) {
    this(sessionTimeout, Optional.absent(), Optional.absent(), zooKeeperServers);
  }

  /**
   * Creates an unconnected client that will lazily attempt to connect on the first call to
   * {@link #get()}.  All successful connections will be authenticated with the given
   * {@code credentials}.
   *
   * @param sessionTimeout the ZK session timeout
   * @param credentials the credentials to authenticate with
   * @param zooKeeperServer the first, required ZK server
   * @param zooKeeperServers any additional servers forming the ZK cluster
   */
  public ZooKeeperClient(Amount<Integer, Time> sessionTimeout, Credentials credentials,
      InetSocketAddress zooKeeperServer, InetSocketAddress... zooKeeperServers) {
    this(sessionTimeout,
        Optional.of(credentials),
        Optional.absent(),
        combine(zooKeeperServer, zooKeeperServers));
  }

  /**
   * Creates an unconnected client that will lazily attempt to connect on the first call to
   * {@link #get}.  All successful connections will be authenticated with the given
   * {@code credentials}.
   *
   * @param sessionTimeout the ZK session timeout
   * @param credentials the credentials to authenticate with
   * @param zooKeeperServers the set of servers forming the ZK cluster
   */
  public ZooKeeperClient(Amount<Integer, Time> sessionTimeout, Credentials credentials,
      Iterable<InetSocketAddress> zooKeeperServers) {
        this(sessionTimeout,
            Optional.of(credentials),
            Optional.absent(),
            zooKeeperServers);
      }

  /**
   * Creates an unconnected client that will lazily attempt to connect on the first call to
   * {@link #get}.  All successful connections will be authenticated with the given
   * {@code credentials}.
   *
   * @param sessionTimeout the ZK session timeout
   * @param credentials the credentials to authenticate with
   * @param chrootPath an optional chroot path
   * @param zooKeeperServers the set of servers forming the ZK cluster
   */
  public ZooKeeperClient(Amount<Integer, Time> sessionTimeout, Optional<Credentials> credentials,
      Optional<String> chrootPath, Iterable<InetSocketAddress> zooKeeperServers) {
    this.sessionTimeoutMs = Preconditions.checkNotNull(sessionTimeout).as(Time.MILLISECONDS);
    this.credentials = Preconditions.checkNotNull(credentials);

    if (chrootPath.isPresent()) {
      PathUtils.validatePath(chrootPath.get());
    }

    Preconditions.checkNotNull(zooKeeperServers);
    Preconditions.checkArgument(!Iterables.isEmpty(zooKeeperServers),
        "Must present at least 1 ZK server");

    Thread watcherProcessor = new Thread("ZookeeperClient-watcherProcessor") {
      @Override
      public void run() {
        while (true) {
          try {
            WatchedEvent event = eventQueue.take();
            for (Watcher watcher : watchers) {
              watcher.process(event);
            }
          } catch (InterruptedException e) { /* ignore */ }
        }
      }
    };
    watcherProcessor.setDaemon(true);
    watcherProcessor.start();

    Iterable<String> servers =
        Iterables.transform(ImmutableSet.copyOf(zooKeeperServers),
            InetSocketAddressHelper::toString);
    this.zooKeeperServers = Joiner.on(',').join(servers).concat(chrootPath.or(""));
  }

  /**
   * Returns the current active ZK connection or establishes a new one if none has yet been
   * established or a previous connection was disconnected or had its session time out.  This method
   * will attempt to re-use sessions when possible.  Equivalent to:
   * <pre>get(Amount.of(0L, ...)</pre>.
   *
   * @return a connected ZooKeeper client
   * @throws ZooKeeperConnectionException if there was a problem connecting to the ZK cluster
   * @throws InterruptedException if interrupted while waiting for a connection to be established
   */
  public synchronized ZooKeeper get() throws ZooKeeperConnectionException, InterruptedException {
    try {
      return get(WAIT_FOREVER);
    } catch (TimeoutException e) {
      InterruptedException interruptedException =
          new InterruptedException("Got an unexpected TimeoutException for 0 wait");
      interruptedException.initCause(e);
      throw interruptedException;
    }
  }

  /**
   * Returns the current active ZK connection or establishes a new one if none has yet been
   * established or a previous connection was disconnected or had its session time out.  This
   * method will attempt to re-use sessions when possible.
   *
   * @param connectionTimeout the maximum amount of time to wait for the connection to the ZK
   *     cluster to be established; 0 to wait forever
   * @return a connected ZooKeeper client
   * @throws ZooKeeperConnectionException if there was a problem connecting to the ZK cluster
   * @throws InterruptedException if interrupted while waiting for a connection to be established
   * @throws TimeoutException if a connection could not be established within the configured
   *     session timeout
   */
  public synchronized ZooKeeper get(Amount<Long, Time> connectionTimeout)
      throws ZooKeeperConnectionException, InterruptedException, TimeoutException {

    if (zooKeeper == null) {
      final CountDownLatch connected = new CountDownLatch(1);
      Watcher watcher = event -> {
        switch (event.getType()) {
          // Guard the None type since this watch may be used as the default watch on calls by
          // the client outside our control.
          case None:
            switch (event.getState()) {
              case Expired:
                LOG.info("Zookeeper session expired. Event: " + event);
                close();
                break;
              case SyncConnected:
                connected.countDown();
                break;
            }
        }

        eventQueue.offer(event);
      };

      try {
        zooKeeper = (sessionState != null)
          ? new ZooKeeper(zooKeeperServers, sessionTimeoutMs, watcher, sessionState.sessionId,
            sessionState.sessionPasswd)
          : new ZooKeeper(zooKeeperServers, sessionTimeoutMs, watcher);
      } catch (IOException e) {
        throw new ZooKeeperConnectionException(
            "Problem connecting to servers: " + zooKeeperServers, e);
      }

      if (connectionTimeout.getValue() > 0) {
        if (!connected.await(connectionTimeout.as(Time.MILLISECONDS), TimeUnit.MILLISECONDS)) {
          close();
          throw new TimeoutException("Timed out waiting for a ZK connection after "
                                     + connectionTimeout);
        }
      } else {
        try {
          connected.await();
        } catch (InterruptedException ex) {
          LOG.info("Interrupted while waiting to connect to zooKeeper");
          close();
          throw ex;
        }
      }
      if (credentials.isPresent()) {
        Credentials zkCredentials = credentials.get();
        zooKeeper.addAuthInfo(zkCredentials.scheme(), zkCredentials.authToken());
      }

      sessionState = new SessionState(zooKeeper.getSessionId(), zooKeeper.getSessionPasswd());
    }
    return zooKeeper;
  }

  /**
   * Clients that need to re-establish state after session expiration can register an
   * {@code onExpired} command to execute.
   *
   * @param onExpired the {@code Command} to register
   * @return the new {@link Watcher} which can later be passed to {@link #unregister} for
   *     removal.
   */
  public Watcher registerExpirationHandler(final Command onExpired) {
    Watcher watcher = event -> {
      if (event.getType() == EventType.None && event.getState() == KeeperState.Expired) {
        onExpired.execute();
      }
    };
    register(watcher);
    return watcher;
  }

  /**
   * Clients that need to register a top-level {@code Watcher} should do so using this method.  The
   * registered {@code watcher} will remain registered across re-connects and session expiration
   * events.
   *
   * @param watcher the {@code Watcher to register}
   */
  public void register(Watcher watcher) {
    watchers.add(watcher);
  }

  /**
   * Clients can attempt to unregister a top-level {@code Watcher} that has previously been
   * registered.
   *
   * @param watcher the {@code Watcher} to unregister as a top-level, persistent watch
   * @return whether the given {@code Watcher} was found and removed from the active set
   */
  public boolean unregister(Watcher watcher) {
    return watchers.remove(watcher);
  }

  /**
   * Checks to see if the client might reasonably re-try an operation given the exception thrown
   * while attempting it.  If the ZooKeeper session should be expired to enable the re-try to
   * succeed this method will expire it as a side-effect.
   *
   * @param e the exception to test
   * @return true if a retry can be attempted
   */
  public boolean shouldRetry(KeeperException e) {
    if (e instanceof SessionExpiredException) {
      close();
    }
    return ZooKeeperUtils.isRetryable(e);
  }

  /**
   * Closes the current connection if any expiring the current ZooKeeper session.  Any subsequent
   * calls to this method will no-op until the next successful {@link #get}.
   */
  public synchronized void close() {
    if (zooKeeper != null) {
      try {
        zooKeeper.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Interrupted trying to close zooKeeper");
      } finally {
        zooKeeper = null;
        sessionState = null;
      }
    }
  }

  @VisibleForTesting
  synchronized boolean isClosed() {
    return zooKeeper == null;
  }

  @VisibleForTesting
  ZooKeeper getZooKeeperClientForTests() {
    return zooKeeper;
  }
}
