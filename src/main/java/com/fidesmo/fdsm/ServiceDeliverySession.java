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

import apdu4j.HexUtils;
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
import javax.smartcardio.CardException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;

// Delivers a service to a card
public class ServiceDeliverySession {
    private final static Logger logger = LoggerFactory.getLogger(ServiceDeliverySession.class);
    private final static long SESSION_TIMEOUT_MINUTES = 15;
    private long sessionTimeout;
    private final FidesmoApiClient client;
    private final FidesmoCard card;
    private final FormHandler formHandler;
    private final ObjectMapper mapper = new ObjectMapper();

    private ServiceDeliverySession(FidesmoCard card, FidesmoApiClient client, FormHandler formHandler) {
        this.card = card;
        this.client = client;
        this.formHandler = formHandler;
        setTimeout(SESSION_TIMEOUT_MINUTES);
    }

    public void setTimeout(long minutes) {
        sessionTimeout = minutes * 60 * 1000;
    }

    public static ServiceDeliverySession getInstance(FidesmoCard card, FidesmoApiClient client, FormHandler formHandler) {
        return new ServiceDeliverySession(card, client, formHandler);
    }

    public boolean deliver(String appId, String serviceId) throws CardException, IOException, UnsupportedCallbackException {
        // Address #4
        JsonNode deviceInfo = client.rpc(client.getURI(FidesmoApiClient.DEVICES_URL, HexUtils.bin2hex(card.getCIN()), new BigInteger(1, card.getBatchId()).toString()));
        byte[] iin = HexUtils.decodeHexString_imp(deviceInfo.get("iin").asText());
        JsonNode capabilities = deviceInfo.get("description").get("capabilities");
        int platformVersion = capabilities.get("platformVersion").asInt();

        // Query service parameters
        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_FOR_CARD_URL, appId, serviceId, HexUtils.bin2hex(card.getCIN())), null);

        // We do not support paid services
        if (service.has("price")) {
            throw new NotSupportedException("Services requiring payment are not supported by fdsm. Please use the Android app!");
        }

        JsonNode description = service.get("description");
        // Nod do we do BankID
        if (description.has("bankIdRequired") && description.get("bankIdRequired").asBoolean()) {
            throw new NotSupportedException("Services requiring BankID are not supported by fdsm. Please use the Android app!");
        }

        // Construct deliveryrequest
        ObjectNode deliveryrequest = JsonNodeFactory.instance.objectNode();

        deliveryrequest.put("appId", appId);
        deliveryrequest.put("serviceId", serviceId);

        // Service description. Echoed verbatim
        deliveryrequest.set("description", description);

        // cardId
        ObjectNode cardId = JsonNodeFactory.instance.objectNode();
        cardId.put("iin", HexUtils.bin2hex(iin));
        cardId.put("cin", HexUtils.bin2hex(card.getCIN()));
        cardId.put("platformVersion", platformVersion);
        deliveryrequest.set("cardId", cardId);

        // User input fields
        ArrayList<Field> fields = new ArrayList<>(fieldsFromNode(description.get("fieldsRequired")));
        // Old style fields hack
        if (description.has("emailRequired"))
            fields.add(new Field("email", FidesmoApiClient.lamei18n(description.get("emailRequired")), "edit", null));
        if (description.has("msisdnRequired"))
            fields.add(new Field("msisdn", FidesmoApiClient.lamei18n(description.get("msisdnRequired")), "edit", null));

        Map<String, Field> userInput = formHandler.processForm(fields);
        if (description.has("emailRequired"))
            deliveryrequest.put("email", userInput.remove("email").getValue());
        if (description.has("msisdnRequired"))
            deliveryrequest.put("msisdn", userInput.remove("msisdn").getValue());

        deliveryrequest.set("fields", mapToJsonNode(userInput));

        // Capabilities, (re-use received object when requesting device info)
        deliveryrequest.set("capabilities", capabilities);

        JsonNode delivery = client.rpc(client.getURI(FidesmoApiClient.SERVICE_DELIVER_URL), deliveryrequest);
        String sessionId = delivery.get("sessionId").asText();
        logger.info("Delivering: {}", FidesmoApiClient.lamei18n(description.get("title")));
        logger.info("Session ID: {}", sessionId);

        ObjectNode fetchrequest = emptyFetchRequest(sessionId);
        long lastActivity = System.currentTimeMillis();

        // Now loop getting the operations
        while (true) {
            try {
                JsonNode fetch = client.rpc(client.getURI(FidesmoApiClient.SERVICE_FETCH_URL), fetchrequest);
                // Successful fetch extends timeout
                lastActivity = System.currentTimeMillis();

                // Check if done
                if (fetch.get("completed").asBoolean()) {
                    if (fetch.get("status").get("success").asBoolean()) {
                        logger.info("Success: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
                        return true;
                    } else {
                        logger.info("Failure: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
                        return false;
                    }
                }

                // Process operations
                String operationType = fetch.get("operationType").asText();
                switch (operationType) {
                    case "transceive":
                        fetchrequest = processTransmitOperation(fetch.get("operationId"), sessionId);
                        break;
                    case "user-interaction":
                        fetchrequest = processUIOperation(fetch, sessionId, description);
                        break;
                    case "action":
                        fetchrequest = processUserAction(fetch, sessionId);
                        break;
                    default:
                        throw new NotSupportedException("Unsupported operation: " + fetch);
                }
            } catch (HttpResponseException e) {
                if (e.getStatusCode() == 503) {
                    long timeSpent = System.currentTimeMillis() - lastActivity;
                    if (timeSpent < sessionTimeout) {
                        logger.warn("Timeout, but re-trying for another {}", time((sessionTimeout - timeSpent)));
                        continue;
                    }
                }
                throw e;
            }
        }
    }

    protected ObjectNode processTransmitOperation(JsonNode operationId, String sessionId) throws CardException, IOException {
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
                try {
                    for (JsonNode cmd : commands) {
                        responses.add(HexUtils.bin2hex(card.transmit(HexUtils.hex2bin(cmd.asText()))));
                    }
                } catch (CardException e) {
                    // Indicate error to Fidesmo API
                    ObjectNode transmiterror = JsonNodeFactory.instance.objectNode();
                    transmiterror.set("uuid", operationId);
                    transmiterror.put("reason", e.getMessage());
                    client.rpc(client.getURI(FidesmoApiClient.CONNECTOR_ERROR_URL), transmiterror);
                    // And escalate the exception to caller as well.
                    throw e;
                }
                transmitrequest.set("responses", mapper.valueToTree(responses));
            } else {
                break;
            }
        }

        // Send an empty fetch request when APDU-s are done
        return emptyFetchRequest(sessionId);
    }

    protected ObjectNode processUIOperation(JsonNode operation, String sessionId, JsonNode service) throws IOException {
        // Extract SP public key
        final PublicKey spkey;
        if (service.has("certificate")) {
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(HexUtils.hex2bin(service.get("certificate").asText())));
                spkey = cert.getPublicKey();
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not extract public key of service provider", e);
            }
        } else {
            spkey = null;
        }

        // Check for encryption
        boolean encrypted = operation.has("encrypted") && operation.get("encrypted").asBoolean();
        if (encrypted && spkey == null) {
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
                    paymentcard.put("cvv", elements[2]);

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
                operationResult.put("ephemeralKey", HexUtils.bin2hex(encryptSessionKey(spkey, sessionKey)));
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
            String action = cmd.get("name").asText();
            switch (action) {
                case "phonecall":
                    TextOutputCallback cb1 = new TextOutputCallback(TextOutputCallback.INFORMATION, FidesmoApiClient.lamei18n(cmd.get("description")));
                    TextOutputCallback cb2 = new TextOutputCallback(TextOutputCallback.INFORMATION, "Please call " + cmd.get("parameters").get("number").asText());
                    TextInputCallback cb3 = new TextInputCallback("Press ENTER to continue"); // XXX: a bit tied to the implementation
                    formHandler.handle(new Callback[]{cb1, cb2, cb3});
                    break;
                default:
                    throw new NotSupportedException("Unknown action: " + cmd);
            }
        }

        ObjectNode fetchRequest = emptyFetchRequest(sessionId);

        ObjectNode operationResult = JsonNodeFactory.instance.objectNode();
        operationResult.set("operationId", operation.get("operationId"));
        fetchRequest.set("operationResult", operationResult);
        return fetchRequest;
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
        // NOTE: SunJCE default parameters do not match the string above, so must give them as parameters
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

        for (Iterator<JsonNode> it = fieldsNode.iterator(); it.hasNext(); ) {
            JsonNode fieldNode = it.next();

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
}
