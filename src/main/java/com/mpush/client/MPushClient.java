/*
 * (C) Copyright 2015-2016 the original author or authors.
 *
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
 *
 * Contributors:
 *     ohun@live.cn (夜色)
 */

package com.mpush.client;


import com.mpush.api.Client;
import com.mpush.api.Logger;
import com.mpush.api.connection.SessionContext;
import com.mpush.api.connection.SessionStorage;
import com.mpush.api.http.HttpRequest;
import com.mpush.api.http.HttpResponse;
import com.mpush.api.protocol.Command;
import com.mpush.api.protocol.Packet;
import com.mpush.handler.HttpProxyHandler;
import com.mpush.message.BindUserMessage;
import com.mpush.message.FastConnectMessage;
import com.mpush.message.HandshakeMessage;
import com.mpush.message.HttpRequestMessage;
import com.mpush.security.AesCipher;
import com.mpush.security.CipherBox;
import com.mpush.session.PersistentSession;
import com.mpush.util.Strings;
import com.mpush.util.thread.ExecutorManager;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.mpush.api.Constants.*;

/**
 * Created by ohun on 2016/1/17.
 *
 * @author ohun@live.cn (夜色)
 */
public final class MPushClient implements Client {
    public enum State {Started, Shutdown, Destroyed}

    private final AtomicReference<State> clientState = new AtomicReference(State.Shutdown);

    private final MessageDispatcher receiver;
    private final TcpConnection connection;
    private final ClientConfig config;
    private final Logger logger;
    private int hbTimeoutTimes;

    private HttpRequestQueue requestQueue;

    /*package*/ MPushClient(ClientConfig config) {
        this.config = config;
        this.logger = config.getLogger();
        this.receiver = new MessageDispatcher();
        this.connection = new TcpConnection(this, receiver);
        if (config.isEnableHttpProxy()) {
            this.requestQueue = new HttpRequestQueue();
            this.receiver.register(Command.HTTP_PROXY, new HttpProxyHandler(requestQueue));
        }
    }

    @Override
    public void start() {
        if (clientState.compareAndSet(State.Shutdown, State.Started)) {
            connection.setAutoConnect(true);
            connection.connect();
            logger.w("do start client ...");
        }
    }

    @Override
    public void stop() {
        logger.w("client shutdown !!!, state=%s", clientState.get());
        if (clientState.compareAndSet(State.Started, State.Shutdown)) {
            connection.setAutoConnect(false);
            connection.close();
        }
    }

    @Override
    public void destroy() {
        if (clientState.getAndSet(State.Destroyed) != State.Destroyed) {
            this.stop();
            logger.w("client destroy !!!");
            ExecutorManager.INSTANCE.shutdown();
            ClientConfig.I.destroy();
        }
    }

    @Override
    public boolean isRunning() {
        return clientState.get() == State.Started && connection.isConnected();
    }

    @Override
    public boolean healthCheck() {

        if (connection.isReadTimeout()) {
            hbTimeoutTimes++;
            logger.w("heartbeat timeout times=%s", hbTimeoutTimes);
        } else {
            hbTimeoutTimes = 0;
        }

        if (hbTimeoutTimes >= MAX_HB_TIMEOUT_COUNT) {
            logger.w("heartbeat timeout times=%d over limit=%d, client restart", hbTimeoutTimes, MAX_HB_TIMEOUT_COUNT);
            hbTimeoutTimes = 0;
            connection.reconnect();
            return false;
        }

        if (connection.isWriteTimeout()) {
            logger.d(">>> send heartbeat ping...");
            connection.send(Packet.HB_PACKET);
        }

        return true;
    }

    @Override
    public void fastConnect() {
        SessionStorage storage = config.getSessionStorage();
        if (storage == null) {
            handshake();
            return;
        }

        String ss = storage.getSession();
        if (Strings.isBlank(ss)) {
            handshake();
            return;
        }

        PersistentSession session = PersistentSession.decode(ss);
        if (session == null || session.isExpired()) {
            storage.clearSession();
            logger.w("fast connect failure session expired, session=%s", session);
            handshake();
            return;
        }

        FastConnectMessage message = new FastConnectMessage(connection);
        message.deviceId = config.getDeviceId();
        message.sessionId = session.sessionId;
        message.maxHeartbeat = config.getMaxHeartbeat();
        message.minHeartbeat = config.getMinHeartbeat();
        message.sendRaw();
        connection.getSessionContext().changeCipher(session.cipher);
        logger.w(">>> do fast connect, message=%s", message);
    }

    @Override
    public void handshake() {
        SessionContext context = connection.getSessionContext();
        context.changeCipher(CipherBox.INSTANCE.getRsaCipher());
        HandshakeMessage message = new HandshakeMessage(connection);
        message.clientKey = CipherBox.INSTANCE.randomAESKey();
        message.iv = CipherBox.INSTANCE.randomAESIV();
        message.deviceId = config.getDeviceId();
        message.osName = config.getOsName();
        message.osVersion = config.getOsVersion();
        message.clientVersion = config.getClientVersion();
        message.maxHeartbeat = config.getMaxHeartbeat();
        message.minHeartbeat = config.getMinHeartbeat();
        message.send();
        context.changeCipher(new AesCipher(message.clientKey, message.iv));
        logger.w(">>> do handshake, message=%s", message);
    }

    @Override
    public void bindUser(String userId) {
        if (Strings.isBlank(userId)) {
            logger.w("bind user is null");
            return;
        }
        SessionContext context = connection.getSessionContext();
        if (userId.equals(context.bindUser)) return;
        context.setBindUser(userId);
        config.setUserId(userId);
        BindUserMessage
                .buildBind(connection)
                .setUserId(userId)
                .send();
        logger.w(">>> do bind user, userId=%s", userId);
    }

    @Override
    public void unbindUser() {
        String userId = config.getUserId();
        if (Strings.isBlank(userId)) {
            logger.w("unbind user is null");
            return;
        }
        config.setUserId(null);
        connection.getSessionContext().setBindUser(null);
        BindUserMessage
                .buildUnbind(connection)
                .setUserId(userId)
                .send();
        logger.w(">>> do unbind user, userId=%s", userId);
    }

    @Override
    public Future<HttpResponse> sendHttp(HttpRequest request) {
        if (connection.getSessionContext().handshakeOk()) {
            HttpRequestMessage message = new HttpRequestMessage(connection);
            message.method = request.method;
            message.uri = request.uri;
            message.headers = request.headers;
            message.body = request.body;
            message.send();
            logger.d(">>> send http proxy, request=%s", request);
            return requestQueue.add(message.getSessionId(), request);
        }
        return null;
    }
}
