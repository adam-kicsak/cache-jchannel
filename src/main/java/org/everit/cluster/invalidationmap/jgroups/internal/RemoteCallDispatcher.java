/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.cluster.invalidationmap.jgroups.internal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.MethodLookup;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;

/**
 * Remote call dispatcher. Provides the methods are able to call remotely. It handles the mandatory
 * call parameter, the start time stamp and the message counter. Also handles the the incoming
 * remote calls.
 */
public final class RemoteCallDispatcher implements RemoteCall {

  /**
   * Logger.
   */
  private static final Logger LOGGER = Logger.getLogger(RemoteCallDispatcher.class.getName());

  /**
   * Asynchronous request options.
   */
  private static final RequestOptions ASYNC_REQUEST_OPTIONS = new RequestOptions(
      ResponseMode.GET_NONE, 0);

  /**
   * Method lookup in the server.
   */
  private final MethodLookup methods = new Lookup(this.getClass());

  /**
   * Cluster for the {@link RemoteCallDispatcher} instance was created.
   */
  private final JGroupsInvalidationMapCluster cluster;

  /**
   * The remote method call dispatcher on the top of the {@link #channel}.
   */
  private final RpcDispatcher dispatcher;

  /**
   * Time stamp of the clustered operation start.
   */
  private final long startTimeNanos;

  /**
   * Message counter.
   */
  private final AtomicLong messageCounter = new AtomicLong(0);

  /**
   * Creates the dispatcher.
   *
   * @param cluster
   *          The cluster for the dispatcher will be created.
   */
  RemoteCallDispatcher(final JGroupsInvalidationMapCluster cluster) {
    this.cluster = cluster;
    dispatcher = new RpcDispatcher(cluster.channel, this);
    dispatcher.setMethodLookup(this.methods);
    startTimeNanos = System.nanoTime();
  }

  /**
   * Sends bye message.
   */
  public void bye() {
    long c = messageCounter.incrementAndGet();
    callRemoteMethod(RemoteCall.METHOD_ID_BYE, c);
    LOGGER.info("Bye was sent");
  }

  @Override
  public void bye(final String nodeName, final long startTimeNanos, final long gotMessageNumber) {
    cluster.notifyRemoteCall(nodeName, startTimeNanos, gotMessageNumber);
    cluster.nodeLeft(nodeName);
  }

  /**
   * Calls a method remotely over the channel. Does nothing if {@link #isDroppedOut()} flag is set.
   *
   * @param id
   *          The ID of the method.
   * @param messageNumber
   *          The message number.
   * @param args
   *          The arguments.
   */
  protected void callRemoteMethod(final short id, final long messageNumber,
      final Object... args) {
    if (!cluster.channel.isConnected()) {
      return;
    }
    MethodCall call = createMethodCall(id, messageNumber, args);
    try {
      dispatcher.callRemoteMethods(null, call, ASYNC_REQUEST_OPTIONS);
      LOGGER.info("Method called: " + methods.findMethod(id).getName() + " "
          + Arrays.toString(call.getArgs()));
    } catch (Exception e) {
      throw new RuntimeException(
          "Cannot call " + methods.findMethod(id) + " with parameters "
              + Arrays.toString(call.getArgs()),
          e);
    }
  }

  /**
   * Creates the method call. It extends the given parameters with the mandatory parameter values.
   *
   * @param id
   *          The ID of the method.
   * @param messageNumber
   *          The message number.
   * @param args
   *          The arguments.
   * @return The method call.
   */
  protected MethodCall createMethodCall(final short id, final long messageNumber,
      final Object... args) {
    Object[] callArgs = new Object[args.length + RemoteCall.MANDATORY_PARAMETER_COUNT];
    callArgs[0] = cluster.selfName;
    callArgs[1] = Long.valueOf(startTimeNanos);
    callArgs[2] = Long.valueOf(messageNumber);
    if (args.length > 0) {
      System.arraycopy(args, 0, callArgs, RemoteCall.MANDATORY_PARAMETER_COUNT, args.length);
    }
    MethodCall call = new MethodCall(id, callArgs);
    return call;
  }

  /**
   * Sends invalidate a key message.
   *
   * @param key
   *          Key to invalidate.
   */
  public void invalidate(final Object key) {
    long c = messageCounter.incrementAndGet();
    callRemoteMethod(RemoteCall.METHOD_ID_INVALIDATE, c, key);
    LOGGER.info("Invalidate the key in the cache of remote nodes " + key);
  }

  @Override
  public void invalidate(final String nodeName, final long startTimeNanos,
      final long gotMessageNumber, final Object key) {
    cluster.notifyRemoteCall(nodeName, startTimeNanos, gotMessageNumber);
    cluster.invalidationCallback.invalidate(key);
  }

  /**
   * Sends invalidate all of the keys message.
   */
  public void invalidateAll() {
    long c = messageCounter.incrementAndGet();
    callRemoteMethod(RemoteCall.METHOD_ID_INVALIDATE_ALL, c);
    LOGGER.info("Invalidate the cache of remote nodes");
  }

  @Override
  public void invalidateAll(final String nodeName, final long startTimeNanos,
      final long gotMessageNumber) {
    cluster.notifyRemoteCall(nodeName, startTimeNanos, gotMessageNumber);
    cluster.invalidationCallback.invalidateAll();
  }

  /**
   * Sends ping.
   */
  public void ping() {
    long c = messageCounter.get();
    callRemoteMethod(RemoteCall.METHOD_ID_PING, c);
    LOGGER.info("Ping was sent");
  }

  @Override
  public void ping(final String nodeName, final long startTimeNanos, final long gotMessageNumber) {
    cluster.notifyPing(nodeName, startTimeNanos, gotMessageNumber);
  }

  /**
   * Stops the remote dispatcher.
   *
   * @param sendBye
   *          Set to <code>true</code> if a by message is needed to send before stop.
   */
  public void stop(final boolean sendBye) {
    if (sendBye) {
      bye();
    }
    dispatcher.stop();
  }

}