package com.fidesmo.fdsm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TranslationsTest {

  //  @Test
    public void returnMessagesInOldFormat() {
        String jsonString = "{ \"fr\" : \"Connecter votre carte de paiement\",\n" +
                "      \"it\" : \"Associa carta di pagamento\",\n" +
                "      \"nl\" : \"Koppel uw betaalkaart\",\n" +
                "      \"de\" : \"Karte verbinden\",\n" +
                "      \"ru\" : \"Подключить платежную карту\",\n" +
                "      \"sv\" : \"Anslut betalkort\",\n" +
                "      \"en\" : \"Connect payment card\"\n}";

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "Connect payment card");
    }
   @Test
    public void returnMessagesInNewFormatWithParams() {
        String jsonString = "{  \"id\": \"service.statuses.success\", \"text\": \"Congrats! Now you can pay with your {0}\", \"params\": [ \"Ring!\" ] }";

        // Parse JSON to JsonNode
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "Congrats! Now you can pay with your Ring!");
    }

    @Test
    public void returnMessagesInNewFormatWithMultipleParams() {
        String jsonString = "{  \"id\": \"service.statuses.success\", \"text\": \"Congrats! Now you can pay with your {0} from {1}\", \"params\": [ \"Ring!\", \"your bank\" ] }";

        // Parse JSON to JsonNode
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "Congrats! Now you can pay with your Ring! from your bank");
    }

    @Test
    public void returnMessagesInNewFormatWithApostrophes() {
        String jsonString = "{  \"id\": \"service.statuses.success\", \"text\": \"You're now able to pay with your {0}\", \"params\": [ \"Ring!\"] }";

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "You're now able to pay with your Ring!");
    }

    @Test
    public void returnMessagesInNewFormatWithoutParams() {
        String jsonString = "{  \"id\": \"service.statuses.success\", \"text\": \"Your card has been removed\"}";

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "Your card has been removed");
    }
    @Test
    public void returnMessagesInNewFormatWithEmptyParams() {
        String jsonString = "{  \"id\": \"service.statuses.success\", \"text\": \"Your card has been removed\", \"params\": []}";

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertEquals(FidesmoApiClient.lamei18n(jsonNode), "Your card has been removed");
    }
}
