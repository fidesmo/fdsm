/*
 * Copyright (c) 2018 - present Fidesmo AB
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.fidesmo.fdsm;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WsClient {
    private final static Logger logger = LoggerFactory.getLogger(WsClient.class);

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int pingSecondsPeriod = 30;

    private final URI uri;
    private final Map<String, String> headers;
    private final BIBO card;
    private WebSocket client;
    private final CompletableFuture<ServiceDeliverySession.DeliveryResult> deliveryResult = new CompletableFuture<>();
    private String sessionId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public WsClient(URI uri, BIBO card, ClientAuthentication authentication, ClientInfo info) {
        this.uri = uri;        
        this.headers = new HashMap<String, String>();
        
        if (authentication != null) {
            this.headers.put(HttpHeaders.AUTHORIZATION, authentication.toAuthenticationHeader());
        }

        info.asHeaders().stream().forEach(h -> this.headers.put(h.getName(), h.getValue()));

        this.card = card;
        this.client = null;
    }

    public static CompletableFuture<ServiceDeliverySession.DeliveryResult> execute(URI uri, BIBO card, ClientAuthentication authentication, ClientInfo info) {
        return new WsClient(uri, card, authentication, info).run();
    }

    public CompletableFuture<ServiceDeliverySession.DeliveryResult> run() {
        if (deliveryResult.isDone()) {
            throw new IllegalStateException("WsClient is single-use!");
        }

        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        WebSocket.Builder webSocketBuilder = httpClientBuilder.build().newWebSocketBuilder();
        headers.forEach(webSocketBuilder::header);

        webSocketBuilder.buildAsync(uri, buildWsListener()).whenComplete((webSocket, error) -> {
            if (error != null) {
                deliveryResult.completeExceptionally(error);
                return;
            }

            client = webSocket;

            scheduler.scheduleAtFixedRate(() -> {
                if (client != null) {
                    client.sendPing(ByteBuffer.wrap(new byte[] {1})).exceptionally(ex -> {
                        logger.warn("Failed to send ping message: " + ex.getMessage());
                        return null;
                    });
                }
            }, pingSecondsPeriod, pingSecondsPeriod, TimeUnit.SECONDS);
        });

        deliveryResult.whenComplete((result, error) -> {
            close();
        });

        return deliveryResult.thenApply(result -> {
            String message = result.getMessage().isEmpty() ? "" : (": " + result.getMessage());

            if (result.isSuccess()) {
                logger.info("Success" + message);
            } else {
                logger.info("Failure" + message);
            }

            return result;
        });
    }

    protected WebSocket.Listener buildWsListener() {
        
        return new WebSocket.Listener() {
            private final StringBuilder incomingMessage = new StringBuilder();
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                incomingMessage.append(data);

                if (!last) {
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
                
                String message = incomingMessage.toString();
                incomingMessage.setLength(0);
            
                try {
                    processCommand(mapper.readTree(message));
                } catch (IOException | DecoderException | BIBOException e) {
                    logger.warn("Error during delivery", e);
                    respondWithStatus("CLIENT_ERROR", Optional.ofNullable(e.getMessage()));
                    deliveryResult.complete(new ServiceDeliverySession.DeliveryResult(sessionId, false, e.getMessage(), null));
                }

                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (!deliveryResult.isDone()) {
                    deliveryResult.completeExceptionally(new Exception(reason));
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                logger.warn("Error during obtaining commands: ", error);
                deliveryResult.completeExceptionally(error);
            }
        };
    }

    protected void processCommand(JsonNode node) throws IOException, DecoderException {
        switch (node.get("type").asString()) {
            case "id":
                sessionId = node.get("value").asString();
                logger.info("Session ID: " + sessionId);
                break;
            case "commands":
                List<String> responses = new ArrayList<>();

                for (JsonNode jsonNode : node.get("commands")) {
                    byte[] command = Hex.decodeHex(jsonNode.asString());
                    responses.add(Hex.encodeHexString(card.transceive(command)));
                }

                ObjectNode res = JsonNodeFactory.instance.objectNode();
                res.put("type", "responses");
                res.set("responses", mapper.valueToTree(responses));
                respond(res);
                break;
            case "status":
                String code = node.get("code").asString();
                String message = Optional.ofNullable(node.get("message")).map(JsonNode::asString).orElse("");

                deliveryResult.complete(
                    new ServiceDeliverySession.DeliveryResult(sessionId, "OK".equals(code), message, null)
                );
                break;
            default:
                throw new IllegalArgumentException("Unsupported message type: " + node.get("type").asString());
        }
    }

    protected void respondWithStatus(String code, Optional<String> message) {
        try {
            ObjectNode res = JsonNodeFactory.instance.objectNode();
            res.put("type", "status");
            res.put("code", code);
            message.ifPresent(m -> res.put("message", m));
            respond(res);
        } catch (Exception ex) {
            logger.warn("Failed to send ", ex);
        }
    }

    protected void respond(ObjectNode node) {
        if (client == null || client.isOutputClosed()) {
            throw new IllegalStateException("WebSocket is not connected on sending: " + mapper.writeValueAsString(node));
        }
        client.sendText(mapper.writeValueAsString(node), true).join();
    }

    private void close() {
        WebSocket wsClient = client;
        scheduler.shutdown();
        if (wsClient != null && !wsClient.isOutputClosed()) {
            wsClient
                .sendClose(WebSocket.NORMAL_CLOSURE, "done")
                .exceptionally(error -> {
                    logger.debug("Failed to close websocket gracefully", error);
                    return null;
                });
        }
    }
}