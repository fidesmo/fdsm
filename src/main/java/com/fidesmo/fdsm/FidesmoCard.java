package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import com.payneteasy.tlv.BerTag;
import com.payneteasy.tlv.BerTlv;
import com.payneteasy.tlv.BerTlvParser;
import com.payneteasy.tlv.BerTlvs;
import pro.javacard.AID;

import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.io.IOException;
import java.net.URI;
import java.util.*;

// Represents a live, personalized Fidesmo card
public class FidesmoCard {
    public static final AID FIDESMO_APP_AID = AID.fromString("A000000617020002000001");
    private final CardChannel channel;
    private byte[] iin = null;
    private byte[] cin = null;

    private FidesmoCard(CardChannel channel) {
        this.channel = channel;
    }

    public static FidesmoCard getInstance(CardChannel channel) throws CardException {
        FidesmoCard card = new FidesmoCard(channel);
        if (!card.detect())
            throw new IllegalArgumentException("Did not detect a Fidesmo card!");
        return card;
    }

    public void deliverRecipe(AuthenticatedFidesmoApiClient client, String recipe) throws CardException, IOException {
        deliverRecipes(client, Collections.singletonList(recipe));
    }

    public void deliverRecipes(AuthenticatedFidesmoApiClient client, List<String> recipes) throws CardException, IOException {
        ServiceDeliverySession session = ServiceDeliverySession.getInstance(this, client);

        for (String recipe : recipes) {
            String uuid = UUID.randomUUID().toString();
            URI uri = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, client.getAppId(), uuid);
            client.put(uri, recipe);
            // When Ctrl-C is pressed from the command line, try not to leave behind stale recipes
            Thread cleanup = new Thread(() -> {
                try {
                    System.out.println("Ctrl-C received, removing temporary recipe ...");
                    client.delete(uri);
                } catch (IOException e) {
                    System.err.println("Failed to remove temporary recipe");
                }
            });
            Runtime.getRuntime().addShutdownHook(cleanup);

            try {
                session.deliver(client.getAppId(), uuid);
            } finally {
                client.delete(uri);
                Runtime.getRuntime().removeShutdownHook(cleanup);
            }
        }
    }

    public byte[] transmit(byte[] bytes) throws CardException {
        return channel.transmit(new CommandAPDU(bytes)).getBytes();
    }

    public byte[] getIIN() {
        return iin.clone();
    }

    public byte[] getCIN() {
        return cin.clone();
    }

    public boolean detect() throws CardException {
        CommandAPDU selectISD = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 0x00);
        ResponseAPDU response = channel.transmit(selectISD);
        if (response.getSW() != 0x9000)
            return false;
        // Read IIN + CIN
        CommandAPDU getDataIIN = new CommandAPDU(0x00, 0xCA, 0x00, 0x42, 0x00);
        response = channel.transmit(getDataIIN);
        if (response.getSW() != 0x9000)
            return false;
        iin = response.getData();
        CommandAPDU getDataCIN = new CommandAPDU(0x00, 0xCA, 0x00, 0x45, 0x00);
        response = channel.transmit(getDataCIN);
        if (response.getSW() != 0x9000)
            return false;
        cin = response.getData();
        // Read batch data
        CommandAPDU selectFidesmo = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, FIDESMO_APP_AID.getBytes());
        response = channel.transmit(selectISD);
        if (response.getSW() != 0x9000)
            return false;
        return true;
    }

    public List<byte[]> listApps() throws CardException {
        // Fidesmo apps prefix
        final byte[] prefix = HexUtils.hex2bin("A00000061701");
        List<byte[]> apps = new LinkedList<>();
        CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, prefix);
        ResponseAPDU response;
        do {
            response = channel.transmit(select);
            if (response.getSW() == 0x9000) {
                byte[] appID = Arrays.copyOfRange(extractAid(response.getData()), 6, 10);
                if (appID == null)
                    throw new CardException("Invalid response from card: " + HexUtils.bin2hex(response.getData()));
                apps.add(appID);
                select = new CommandAPDU(0x00, 0xA4, 0x04, 0x02, prefix);
            }
        } while (response.getSW() == 0x9000);
        return apps;
    }

    private static byte[] extractAid(byte[] selectResponse) {
        BerTlvParser parser = new BerTlvParser();
        BerTlvs tlvs = parser.parse(selectResponse);
        BerTlv fci = tlvs.find(new BerTag(0x6F));
        if (fci != null) {
            BerTlv aid = fci.find(new BerTag(0x84));
            if (aid != null) {
                return aid.getBytesValue();
            }
        }
        return null;
    }
}
