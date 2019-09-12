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
import com.payneteasy.tlv.BerTag;
import com.payneteasy.tlv.BerTlv;
import com.payneteasy.tlv.BerTlvParser;
import com.payneteasy.tlv.BerTlvs;
import pro.javacard.AID;

import javax.security.auth.callback.UnsupportedCallbackException;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.io.IOException;
import java.net.URI;
import java.util.*;

// Represents a live, personalized Fidesmo card
public class FidesmoCard {
    public enum ChipPlatform {

        UNKNOWN(0),
        JCOP242R1(1),
        JCOP242R2(2),
        JCOP3EMV(3),
        JCOP3SECIDCS(4),
        ST31(5);

        // TODO Add Gemalto Optelio G277 and NXP JCOP 4 P71 (also to CPLC_PLATFORMS map)

        private int v;

        ChipPlatform(int v) {
            this.v = v;
        }

        public static ChipPlatform valueOf(int v) {
            for (ChipPlatform t : values()) {
                if (t.v == v)
                    return t;
            }
            return UNKNOWN;
        }
    }

    static HashMap<byte[], ChipPlatform> CPLC_PLATFORMS = new HashMap<>();

    static {
        // ICFabricator=4790
        // ICType=5168
        // OperatingSystemID=4791
        // OperatingSystemReleaseDate=1210 (2011-07-29)
        // OperatingSystemReleaseLevel=3800
        CPLC_PLATFORMS.put(HexUtils.hex2bin("47905168479112103800"), ChipPlatform.JCOP242R1);

        // ICFabricator=4790
        // ICType=5075
        // OperatingSystemID=4791
        // OperatingSystemReleaseDate=2081 (2012-03-21)
        // OperatingSystemReleaseLevel=3B00
        CPLC_PLATFORMS.put(HexUtils.hex2bin("47905075479120813B00"), ChipPlatform.JCOP242R2);

        // ICFabricator=4790
        // ICType=6B64
        // OperatingSystemID=4700
        // OperatingSystemReleaseDate=E4D8 (invalid date format)
        // OperatingSystemReleaseLevel=0300
        CPLC_PLATFORMS.put(HexUtils.hex2bin("47906B644700E4D80300"), ChipPlatform.JCOP3EMV);

        // ICFabricator=4790
        // ICType=0503
        // OperatingSystemID=8211
        // OperatingSystemReleaseDate=6351 (2016-12-16)
        // OperatingSystemReleaseLevel=0302
        CPLC_PLATFORMS.put(HexUtils.hex2bin("47900503821163510302"), ChipPlatform.JCOP3SECIDCS);

        // ICFabricator=4750
        // ICType=00B8
        // OperatingSystemID=4750
        // OperatingSystemReleaseDate=7248 (2017-09-05)
        // OperatingSystemReleaseLevel=5431
        CPLC_PLATFORMS.put(HexUtils.hex2bin("475000B8475072485431"), ChipPlatform.ST31);
    }

    // Given CPLC, detect the platform from enumeration
    public static ChipPlatform detectPlatform(byte[] cplc) {
        for (Map.Entry<byte[], ChipPlatform> e : CPLC_PLATFORMS.entrySet()) {
            if (Arrays.equals(e.getKey(), Arrays.copyOf(cplc, e.getKey().length)))
                return e.getValue();
        }
        return ChipPlatform.UNKNOWN;
    }

    // Capabilities applet AID
    public static final AID FIDESMO_APP_AID = AID.fromString("A000000617020002000001");
    public static final AID FIDESMO_BATCH_AID = AID.fromString("A000000617020002000002");
    public static final AID FIDESMO_PLATFORM_AID = AID.fromString("A00000061702000900010101");

    public static final List<byte[]> FIDESMO_CARD_AIDS = Arrays.asList(
        FIDESMO_APP_AID.getBytes(), FIDESMO_PLATFORM_AID.getBytes()
    );

    private final CardChannel channel;
    private byte[] uid = null;
    private byte[] cin = null;
    private byte[] cplc = null;
    private byte[] batchId = null;

    private FidesmoCard(CardChannel channel) {
        this.channel = channel;
    }

    public static FidesmoCard getInstance(CardChannel channel) throws CardException {
        FidesmoCard card = new FidesmoCard(channel);
        if (!(card.detectPlatformV2() || card.detectPlatformV3()))
            throw new IllegalArgumentException("Did not detect a Fidesmo card!");
        return card;
    }

    public static FidesmoCard fakeInstance(CardChannel channel) {
        FidesmoCard card = new FidesmoCard(channel);
        card.uid = new byte[7];
        card.cin = new byte[7];
        card.batchId = new byte[7];
        return card;
    }

    public boolean deliverRecipe(AuthenticatedFidesmoApiClient client, FormHandler formHandler, String recipe) throws CardException, IOException, UnsupportedCallbackException {
        return deliverRecipes(client, formHandler, Collections.singletonList(recipe));
    }

    public boolean deliverRecipes(AuthenticatedFidesmoApiClient client, FormHandler formHandler, List<String> recipes) throws CardException, IOException, UnsupportedCallbackException {
        ServiceDeliverySession session = ServiceDeliverySession.getInstance(this, client, formHandler);

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
                if (!session.deliver(client.getAppId(), uuid).isSuccess()) {
                    return false;
                }
            } finally {
                client.delete(uri);
                Runtime.getRuntime().removeShutdownHook(cleanup);
            }
        }
        return true;
    }

    public byte[] transmit(byte[] bytes) throws CardException {
        return channel.transmit(new CommandAPDU(bytes)).getBytes();
    }

    public byte[] getCIN() {
        return cin.clone();
    }

    public byte[] getBatchId() {
        return batchId.clone();
    }

    public ChipPlatform getPlatform() {
        return detectPlatform(cplc);
    }

    public Optional<byte[]> getUID() {
        return Optional.ofNullable(uid);
    }

    public boolean detectPlatformV2() throws CardException {
        // Select ISD
        CommandAPDU selectISD = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 0x00);
        ResponseAPDU response = channel.transmit(selectISD);
        if (response.getSW() != 0x9000)
            return false;

        // See if we get the UID from ACS(-compatible) readers
        // NOTE: to make sure we get a sane response if the reader does not support
        // the command, ISD MUST be selected before this command
        CommandAPDU getUID = new CommandAPDU(HexUtils.hex2bin("FFCA000000"));
        response = channel.transmit(getUID);
        // Sensibility check: UID size
        if (response.getSW() == 0x9000 && response.getData().length <= 7) {
            uid = response.getData();
        }
        // Get CPLC
        CommandAPDU getCPLC = new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 0x00);
        response = channel.transmit(getCPLC);
        if (response.getSW() != 0x9000 || response.getData().length == 0)
            return false;
        byte[] data = response.getData();
        // Remove tag, if present
        if (data[0] == (byte) 0x9f && data[1] == (byte) 0x7f && data[2] == (byte) 0x2A)
            data = Arrays.copyOfRange(data, 3, data.length);
        cplc = data;

        // Read CIN
        CommandAPDU getDataCIN = new CommandAPDU(0x00, 0xCA, 0x00, 0x45, 0x00);
        response = channel.transmit(getDataCIN);
        if (response.getSW() != 0x9000)
            return false;
        cin = response.getData();

        // Read batchID
        CommandAPDU selectFidesmoBatch = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, FIDESMO_BATCH_AID.getBytes());
        response = channel.transmit(selectFidesmoBatch);
        if (response.getSW() != 0x9000)
            return false;
        BerTlvParser parser = new BerTlvParser();
        BerTlvs tlvs = parser.parse(fixup(response.getData()));
        BerTlv batchIdTag = tlvs.find(new BerTag(0x42));
        if (batchIdTag != null) {
            batchId = batchIdTag.getBytesValue();
        }
        return true;
    }

    public boolean detectPlatformV3() throws CardException {
        // Select ISD
        CommandAPDU selectISD = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 0x00);
        ResponseAPDU response = channel.transmit(selectISD);
        if (response.getSW() != 0x9000)
            return false;

        // See if we get the UID from ACS(-compatible) readers
        // NOTE: to make sure we get a sane response if the reader does not support
        // the command, ISD MUST be selected before this command
        CommandAPDU getUID = new CommandAPDU(HexUtils.hex2bin("FFCA000000"));
        response = channel.transmit(getUID);
        // Sensibility check: UID size
        if (response.getSW() == 0x9000 && response.getData().length <= 7) {
            uid = response.getData();
        }
        // Get CPLC
        CommandAPDU getCPLC = new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 0x00);
        response = channel.transmit(getCPLC);
        if (response.getSW() != 0x9000 || response.getData().length == 0)
            return false;
        byte[] data = response.getData();
        // Remove tag, if present
        if (data[0] == (byte) 0x9f && data[1] == (byte) 0x7f && data[2] == (byte) 0x2A)
            data = Arrays.copyOfRange(data, 3, data.length);
        cplc = data;

        // Select Platform applet
        CommandAPDU selectFidesmoPlatform = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, FIDESMO_PLATFORM_AID.getBytes());
        response = channel.transmit(selectFidesmoPlatform);
        if (response.getSW() != 0x9000)
            return false;
        BerTlvParser parser = new BerTlvParser();
        BerTlvs tlvs = parser.parse(fixup(response.getData()));

        // Read BatchId
        BerTlv batchIdTag = tlvs.find(new BerTag(0x42));
        if (batchIdTag != null) {
            batchId = batchIdTag.getBytesValue();
        }

        // Read CIN
        BerTlv cinTag = tlvs.find(new BerTag(0x45));
        if (cinTag != null) {
            cin = cinTag.getBytesValue();
        }

        return true;
    }

    // Remove the trailing 0x00 if the format is not TLV
    private byte[] fixup(byte[] v) {
        if (v.length > 0 && v[v.length - 1] == 0x00) {
            try {
                BerTlvParser parser = new BerTlvParser();
                parser.parse(v);
                return v;
            } catch (ArrayIndexOutOfBoundsException e) {
                return Arrays.copyOf(v, v.length - 1);
            }
        }
        return v;
    }

    public List<byte[]> listApps() throws CardException {
        // Fidesmo RID
        final byte[] prefix = HexUtils.hex2bin("A00000061701");
        List<byte[]> apps = new LinkedList<>();
        CommandAPDU select = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, prefix, 256);
        ResponseAPDU response;
        do {
            response = channel.transmit(select);
            if (response.getSW() == 0x9000) {
                byte[] aid = extractAid(response.getData());
                // We assume it is a SSD if we can extract the AID from FCI
                if (aid != null) {
                    byte[] appID = Arrays.copyOfRange(aid, 6, 10);
                    apps.add(appID);
                }
                // Select next
                select = new CommandAPDU(0x00, 0xA4, 0x04, 0x02, prefix, 256);
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
