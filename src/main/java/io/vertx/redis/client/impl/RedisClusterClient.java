/*
 * Copyright 2019 Red Hat, Inc.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * <p>
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * <p>
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.redis.client.impl;

import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisClusterConnectOptions;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisReplicas;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.types.MultiType;
import io.vertx.redis.client.impl.types.NumberType;
import io.vertx.redis.client.impl.types.SimpleStringType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public class RedisClusterClient extends BaseRedisClient<RedisClusterConnectOptions> implements Redis {

  private static final Logger LOG = LoggerFactory.getLogger(RedisClusterClient.class);

  public static void addReducer(Command command, Function<List<Response>, Response> fn) {
    RedisClusterConnection.addReducer(command, fn);
  }

  public static void addMasterOnlyCommand(Command command) {
    RedisClusterConnection.addMasterOnlyCommand(command);
  }

  static {
    // provided reducers

    addReducer(Command.MSET, list ->
      // Simple string reply: always OK since MSET can't fail.
      SimpleStringType.OK);

    addReducer(Command.DEL, list ->
      NumberType.create(list.stream()
        .mapToLong(el -> {
          Long l = el.toLong();
          if (l == null) {
            return 0L;
          } else {
            return l;
          }
        }).sum()));

    addReducer(Command.MGET, list -> {
      int total = 0;
      for (Response resp : list) {
        total += resp.size();
      }

      MultiType multi = MultiType.create(total, false);
      for (Response resp : list) {
        for (Response child : resp) {
          multi.add(child);
        }
      }

      return multi;
    });

    addReducer(Command.KEYS, list -> {
      int total = 0;
      for (Response resp : list) {
        total += resp.size();
      }

      MultiType multi = MultiType.create(total, false);
      for (Response resp : list) {
        for (Response child : resp) {
          multi.add(child);
        }
      }

      return multi;
    });

    addReducer(Command.FLUSHDB, list ->
      // Simple string reply: always OK since FLUSHDB can't fail.
      SimpleStringType.OK);

    addReducer(Command.DBSIZE, list ->
      // Sum of key numbers on all Key Slots
      NumberType.create(list.stream()
        .mapToLong(el -> {
          Long l = el.toLong();
          if (l == null) {
            return 0L;
          } else {
            return l;
          }
        }).sum()));

    addMasterOnlyCommand(Command.WAIT);

    addMasterOnlyCommand(Command.SUBSCRIBE);
    addMasterOnlyCommand(Command.PSUBSCRIBE);
    addMasterOnlyCommand(Command.SSUBSCRIBE);
    addReducer(Command.UNSUBSCRIBE, list -> SimpleStringType.OK);
    addReducer(Command.PUNSUBSCRIBE, list -> SimpleStringType.OK);
    addReducer(Command.SUNSUBSCRIBE, list -> SimpleStringType.OK);
  }

  private final SharedSlots sharedSlots;

  public RedisClusterClient(Vertx vertx, NetClientOptions tcpOptions, PoolOptions poolOptions, Supplier<Future<RedisClusterConnectOptions>> connectOptions, TracingPolicy tracingPolicy) {
    super(vertx, tcpOptions, poolOptions, connectOptions, tracingPolicy);
    this.sharedSlots = new SharedSlots(vertx, connectOptions, connectionManager);
    // validate options
    if (poolOptions.getMaxWaiting() < poolOptions.getMaxSize()) {
      throw new IllegalStateException("Invalid options: maxWaiting < maxSize");
    }
  }

  @Override
  public Future<RedisConnection> connect() {
    final Promise<RedisConnection> promise = vertx.promise();
    sharedSlots.get()
      .onSuccess(slots -> connect(slots, promise))
      .onFailure(promise::fail);
    return promise.future();
  }

  private void connect(Slots slots, Completable<RedisConnection> promise) {
    connectOptions.get()
      .onSuccess(opts -> connect(slots, opts, promise))
      .onFailure(promise::fail);
  }

  private void connect(Slots slots, RedisClusterConnectOptions connectOptions, Completable<RedisConnection> onConnected) {
    // create a cluster connection
    final Set<Throwable> failures = ConcurrentHashMap.newKeySet();
    final AtomicInteger counter = new AtomicInteger();
    final Map<String, PooledRedisConnection> connections = new HashMap<>();

    for (String endpoint : slots.endpoints()) {
      connectionManager.getConnection(endpoint, RedisReplicas.NEVER != connectOptions.getUseReplicas() ? Request.cmd(Command.READONLY) : null)
        .onFailure(err -> {
          // failed try with the next endpoint
          failures.add(err);
          connectionComplete(counter, slots, connectOptions, connections, failures, onConnected);
        })
        .onSuccess(cconn -> {
          // there can be concurrent access to the connection map
          // since this is a one time operation we can pay the penalty of
          // synchronizing on each write (hopefully is only a few writes)
          synchronized (connections) {
            connections.put(endpoint, cconn);
          }
          connectionComplete(counter, slots, connectOptions, connections, failures, onConnected);
        });
    }
  }

  private void connectionComplete(AtomicInteger counter, Slots slots, RedisClusterConnectOptions connectOptions,
      Map<String, PooledRedisConnection> connections, Set<Throwable> failures, Completable<RedisConnection> onConnected) {
    if (counter.incrementAndGet() == slots.endpoints().length) {
      // end condition
      if (!failures.isEmpty()) {
        // cleanup

        // during an error we lock the map because we will change it
        // probably this isn't an issue as no more write should happen anyway
        synchronized (connections) {
          for (RedisConnection value : connections.values()) {
            if (value != null) {
              value.close().onFailure(LOG::warn);
            }
          }
        }
        // return
        StringBuilder message = new StringBuilder("Failed to connect to all nodes of the cluster");
        for (Throwable failure : failures) {
          message.append("\n- ").append(failure);
        }
        onConnected.fail(new RedisConnectException(message.toString()));
      } else {
        onConnected.succeed(new RedisClusterConnection(vertx, connectionManager,
          connectOptions, sharedSlots, connections));
      }
    }
  }

}
