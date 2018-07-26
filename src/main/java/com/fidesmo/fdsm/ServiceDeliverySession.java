package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.smartcardio.CardException;
import java.io.IOException;
import java.util.ArrayList;

// Delivers a service to a card
// TODO: possibly add user interaction
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
        cardId.put("platformVersion", card.platformVersion); // XXX: required here but repeated in capabilities

        deliveryrequest.set("cardId", cardId);
        // Empty fields node
        deliveryrequest.set("fields", JsonNodeFactory.instance.objectNode());

        // Capabilities, partial
        ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
        capabilities.put("platformVersion", card.platformVersion);
        capabilities.put("osTypeVersion", card.platformType);
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
                transmitrequest.put("open", true); // FIXME: document
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
