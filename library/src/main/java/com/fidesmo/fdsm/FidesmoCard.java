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

import apdu4j.core.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payneteasy.tlv.BerTag;
import com.payneteasy.tlv.BerTlv;
import com.payneteasy.tlv.BerTlvParser;
import com.payneteasy.tlv.BerTlvs;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.javacard.AID;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.RunnableFuture;

// Represents a live, personalized Fidesmo card
public class FidesmoCard {
    private final static Logger logger = LoggerFactory.getLogger(FidesmoCard.class);

    public enum ChipPlatform {
        JCOP242R1(1),
        JCOP242R2(2),
        JCOP3EMV(3),
        JCOP3SECID(4),
        ST31(5),
        OPTELIO(6),
        JCOP4(7);

        private final int v;

        ChipPlatform(int v) {
            this.v = v;
        }

        public static Optional<ChipPlatform> valueOf(int v) {
            for (ChipPlatform t : values()) {
                if (t.v == v)
                    return Optional.of(t);
            }
            return Optional.empty();
        }
    }

    static HashMap<HexBytes, ChipPlatform> CPLC_PLATFORMS = new HashMap<>();

    static {
        // ICFabricator=4790
        // ICType=5168
        // OperatingSystemID=4791
        // OperatingSystemReleaseDate=1210 (2011-07-29)
        // OperatingSystemReleaseLevel=3800
        CPLC_PLATFORMS.put(HexBytes.v("47905168479112103800"), ChipPlatform.JCOP242R1);

        // ICFabricator=4790
        // ICType=5075
        // OperatingSystemID=4791
        // OperatingSystemReleaseDate=2081 (2012-03-21)
        // OperatingSystemReleaseLevel=3B00
        CPLC_PLATFORMS.put(HexBytes.v("47905075479120813B00"), ChipPlatform.JCOP242R2);

        // ICFabricator=4790
        // ICType=6B64
        // OperatingSystemID=4700
        // OperatingSystemReleaseDate=E4D8 (invalid date format)
        // OperatingSystemReleaseLevel=0300
        CPLC_PLATFORMS.put(HexBytes.v("47906B644700E4D80300"), ChipPlatform.JCOP3EMV);

        // ICFabricator=4790
        // ICType=0503
        // OperatingSystemID=8211
        // OperatingSystemReleaseDate=6351 (2016-12-16)
        // OperatingSystemReleaseLevel=0302
        CPLC_PLATFORMS.put(HexBytes.v("47900503821163510302"), ChipPlatform.JCOP3SECID);

        // ICFabricator=4750
        // ICType=00B8
        // OperatingSystemID=4750
        // OperatingSystemReleaseDate=7248 (2017-09-05)
        // OperatingSystemReleaseLevel=5431
        CPLC_PLATFORMS.put(HexBytes.v("475000B8475072485431"), ChipPlatform.ST31);

        // ICFabricator=4090
        // ICType=1889
        // OperatingSystemID=1981
        // OperatingSystemReleaseDate=7322 (2017-11-18)
        // OperatingSystemReleaseLevel=0100
        CPLC_PLATFORMS.put(HexBytes.v("40901889198173220100"), ChipPlatform.OPTELIO);

        // ICFabricator=4790
        // ICType=D321
        // OperatingSystemID=4700
        // OperatingSystemReleaseDate=0000 (2010-01-01)
        // OperatingSystemReleaseLevel=0000
        CPLC_PLATFORMS.put(HexBytes.v("4790D321470000000000"), ChipPlatform.JCOP4);
    }

    // Given CPLC, detect the platform from enumeration
    public static Optional<ChipPlatform> detectPlatform(byte[] cplc) {
        for (Map.Entry<HexBytes, ChipPlatform> e : CPLC_PLATFORMS.entrySet()) {
            if (Arrays.equals(e.getKey().value(), Arrays.copyOf(cplc, e.getKey().len())))
                return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    // Capabilities applet AID
    public static final AID FIDESMO_APP_AID = AID.fromString("A000000617020002000001");
    public static final AID FIDESMO_BATCH_AID = AID.fromString("A000000617020002000002");
    public static final AID FIDESMO_PLATFORM_AID = AID.fromString("A00000061702000900010101");

    public static final List<byte[]> FIDESMO_CARD_AIDS = Collections.unmodifiableList(Arrays.asList(
            FIDESMO_APP_AID.getBytes(), FIDESMO_PLATFORM_AID.getBytes()
    ));

    private final Optional<byte[]> uid;
    private final byte[] cin;
    private final byte[] cplc;
    private final int batchId;
    /** Indicates that device is fully batched or requires batching operation otherwise */
    private final boolean batched;

    public FidesmoCard(byte[] fid, byte[] cplc, int batchId, boolean batched, Optional<byte[]> uid) {
        if (fid == null) throw new NullPointerException("fid can't be null");
        this.cin = fid.clone();
        this.cplc = cplc == null ? null : cplc.clone();
        this.batchId = batchId;
        this.batched = batched;
        this.uid = uid;
    }

    public static FidesmoCard dummy() {
        return new FidesmoCard(new byte[7], null, 0, true, Optional.empty());
    }

    @Deprecated
    public static Optional<FidesmoCard> detect(BIBO channel) {
        return detectOffline(channel);
    }

    public static Optional<FidesmoCard> detectOffline(BIBO channel) {
        return detect(probe(channel));
    }

    public static Optional<FidesmoCard> detectOnline(BIBO channel, FidesmoApiClient client) {
        return detect(probe(channel), client);
    }

    static final HexBytes getUID = HexBytes.b(new CommandAPDU(0xFF, 0xCA, 0x00, 0x00, 0x00).getBytes());
    static final HexBytes selectISD = HexBytes.b(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 0x00).getBytes());
    static final HexBytes getCPLC = HexBytes.b(new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 0x00).getBytes());
    static final HexBytes getDataCIN = HexBytes.b(new CommandAPDU(0x80, 0xCA, 0x00, 0x45, 0x00).getBytes());
    static final HexBytes selectFidesmoPlatform = HexBytes.b(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, FIDESMO_PLATFORM_AID.getBytes()).getBytes());
    static final HexBytes selectFidesmoBatch = HexBytes.b(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, FIDESMO_BATCH_AID.getBytes()).getBytes());

    public static Map<HexBytes, byte[]> probe(BIBO channel) {
        // preserve order, just for fun
        Map<HexBytes, byte[]> r = new LinkedHashMap<>();
        APDUBIBO bibo = new APDUBIBO(channel);
        // Get UID
        ResponseAPDU response = bibo.transmit(new CommandAPDU(getUID.value()));
        r.put(getUID, response.getBytes());
        // Select ISD
        response = bibo.transmit(new CommandAPDU(selectISD.value()));
        r.put(selectISD, response.getBytes());
        if (response.getSW() == 0x9000) {
            // Get CPLC (always available for PV2)
            response = bibo.transmit(new CommandAPDU(getCPLC.value()));
            r.put(getCPLC, response.getBytes());
            // Read CIN
            response = bibo.transmit(new CommandAPDU(getDataCIN.value()));
            r.put(getDataCIN, response.getBytes());
        }

        // try PV3
        response = bibo.transmit(new CommandAPDU(selectFidesmoPlatform.value()));
        r.put(selectFidesmoPlatform, response.getBytes());

        if (response.getSW() != 0x9000) {
            // Try PV2
            response = bibo.transmit(new CommandAPDU(selectFidesmoBatch.value()));
            r.put(selectFidesmoBatch, response.getBytes());
        }

        return r;
    }

    static Optional<ResponseAPDU> response(Map<HexBytes, byte[]> map, HexBytes key) {
        return Optional.ofNullable(map.get(key)).map(ResponseAPDU::new);
    }

    static boolean check(ResponseAPDU r) {
        return r.getSW() == 0x9000;
    }

    static Optional<byte[]> fetchTag(int tag, ResponseAPDU response) {
        BerTlvParser parser = new BerTlvParser();
        BerTlvs tlvs = parser.parse(response.getData());
        BerTlv vt = tlvs.find(new BerTag(tag));
        if (vt != null) {
            return Optional.of(vt.getBytesValue());
        }
        return Optional.empty();
    }

    public static Optional<FidesmoCard> detect(Map<HexBytes, byte[]> commands) {
        return detect(commands, null);
    }

    public static Optional<FidesmoCard> detect(Map<HexBytes, byte[]> commands, FidesmoApiClient client) {
        final Optional<byte[]> uid;
        final byte[] cplc;
        final byte[] cin;
        final byte[] batch;

        Optional<byte[]> pv3cin = response(commands, selectFidesmoPlatform)
                .filter(FidesmoCard::check)
                .map(FidesmoCard::fixup)
                .flatMap(a -> fetchTag(0x45, a));

        Optional<byte[]> pv2cin = response(commands, getDataCIN)
                .filter(FidesmoCard::check)
                .flatMap(a -> fetchTag(0x45, a));

        cin = pv3cin.orElseGet(() -> pv2cin.orElse(null));

        uid = response(commands, getUID).filter(FidesmoCard::check).map(ResponseAPDU::getData);

        cplc = response(commands, getCPLC).filter(FidesmoCard::check).map(a -> {
            byte[] data = a.getData();
            if (data[0] == (byte) 0x9f && data[1] == (byte) 0x7f && data[2] == (byte) 0x2A)
                data = Arrays.copyOfRange(data, 3, data.length);
            return data;
        }).orElse(null);

        Optional<byte[]> pv3batch = response(commands, selectFidesmoPlatform)
                .filter(FidesmoCard::check)
                .map(FidesmoCard::fixup)
                .flatMap(a -> fetchTag(0x42, a));

        Optional<byte[]> pv2batch = response(commands, selectFidesmoBatch)
                .filter(FidesmoCard::check)
                .map(FidesmoCard::fixup)
                .flatMap(a -> fetchTag(0x42, a));

        batch = pv3batch.orElseGet(() -> pv2batch.orElse(null));

        if (cin == null || batch == null) {
            if (client == null) return Optional.empty();
            
            // Try to identify device on the server side using CPLC data
            try {
                URI uri = uid
                        .map(value -> client.getURI(FidesmoApiClient.DEVICE_IDENTIFY_WITH_UID_URL, HexUtils.bin2hex(cplc), HexUtils.bin2hex(value)))
                        .orElse(client.getURI(FidesmoApiClient.DEVICE_IDENTIFY_URL, HexUtils.bin2hex(cplc)));
                JsonNode detect = client.rpc(uri);
                
                if (detect != null) {
                    byte[] fid = Hex.decodeHex(detect.get("cin").asText());
                    int batchId = detect.get("batchId").asInt();
                    return Optional.of(new FidesmoCard(fid, cplc, batchId, false, uid));
                }
            } catch(DecoderException dex) {
                throw new RuntimeException("Failed to decode FID from server: ", dex);
            } catch(IOException ex) {
                return Optional.empty();
            }
        }
            
        return Optional.of(new FidesmoCard(cin, cplc, new BigInteger(1, batch).intValue(), true, uid));
    }

    public static boolean deliverRecipe(BIBO bibo, FidesmoCard card, AuthenticatedFidesmoApiClient
            client, FormHandler formHandler, String appId, ObjectNode recipe) throws IOException {
        return deliverRecipes(bibo, card, client, formHandler, appId, Collections.singletonList(recipe));
    }

    public static boolean deliverRecipes(BIBO bibo, FidesmoCard card, AuthenticatedFidesmoApiClient
            client, FormHandler formHandler, String appId, List<ObjectNode> recipes) throws IOException {

        for (ObjectNode recipe : recipes) {
            final String uuid = UUID.randomUUID().toString();

            URI uri = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, appId, uuid);
            client.put(uri, recipe).close();

            ServiceDeliverySession session = ServiceDeliverySession.getInstance(() -> bibo, card, client, appId, uuid, formHandler);

            // Remove also when ctrl-c is pressed
            session.cleanups.add(() -> {
                try {
                    logger.info("Removing temporary recipe {} ...", uuid);
                    client.delete(uri);
                } catch (IOException e) {
                    logger.warn("Failed to remove temporary recipe {}: {}", uuid, e.getMessage());
                }
            });
            if (!session.call().isSuccess()) {
                return false;
            }
        }
        return true;
    }

    public byte[] getCIN() {
        return cin.clone();
    }

    public int getBatchId() {
        return batchId;
    }

    public boolean isBatched() {
        return batched;
    }

    public byte[] getCPLC() {
        return cplc.clone();
    }

    public Optional<byte[]> getUID() {
        return uid;
    }

    private static boolean valid(byte[] v) {
        try {
            BerTlvParser parser = new BerTlvParser();
            parser.parse(v);
            return true;
        } catch (RuntimeException e) {
            // ignore
        }
        return false;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static ResponseAPDU fixup(ResponseAPDU a) {
        if (!valid(a.getData())) {
            byte[] n = concat(fixup(a.getData()), a.getSWBytes());
            return new ResponseAPDU(n);
        }
        return a;
    }

    // Fix various known issues
    private static byte[] fixup(byte[] v) {
        if (!valid(v)) {
            // trailing 0x00; remove
            if (v.length > 0 && v[v.length - 1] == 0x00 && valid(Arrays.copyOf(v, v.length - 1))) {
                return Arrays.copyOf(v, v.length - 1);
            }
            // incorrect payload and payload length; fix length
            if (v.length == 16 && v[0] == 0x42 && v[1] == 0x03 && v[5] == 0x43 && v[6] == 0x06) {
                byte[] r = v.clone();
                r[6] = 0x05;
                if (valid(r)) return r;
            }
        }
        return v;
    }

    public static List<byte[]> listApps(APDUBIBO channel) {
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

    private static Optional<DeliveryUrl> getBatchingUrl(FidesmoApiClient client, byte[] cplc, Optional<byte[]> uid) throws IOException {
        URI uri = uid
                .map(value -> client.getURI(FidesmoApiClient.DEVICE_IDENTIFY_WITH_UID_URL, HexUtils.bin2hex(cplc), HexUtils.bin2hex(value)))
                .orElse(client.getURI(FidesmoApiClient.DEVICE_IDENTIFY_URL, HexUtils.bin2hex(cplc)));
        JsonNode detect = client.rpc(uri);
        return Optional.ofNullable(detect.get("batchingUrl")).map(n -> DeliveryUrl.parse(n.asText()));
    }

    public void ensureBatched(APDUBIBO bibo, FidesmoApiClient client, Optional<Integer> timeoutMinutes, boolean ignoreImplicitBatching, FormHandler formHandler) throws IOException, URISyntaxException {
        if (!this.isBatched() && !ignoreImplicitBatching) {
            Optional<DeliveryUrl> deliveryOpt = getBatchingUrl(client, this.getCPLC(), this.getUID());
            if (deliveryOpt.isPresent()) {
                DeliveryUrl delivery = deliveryOpt.get();
                System.out.println("Device is not batched. Completing batching.");
                if (delivery.isWebSocket()) {
                    if (!WsClient.execute(new URI(delivery.getService()), bibo, null).join().isSuccess()) {
                        throw new RuntimeException("Failed to batch the device");
                    }
                } else {
                    final ServiceDeliverySession cardSession = ServiceDeliverySession.getInstance(
                            () -> bibo, this, client, delivery.getAppId().get(), delivery.getService(), formHandler
                    );

                    timeoutMinutes.ifPresent(cardSession::setTimeoutMinutes);

                    RunnableFuture<ServiceDeliverySession.DeliveryResult> serviceFuture = new CancellationWaitingFuture<>(cardSession);

                    if (!ServiceDeliverySession.deliverService(serviceFuture).isSuccess()) {
                        throw new RuntimeException("Failed to batch the device");
                    }
                }
            }
        }
    }
}
