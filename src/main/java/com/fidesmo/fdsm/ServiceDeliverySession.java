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

import javax.smartcardio.CardException;
import java.io.IOException;
import java.util.ArrayList;

// Delivers a service (with no user interaction) to a card
public class ServiceDeliverySession {
    private final FidesmoApiClient client;
    private final FidesmoCard card;

    private ServiceDeliverySession(FidesmoCard card, FidesmoApiClient client) {
        this.card = card;
        this.client = client;
    }

    public static ServiceDeliverySession getInstance(FidesmoCard card, FidesmoApiClient client) {
        return new ServiceDeliverySession(card, client);
    }

    public boolean deliver(String appId, String serviceId) throws CardException, IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_URL, appId, serviceId), null);

        // Construct deliveryrequest
        ObjectNode deliveryrequest = JsonNodeFactory.instance.objectNode();
        ObjectNode cardId = JsonNodeFactory.instance.objectNode();

        deliveryrequest.put("appId", appId);
        deliveryrequest.put("serviceId", serviceId);
        // Service description. Echoed verbatim
        deliveryrequest.set("description", service.get("description"));


        cardId.put("iin", HexUtils.bin2hex(card.getIIN()));
        cardId.put("cin", HexUtils.bin2hex(card.getCIN()));
        cardId.put("platformVersion", card.platformVersion);

        deliveryrequest.set("cardId", cardId);
        // Empty fields node
        deliveryrequest.set("fields", JsonNodeFactory.instance.objectNode());

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

        // Now loop getting the operations
        while (true) {
            ObjectNode fetchrequest = JsonNodeFactory.instance.objectNode();
            fetchrequest.put("sessionId", sessionId);
            JsonNode fetch = client.rpc(client.getURI(FidesmoApiClient.SERVICE_FETCH_URL), fetchrequest);

            if (fetch.get("completed").asBoolean()) {
                if (fetch.get("status").get("success").asBoolean()) {
                    System.out.println("Success: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
                    return true;
                } else {
                    System.out.println("Failure: " + FidesmoApiClient.lamei18n(fetch.get("status").get("message")));
                    return false;
                }
            }

            // Process transceive operations
            if (!fetch.get("completed").asBoolean() && fetch.get("operationType").asText().equals("transceive")) {
                ObjectNode transmitrequest = JsonNodeFactory.instance.objectNode();
                transmitrequest.set("uuid", fetch.get("operationId"));
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
                            transmiterror.set("uuid", fetch.get("operationId"));
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
            } else {
                break;
            }
        }
        return false;
    }
}
