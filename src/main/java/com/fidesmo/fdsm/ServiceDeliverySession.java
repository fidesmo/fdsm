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

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.smartcardio.CardException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;

// Delivers a service (with no user interaction) to a card
public class ServiceDeliverySession {
    private final FidesmoApiClient client;
    private final FidesmoCard card;
    private final FormHandler formHandler;
    private final ObjectMapper mapper = new ObjectMapper();

    private ServiceDeliverySession(FidesmoCard card, FidesmoApiClient client, FormHandler formHandler) {
        this.card = card;
        this.client = client;
        this.formHandler = formHandler;
    }

    public static ServiceDeliverySession getInstance(FidesmoCard card, FidesmoApiClient client, FormHandler formHandler) {
        return new ServiceDeliverySession(card, client, formHandler);
    }

    public boolean deliver(String appId, String serviceId) throws CardException, IOException {
        // Query service parameters
        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_URL, appId, serviceId), null);

        JsonNode description = service.get("description");
        // We don't do BankID
        if (description.has("bankIdRequired") && description.get("bankIdRequired").asBoolean()) {
            //throw new NotSupportedException("fdsm does not support BankID");
        }

        // Construct deliveryrequest
        ObjectNode deliveryrequest = JsonNodeFactory.instance.objectNode();

        deliveryrequest.put("appId", appId);
        deliveryrequest.put("serviceId", serviceId);

        // Service description. Echoed verbatim
        deliveryrequest.set("description", description);

        // cardId
        ObjectNode cardId = JsonNodeFactory.instance.objectNode();
        cardId.put("iin", HexUtils.bin2hex(card.getIIN()));
        cardId.put("cin", HexUtils.bin2hex(card.getCIN()));
        cardId.put("platformVersion", card.platformVersion);
        deliveryrequest.set("cardId", cardId);

        // User input fields
        ArrayList<Field> fields = new ArrayList<>(fieldsFromNode(description.get("fieldsRequired")));
        // Old style email hack
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

        // Capabilities, partial
        ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
        capabilities.put("platformVersion", card.platformVersion);
        capabilities.put("mifareType", card.mifareType);
        capabilities.put("osTypeVersion", card.platformType);
        capabilities.put("globalPlatformVersion", 0x0202); // Fixed to GlobalPlatform 2.2
        capabilities.put("jcVersion", 0x0300); // Fixed to JavaCard 3.0
        deliveryrequest.set("capabilities", capabilities);

        JsonNode delivery = client.rpc(client.getURI(FidesmoApiClient.SERVICE_DELIVER_URL), deliveryrequest);
        String sessionId = delivery.get("sessionId").asText();
        System.out.println("Delivering: " + FidesmoApiClient.lamei18n(description.get("title")));
        System.out.println("Session ID: " + sessionId);

        ObjectNode fetchrequest = emptyFetchRequest(sessionId);
        // Now loop getting the operations
        while (true) {
            JsonNode fetch = client.rpc(client.getURI(FidesmoApiClient.SERVICE_FETCH_URL), fetchrequest);

            // Check if done
            if (fetch.get("completed").asBoolean()) {
                if (fetch.get("status").get("success").asBoolean()) {
                    System.out.println("Success: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
                    return true;
                } else {
                    System.out.println("Failure: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
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


    protected ObjectNode processUserAction(JsonNode operation, String sessionId) throws IOException {

        JsonNode commands = operation.get("actions");
        for (JsonNode cmd : commands) {
            String action = cmd.get("name").asText();
            switch (action) {
                case "phonecall":
                    System.out.println(FidesmoApiClient.lamei18n(cmd.get("description")));
                    System.console().readLine("Please call " + cmd.get("parameters").get("number").asText() + " and press ENTER to continue!");
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
        ObjectNode operationResult = JsonNodeFactory.instance.objectNode();
        operationResult.put("sessionId", sessionId);
        operationResult.put("statusCode", sessionId);
        return operationResult;
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
}
