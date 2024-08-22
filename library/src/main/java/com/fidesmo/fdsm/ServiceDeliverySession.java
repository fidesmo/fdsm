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

import apdu4j.core.APDUBIBO;
import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.TextInputCallback;
import javax.security.auth.callback.TextOutputCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

// Delivers a service to a card
public class ServiceDeliverySession implements Callable<ServiceDeliverySession.DeliveryResult> {
    private final static Logger logger = LoggerFactory.getLogger(ServiceDeliverySession.class);
    private final static long SESSION_TIMEOUT_MINUTES = 15;
    private long sessionTimeoutMillis;
    private final FidesmoApiClient client;
    private final Supplier<BIBO> biboSupplier; // A supplier to make sure a right thread-local is given when run in executor thread
    private final FidesmoCard card;
    private final FormHandler formHandler;
    private final String appId;
    private final String serviceId;
    private final ObjectMapper mapper = new ObjectMapper();
    // For cancellation and threading and cleanup
    final ArrayList<Runnable> cleanups = new ArrayList<>();

    private ServiceDeliverySession(Supplier<BIBO> biboSupplier, FidesmoCard card, FidesmoApiClient client, String appId, String serviceId, FormHandler formHandler) {
        this.card = card;
        this.biboSupplier = biboSupplier;
        this.client = client;
        this.formHandler = formHandler;
        this.appId = appId;
        this.serviceId = serviceId;
        setTimeoutMinutes(SESSION_TIMEOUT_MINUTES);
    }

    public void setTimeoutMinutes(long minutes) {
        sessionTimeoutMillis = TimeUnit.MINUTES.toMillis(minutes);
    }

    public static ServiceDeliverySession getInstance(Supplier<BIBO> biboSupplier, FidesmoCard card, FidesmoApiClient client, String appId, String serviceId, FormHandler formHandler) {
        return new ServiceDeliverySession(biboSupplier, card, client, appId, serviceId, formHandler);
    }

    @Override
    public DeliveryResult call() throws FDSMException {
        BIBO bibo = biboSupplier.get();
        try {
            return deliver(bibo, appId, serviceId);
        } catch (IOException | BIBOException | UnsupportedCallbackException e) {
            throw new FDSMException(e.getMessage(), e);
        } finally {
            // Do any cleanups. We run them here
            for (Runnable r : cleanups)
                r.run();
        }
    }

    public DeliveryResult deliver(BIBO bibo, String appId, String serviceId) throws IOException, UnsupportedCallbackException {
        APDUBIBO apduBibo = new APDUBIBO(bibo);
        card.selectEmpty(apduBibo);
        // Address #4
        JsonNode deviceInfo = client.rpc(client.getURI(FidesmoApiClient.DEVICES_URL, HexUtils.bin2hex(card.getCIN()), card.getBatchId()));
        byte[] iin = HexUtils.decodeHexString_imp(deviceInfo.get("iin").asText());
        JsonNode capabilities = deviceInfo.get("description").get("capabilities");
        int platformVersion = capabilities.get("platformVersion").asInt();

        // Query service parameters
        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_FOR_CARD_URL, appId, serviceId, HexUtils.bin2hex(card.getCIN())), null);

        // We do not support paid services
        if (service.has("price")) {
            throw new FDSMException("Services requiring payment are not supported by fdsm. Please use the Android app!");
        }

        JsonNode description = service.get("description");

        // Extract SP public key
        final PublicKey spKey;
        if (description.has("certificate")) {
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(HexUtils.hex2bin(description.get("certificate").asText()))
                );
                spKey = cert.getPublicKey();
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not extract public key of service provider", e);
            }
        } else {
            spKey = null;
        }

        // Construct Delivery Request
        ObjectNode deliveryRequest = JsonNodeFactory.instance.objectNode();

        deliveryRequest.put("appId", appId);
        deliveryRequest.put("serviceId", serviceId);

        // cardId
        ObjectNode cardId = JsonNodeFactory.instance.objectNode();
        cardId.put("iin", HexUtils.bin2hex(iin));
        cardId.put("cin", HexUtils.bin2hex(card.getCIN()));
        cardId.put("platformVersion", platformVersion);
        deliveryRequest.set("cardId", cardId);

        // User input fields
        ArrayList<Field> fields = new ArrayList<>(fieldsFromNode(description.get("fieldsRequired")));
        // Old style fields hack
        if (description.has("emailRequired"))
            fields.add(new Field("email", FidesmoApiClient.lamei18n(description.get("emailRequired")), "edit", null));
        if (description.has("msisdnRequired"))
            fields.add(new Field("msisdn", FidesmoApiClient.lamei18n(description.get("msisdnRequired")), "edit", null));

        Map<String, Field> userInput = formHandler.processForm(fields);
        if (description.has("emailRequired"))
            deliveryRequest.put("email", userInput.remove("email").getValue());
        if (description.has("msisdnRequired"))
            deliveryRequest.put("msisdn", userInput.remove("msisdn").getValue());

        deliveryRequest.set("fields", mapToJsonNode(userInput));

        JsonNode delivery = client.rpc(client.getURI(FidesmoApiClient.SERVICE_DELIVER_URL), deliveryRequest);
        String sessionId = delivery.get("sessionId").asText();

        logger.info("Delivering: {}", FidesmoApiClient.lamei18n(description.get("title")));
        logger.info("Session ID: {}", sessionId);

        try {
            return deliveryLoop(bibo, sessionId, spKey);
        } catch (Exception e) {
            notifyDeliveryFailure(sessionId, e.getMessage());
            // And escalate the exception to caller as well.
            throw e;
        }
    }

    protected DeliveryResult deliveryLoop(BIBO bibo, String sessionId, PublicKey spKey) throws IOException, UnsupportedCallbackException {
        ObjectNode fetchrequest = emptyFetchRequest(sessionId);
        long lastActivity = System.currentTimeMillis();

        // Now loop getting the operations
        while (true) {
            deliveryInterruptionPoint();

            try {
                JsonNode fetch = rpcWithRetry(client.getURI(FidesmoApiClient.SERVICE_FETCH_URL), fetchrequest, 5);
                // Successful fetch extends timeout
                lastActivity = System.currentTimeMillis();

                // Check if done
                if (fetch.get("completed").asBoolean()) {
                    JsonNode statusNode = fetch.get("status");

                    DeliveryResult result = new DeliveryResult(
                            sessionId,
                            statusNode.get("success").asBoolean(),
                            FidesmoApiClient.lamei18n(statusNode.get("message")),
                            statusNode.has("scriptStatus") ? FidesmoApiClient.lamei18n(statusNode.get("scriptStatus")) : null
                    );

                    if (result.isSuccess()) {
                        logger.info("Success: " + result.getMessage());
                    } else {
                        logger.info("Failure: " + result.getMessage() + result.getScriptStatus().map(status -> "\nScript status: " + status).orElse(""));
                    }

                    return result;
                }

                // Process operations
                deliveryInterruptionPoint();
                String operationType = fetch.get("operationType").asText();
                switch (operationType) {
                    case "transceive":
                        fetchrequest = processTransmitOperation(bibo, fetch.get("operationId"), sessionId);
                        break;
                    case "user-interaction":
                        fetchrequest = processUIOperation(fetch, sessionId, spKey);
                        break;
                    case "action":
                        fetchrequest = processUserAction(fetch, sessionId);
                        break;
                    default:
                        throw new FDSMException("Unsupported operation: " + fetch);
                }
            } catch (HttpResponseException e) {
                if (e.getStatusCode() == 503) {
                    long timeSpent = System.currentTimeMillis() - lastActivity;
                    if (timeSpent < sessionTimeoutMillis) {
                        logger.warn("Timeout, but re-trying for another {}", time((sessionTimeoutMillis - timeSpent)));
                        continue;
                    }
                }
                throw e;
            }
        }
    }

    protected ObjectNode processTransmitOperation(BIBO bibo, JsonNode operationId, String sessionId) throws IOException {
        ObjectNode transmitrequest = JsonNodeFactory.instance.objectNode();
        transmitrequest.set("uuid", operationId);
        transmitrequest.put("open", true);
        transmitrequest.putArray("responses"); // Empty, to signal "start sending"

        while (true) {
            JsonNode transmit = client.rpc(client.getURI(FidesmoApiClient.CONNECTOR_URL), transmitrequest);
            JsonNode commands = transmit.get("commands");
            // Check if there are commands
            if (commands.size() > 0) {
                ArrayList<String> responses = new ArrayList<>();
                for (JsonNode cmd : commands) {
                    deliveryInterruptionPoint();
                    responses.add(HexUtils.bin2hex(bibo.transceive(HexUtils.hex2bin(cmd.asText()))));
                }

                transmitrequest.set("responses", mapper.valueToTree(responses));
            } else {
                break;
            }
        }

        // Send an empty fetch request when APDU-s are done
        return emptyFetchRequest(sessionId);
    }

    /**
     * Used for service/fetch endpoint where an empty result means that client needs to retry fetching later.
     */
    private JsonNode rpcWithRetry(URI uri, JsonNode request, int retries) throws IOException {
        JsonNode node = client.rpc(uri, request);

        if (node != null) {
            return node;
        } else if (retries > 0) {
            // response is not ready, retry after timeout
            try {
                Thread.sleep(500);
            } catch (InterruptedException iex) {
                // Set the flag again and let deliveryLoop finish
                Thread.currentThread().interrupt();
            }
            return rpcWithRetry(uri, request, retries - 1);
        } else {
            throw new IOException("Unable to fetch request after all retries");
        }
    }

    protected ObjectNode processUIOperation(JsonNode operation, String sessionId, final PublicKey spKey) throws IOException {
        // Check for encryption
        boolean encrypted = operation.has("encrypted") && operation.get("encrypted").asBoolean();
        if (encrypted && spKey == null) {
            throw new IOException("Invalid request: encryption required but no public key available!");
        }

        // Get input
        List<Field> fields = fieldsFromNode(operation.get("fields"));
        Map<String, Field> responses = formHandler.processForm(fields);

        // Construct response
        ObjectNode operationResult = JsonNodeFactory.instance.objectNode();
        JsonNode operationId = operation.get("operationId");

        operationResult.set("operationId", operationId);

        // Send fields, encrypting as needed
        ObjectNode values = JsonNodeFactory.instance.objectNode();

        try {
            // Generate session key
            final Key sessionKey;
            if (encrypted)
                sessionKey = generateEphemeralKey();
            else sessionKey = null;

            // From map to JSON, encrypting or expanding as needed
            for (Map.Entry<String, Field> v : responses.entrySet()) {
                deliveryInterruptionPoint();

                String value;

                // special handling for PAN
                if (v.getValue().getType().equals("paymentcard")) {
                    // God forgive me, for I have sinned
                    String[] elements = v.getValue().getValue().split(";");
                    String[] date = elements[1].split("/");
                    // Construct JSON
                    ObjectNode paymentcard = JsonNodeFactory.instance.objectNode();
                    paymentcard.put("cardNumber", elements[0]);
                    paymentcard.put("expiryMonth", Integer.parseInt(date[0]));
                    paymentcard.put("expiryYear", Integer.parseInt(date[1]));

                    // CVV code is optional
                    if (elements.length > 2) {
                        paymentcard.put("cvv", elements[2]);
                    }

                    String payload = mapper.writeValueAsString(paymentcard);

                    if (encrypted) {
                        value = HexUtils.bin2hex(encrypt(payload, sessionKey));
                        values.put(v.getKey(), value);
                    } else {
                        values.put(v.getKey(), payload);
                    }
                    continue;
                }
                // "Normal" value
                if (encrypted) {
                    value = HexUtils.bin2hex(encrypt(v.getValue().getValue(), sessionKey));
                } else {
                    value = v.getValue().getValue();
                }
                values.put(v.getKey(), value);
            }
            operationResult.set("fields", values);
            operationResult.put("statusCode", 200);

            if (encrypted) {
                operationResult.put("ephemeralKey", HexUtils.bin2hex(encryptSessionKey(spKey, sessionKey)));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not handle response encryption", e);
        }

        ObjectNode fetchRequest = emptyFetchRequest(sessionId);
        fetchRequest.set("operationResult", operationResult);

        return fetchRequest;
    }


    protected ObjectNode processUserAction(JsonNode operation, String sessionId) throws IOException, UnsupportedCallbackException {

        JsonNode commands = operation.get("actions");
        for (JsonNode cmd : commands) {
            deliveryInterruptionPoint();
            String action = cmd.get("name").asText();
            switch (action) {
                case "phonecall":
                    // TODO: have all in one callback and utilize ConfirmationCallback
                    TextOutputCallback cb1 = new TextOutputCallback(TextOutputCallback.INFORMATION, FidesmoApiClient.lamei18n(cmd.get("description")));
                    TextOutputCallback cb2 = new TextOutputCallback(TextOutputCallback.INFORMATION, "Please call " + cmd.get("parameters").get("number").asText());
                    TextInputCallback cb3 = new TextInputCallback("Press ENTER to continue"); // XXX: a bit tied to the implementation
                    formHandler.handle(new Callback[]{cb1, cb2, cb3});
                    break;
                default:
                    TextOutputCallback genCb1 = new TextOutputCallback(TextOutputCallback.INFORMATION, FidesmoApiClient.lamei18n(cmd.get("description")));
                    TextInputCallback genCb2 = new TextInputCallback("Press ENTER to continue"); // XXX: a bit tied to the implementation
                    formHandler.handle(new Callback[]{genCb1, genCb2});
            }
        }

        ObjectNode fetchRequest = emptyFetchRequest(sessionId);

        ObjectNode operationResult = JsonNodeFactory.instance.objectNode();
        operationResult.set("operationId", operation.get("operationId"));
        fetchRequest.set("operationResult", operationResult);
        return fetchRequest;
    }

    protected void notifyDeliveryFailure(String sessionId, String message) throws IOException {
        try {
            ObjectNode deliveryError = JsonNodeFactory.instance.objectNode();
            deliveryError.put("sessionId", sessionId);
            deliveryError.put("message", message);
            deliveryError.put("fatal", true);
            client.rpc(client.getURI(FidesmoApiClient.SERVICE_DELIVERY_ERROR_URL), deliveryError);
        } catch (HttpResponseException e) {
            logger.warn("Delivery error reporting got a {}", e.getStatusCode());
        }
    }

    private byte[] encrypt(String value, Key key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(new byte[16]));
        return c.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private Key generateEphemeralKey() throws GeneralSecurityException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        return keyGenerator.generateKey();
    }

    private byte[] encryptSessionKey(PublicKey publicKey, Key key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-512AndMGF1Padding");
        // NOTE: SunJCE default parameters do not match the string above, so must give them as explicit parameters
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(key.getEncoded());
    }

    private ObjectNode emptyFetchRequest(String sessionId) {
        ObjectNode fetchRequest = JsonNodeFactory.instance.objectNode();
        fetchRequest.put("sessionId", sessionId);
        return fetchRequest;
    }

    private ObjectNode mapToJsonNode(Map<String, Field> map) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();

        for (Map.Entry<String, Field> entry : map.entrySet()) {
            node.put(entry.getKey(), entry.getValue().getValue());
        }

        return node;
    }

    private List<Field> fieldsFromNode(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return Collections.emptyList();
        }

        List<Field> fields = new ArrayList<>(fieldsNode.size());

        for (JsonNode fieldNode : fieldsNode) {
            String label = FidesmoApiClient.lamei18n(fieldNode.get("label"));
            fields.add(new Field(
                    fieldNode.get("id").asText(),
                    label,
                    fieldNode.get("type").asText(),
                    Optional.ofNullable(fieldNode.get("format")).map(JsonNode::asText).orElse(null)
            ));
        }

        return fields;
    }

    private String time(long ms) {
        String time = ms + "ms";
        if (ms > 60000) {
            time = (ms / 60000) + "m" + ((ms % 60000) / 1000) + "s";
        } else if (ms > 1000) {
            time = (ms / 1000) + "s" + (ms % 1000) + "ms";
        }
        return time;
    }

    protected void deliveryInterruptionPoint() {
        if (Thread.interrupted()) {
            logger.info("Interrupted - cancelling");
            throw new CancellationException("Interrupted");
        }
    }

    public static class DeliveryResult {
        private final String sessionId;
        private final boolean success;
        private final String message;
        private final String scriptStatus;

        public DeliveryResult(String sessionId, boolean success, String message, String scriptStatus) {
            this.sessionId = sessionId;
            this.success = success;
            this.message = message;
            this.scriptStatus = scriptStatus;
        }

        public String getSessionId() {
            return sessionId;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public Optional<String> getScriptStatus() {
            return Optional.ofNullable(scriptStatus);
        }

        @Override
        public String toString() {
            return "DeliveryResult{" +
                    "sessionId='" + sessionId + '\'' +
                    ", success=" + success +
                    ", message='" + message + '\'' +
                    ", scriptStatus=" + scriptStatus +
                    '}';
        }
    }

    public static ServiceDeliverySession.DeliveryResult deliverService(final RunnableFuture<DeliveryResult> serviceDelivery) {
        Thread cleanup = new Thread(() -> {
            System.err.println("\nCtrl-C received, cancelling delivery");
            serviceDelivery.cancel(true);
            try {
                // leave some time to finish HTTP
                serviceDelivery.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException | CancellationException ignored) {
            }
        });

        Runtime.getRuntime().addShutdownHook(cleanup);

        boolean ran = false;
        try {
            // Run in current thread
            serviceDelivery.run();
            ServiceDeliverySession.DeliveryResult result = serviceDelivery.get();
            ran = true;
            return result;
        } catch (ExecutionException e) {
            ran = true;
            if (e.getCause() instanceof FDSMException)
                throw (FDSMException) e.getCause();
            System.err.println("Failed to run service: " + e.getCause().getMessage());
            throw new RuntimeException("Failed to run service: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            // If main thread gets interrupted ....
            throw new CancellationException("Interrupted");
        } finally {
            try {
                if (ran) Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException ignored) {
                // It's fine to fail to remove the hook if shutdown is already in progress
            }
        }
    }
}
