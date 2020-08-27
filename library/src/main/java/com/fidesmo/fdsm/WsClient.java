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

import apdu4j.BIBO;
import apdu4j.BIBOException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpHeaders;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class WsClient {
    private final static Logger logger = LoggerFactory.getLogger(WsClient.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final URI uri;
    private final Map<String, String> headers;
    private final BIBO card;
    private final WebSocketClient client;
    private final CompletableFuture<ServiceDeliverySession.DeliveryResult> deliveryResult = new CompletableFuture<>();
    private String sessionId;

    public WsClient(URI uri, BIBO card, ClientAuthentication authentication) {
        this.uri = uri;
        this.headers = authentication != null ? Collections.singletonMap(HttpHeaders.AUTHORIZATION, authentication.toAuthenticationHeader()) : null;
        this.card = card;
        this.client = buildClient();
    }

    public static CompletableFuture<ServiceDeliverySession.DeliveryResult> execute(URI uri, BIBO card, ClientAuthentication authentication) {
        return new WsClient(uri, card, authentication).run();
    }

    protected WebSocketClient buildClient() {
        return new WebSocketClient(uri, headers) {
            public void onOpen(ServerHandshake handshake) {
            }

            @Override
            public void onMessage(String data) {
                try {
                    processCommand(mapper.readTree(data));
                } catch (IOException | DecoderException | BIBOException e) {
                    logger.warn("Error during delivery", e);

                    respondWithStatus("CLIENT_ERROR", Optional.of(e.getMessage()));

                    deliveryResult.complete(new ServiceDeliverySession.DeliveryResult(sessionId, false, e.getMessage(), null));

                    close();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!deliveryResult.isDone()) {
                    deliveryResult.completeExceptionally(new Exception(reason));
                }
            }

            @Override
            public void onError(Exception ex) {
                logger.warn("Error during obtaining commands: ", ex);
                deliveryResult.completeExceptionally(ex);
            }
        };

    }

    public CompletableFuture<ServiceDeliverySession.DeliveryResult> run() {
        if (deliveryResult.isDone()) {
            throw new IllegalStateException("WsClient is single-use!");
        }

        client.connect();
        
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

    protected void processCommand(JsonNode node) throws IOException, DecoderException {
        switch (node.get("type").asText()) {
            case "id":
                sessionId = node.get("value").asText();
                logger.info("Session ID: " + sessionId);
                break;
            case "commands":
                List<String> responses = new ArrayList<>();

                for (JsonNode jsonNode : node.get("commands")) {
                    byte[] command = Hex.decodeHex(jsonNode.asText());
                    responses.add(Hex.encodeHexString(card.transceive(command)));
                }

                ObjectNode res = JsonNodeFactory.instance.objectNode();
                res.put("type", "responses");
                res.set("responses", mapper.valueToTree(responses));
                respond(res);
                break;
            case "status":
                String code = node.get("code").asText();
                String message = node.get("message").asText("");

                deliveryResult.complete(
                    new ServiceDeliverySession.DeliveryResult(sessionId, "OK".equals(code), message, null)
                );

                client.close();

                break;
            default:
                throw new IllegalArgumentException("Unsupported message type: " + node.get("type").asText());
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

    protected void respond(ObjectNode node) throws JsonProcessingException {
        client.send(mapper.writeValueAsString(node));
    }

}