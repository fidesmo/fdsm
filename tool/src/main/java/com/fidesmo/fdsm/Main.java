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
import apdu4j.core.CancellationWaitingFuture;
import apdu4j.core.HexBytes;
import apdu4j.core.HexUtils;
import apdu4j.pcsc.CardBIBO;
import apdu4j.pcsc.SCard;
import apdu4j.pcsc.TerminalManager;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fidesmo.fdsm.FidesmoCard.ChipPlatform;
import jnasmartcardio.Smartcardio;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FilenameUtils;
import org.apache.http.client.HttpResponseException;
import pro.javacard.AID;
import pro.javacard.CAPFile;

import javax.crypto.Cipher;
import javax.smartcardio.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class Main extends CommandLineInterface {
    public static void main(String[] argv) {
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv().getOrDefault(ENV_FIDESMO_DEBUG, "error"));

        Optional<Integer> optTimeout;
        boolean ignoreImplicitBatching;

        try {
            // Inspect arguments
            parseArguments(argv);
            // Check environment
            inspectEnvironment(args);

            optTimeout = Optional.ofNullable(args.valueOf(OPT_TIMEOUT));
            ignoreImplicitBatching = args.has(OPT_IGNORE_IMPLICIT_BATCHING);

            // Show useful stuff
            if (verbose && System.getenv(ENV_FIDESMO_DEBUG) == null)
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");

            // Check for version
            if (args.has(OPT_VERSION)) {
                System.out.println("# fdsm " + ClientInfo.getBuildVersion());
                checkVersions();
            }

            // Check if using payment card encryption would fail (Java 1.8 < u151)
            if (Cipher.getMaxAllowedKeyLength("AES") == 128) {
                System.err.println("WARNING: Unlimited crypto policy is NOT installed and using too old Java version!");
                System.err.println("Please update to latest Java!");
            }

            if (args.has(OPT_STORE_APPS)) {
                FidesmoApiClient client = getClient();

                final String states;
                // This is "hidden" from documentation, default to published
                if (args.hasArgument(OPT_STORE_APPS) && "all".equals(args.valueOf(OPT_STORE_APPS))) {
                    states = "?states=published,demo,development";
                } else {
                    states = "?states=published";
                }

                JsonNode apps = client.rpc(client.getURI(FidesmoApiClient.APPS_URL, states));
                if (apps.size() > 0) {
                    List<byte[]> appids = new LinkedList<>();
                    for (JsonNode appid : apps) {
                        appids.add(HexUtils.hex2bin(appid.asText()));
                    }
                    printApps(queryApps(client, appids, verbose), System.out, verbose);
                    success();
                } else {
                    fail("No apps in the appstore!?");
                }
            }

            if (requiresAuthentication()) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();

                // Delete a specific applet or recipe
                if (args.has(OPT_DELETE)) {
                    String id = args.valueOf(OPT_DELETE);
                    Path path = Paths.get(id);
                    // DWIM: recipe
                    final URI toDelete;
                    if (id.toLowerCase().matches("[a-f0-9]{64}")) {
                        toDelete = client.getURI(FidesmoApiClient.CAPFILES_ID_URL, getAppId(), id);
                    } else if (Files.exists(path)) {
                        // DWIM: capfile or recipe .json; This makes it the opposite of the upload operation
                        File f = path.toFile();
                        String extension = FilenameUtils.getExtension(f.getName());
                        if (extension.equalsIgnoreCase("json")) {
                            id = FilenameUtils.getBaseName(f.getName());
                            toDelete = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, getAppId(), id);
                        } else if (extension.equalsIgnoreCase("cap")) {
                            CAPFile tmp = CAPFile.fromBytes(Files.readAllBytes(path));
                            id = HexUtils.bin2hex(tmp.getLoadFileDataHash("SHA-256"));
                            toDelete = client.getURI(FidesmoApiClient.CAPFILES_ID_URL, getAppId(), id);
                        } else {
                            throw new IllegalArgumentException("Only .cap and .json files are supported: " + id);
                        }
                    } else {
                        // Maybe it is a recipe name ?
                        ArrayNode recipes = (ArrayNode) client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, getAppId()));
                        String finalId = id;
                        id = StreamSupport.stream(recipes.spliterator(), false)
                                .map(JsonNode::asText)
                                .filter(r -> r.equals(finalId))
                                .findFirst().orElseThrow(() -> new IllegalArgumentException("Not a recipe nor SHA-256: " + finalId));
                        toDelete = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, getAppId(), id);
                    }

                    try {
                        client.delete(toDelete);
                        System.out.println(id + " deleted.");
                    } catch (HttpResponseException e) {
                        if (e.getStatusCode() == 404) {
                            fail("Not found: " + id);
                        } else
                            throw e;
                    }
                }

                // List applets
                if (args.has(OPT_LIST_APPLETS)) {
                    JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.CAPFILES_URL, getAppId()));
                    // Show applets, grouped by AID-s.
                    if (applets.size() > 0) {
                        Map<String, Map<String, String>> r = new HashMap<>();
                        for (JsonNode e : applets) {
                            String aid = e.get("elfAid").asText().toUpperCase();
                            List<String> variant = new ArrayList<>();
                            if (e.has("javaCardVersion")) {
                                variant.add("JC/" + e.get("javaCardVersion").asText());
                            }
                            if (e.get("metadata").has("gp-version")) {
                                variant.add("GP/" + e.get("metadata").get("gp-version").asText());
                            }
                            if (e.get("metadata").has("otv-version")) {
                                variant.add(e.get("metadata").get("otv-version").asText());
                            }
                            Map<String, String> ids = r.getOrDefault(aid, new HashMap<>());
                            ids.put(e.get("id").asText(), String.join(", ", variant));
                            r.put(aid, ids);
                        }
                        for (Map.Entry<String, Map<String, String>> e : r.entrySet()) {
                            System.out.println("AID: " + e.getKey());
                            for (Map.Entry<String, String> id : e.getValue().entrySet()) {
                                System.out.println("     " + id.getKey() + " " + id.getValue());
                            }
                        }
                    } else {
                        success("No applets");
                    }
                }

                // List applets
                if (args.has(OPT_LIST_RECIPES)) {
                    JsonNode recipes = client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, getAppId()));
                    if (recipes.size() > 0) {
                        System.out.println(FidesmoApiClient.mapper.writer(FidesmoApiClient.printer).writeValueAsString(recipes));
                    } else {
                        success("No recipes");
                    }
                }

                // Cleanup recipes
                if (args.has(OPT_CLEANUP)) {
                    JsonNode recipes = client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, getAppId()));
                    int removed = 0;
                    if (recipes.size() > 0) {
                        for (JsonNode r : recipes) {
                            try {
                                UUID uuid = UUID.fromString(r.asText());
                                URI recipe = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, getAppId(), uuid.toString());
                                client.delete(recipe);
                                removed = removed + 1;
                            } catch (IllegalArgumentException e) {
                                // Ignore recipes not matching uuid
                            }
                        }
                        success("Cleaned up " + removed + " recipes");
                    } else {
                        success("No recipes");
                    }
                }

                if (args.has(OPT_UPLOAD)) {
                    File f = args.valueOf(OPT_UPLOAD);
                    if (FilenameUtils.getExtension(f.getName()).equalsIgnoreCase("json")) {
                        String name = FilenameUtils.getBaseName(f.getName());
                        ObjectNode recipe = RecipeGenerator.mapper.readTree(Files.readAllBytes(f.toPath())).deepCopy();
                        URI uri = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, getAppId(), name);
                        client.put(uri, recipe).close();
                    } else {
                        CAPFile cap = CAPFile.fromStream(new FileInputStream(args.valueOf(OPT_UPLOAD)));
                        client.upload(getAppId(), cap);
                    }
                } else if (args.has(OPT_FLUSH_APPLETS)) {
                    JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.CAPFILES_URL, getAppId()));
                    for (JsonNode e : applets) {
                        client.delete(client.getURI(FidesmoApiClient.CAPFILES_ID_URL, getAppId(), e.get("id").asText()));
                    }
                }
            }

            // Make sure the client is a recent one
            if (requiresCard() || requiresAuthentication())
                checkVersions();

            // Following requires card access
            if (requiresCard()) {
                TerminalFactory factory = TerminalManager.getTerminalFactory();
                CardTerminals terminals = factory.terminals();

                FidesmoApiClient client = getClient();
                // Locate a Fidesmo card, unless asked for a specific terminal
                CardTerminal terminal = null;
                if (args.has(OPT_READER)) {
                    String reader = args.valueOf(OPT_READER);
                    for (CardTerminal t : terminals.list()) {
                        if (t.getName().toLowerCase().contains(reader.toLowerCase())) {
                            terminal = t;
                        }
                    }
                    if (terminal == null) {
                        System.err.println("Available readers:");
                        terminals.list().forEach(r -> System.err.println("- " + r.getName()));
                        throw new IllegalArgumentException(String.format("Reader \"%s\" not found", reader));
                    }
                } else {
                    List<CardTerminal> candidates = TerminalManager.byAID(FidesmoCard.FIDESMO_CARD_AIDS);
                    if (candidates.size() != 1) {
                        System.err.println("Available readers:");
                        terminals.list().forEach(r -> System.err.println("- " + r.getName()));
                        throw new CardException("Could not find a single Fidesmo card; must use --reader.");
                    }
                    terminal = candidates.get(0);
                }

                if (apduTraceStream != null) {
                    terminal = LoggingCardTerminal.getInstance(terminal, apduTraceStream);
                }
                Card card = terminal.connect("*");
                Optional<byte[]> uid = getUID(card.getBasicChannel());
                APDUBIBO bibo = new APDUBIBO(CardBIBO.wrap(card));
                Optional<FidesmoCard> fidesmoMetadata = (args.has(OPT_OFFLINE) || args.has(OPT_IGNORE_IMPLICIT_BATCHING)) ? FidesmoCard.detectOffline(bibo) : FidesmoCard.detectOnline(bibo, client);

                System.out.println("Using card in " + terminal.getName());

                // Can be used always
                if (args.has(OPT_CARD_INFO)) {
                    if (fidesmoMetadata.isPresent()) {
                        FidesmoCard fidesmoCard = fidesmoMetadata.get();
                        System.out.format("CIN: %s BATCH: %d UID: %s%n", printableCIN(fidesmoCard.getCIN()), fidesmoCard.getBatchId(), uid.map(HexUtils::bin2hex).orElse("N/A"));
                        if (args.has(OPT_OFFLINE)) {
                            System.out.format("OS type: %s%n", FidesmoCard.detectPlatform(fidesmoCard.getCPLC()).map(ChipPlatform::toString).orElse("unknown"));
                        } else {
                            JsonNode device = client.rpc(client.getURI(FidesmoApiClient.DEVICES_URL, HexUtils.bin2hex(fidesmoCard.getCIN()), fidesmoCard.getBatchId()));
                            byte[] iin = HexUtils.decodeHexString_imp(device.get("iin").asText());
                            // Read capabilities
                            JsonNode capabilities = device.get("description").get("capabilities");
                            int platformVersion = capabilities.get("platformVersion").asInt();
                            String platform = Optional.ofNullable(capabilities.get("osTypeVersionName")).map(JsonNode::asText).orElse("unknown");
                            if (verbose)
                                System.out.format("IIN: %s%n", HexUtils.bin2hex(iin));
                            System.out.format("OS type: %s (platform v%d)%n", platform, platformVersion);

                            fidesmoMetadata.get().ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                        }
                    } else {
                        System.out.println("UID: " + uid.map(HexUtils::bin2hex).orElse("N/A"));
                        System.out.println("Not a Fidesmo device");
                    }
                }

                if (args.has(OPT_RUN)) {
                    if (fidesmoMetadata.isPresent()) {
                        fidesmoMetadata.get().ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                    }

                    DeliveryUrl delivery = DeliveryUrl.parse(args.valueOf(OPT_RUN));

                    if (delivery.isWebSocket()) {
                        fidesmoMetadata.ifPresent(fidesmoCard -> fidesmoCard.selectEmpty(bibo));
                        boolean success = WsClient.execute(new URI(delivery.getService()), bibo, auth, clientInfo()).join().isSuccess();
                        if (!success) {
                            fail("Fail to run a script");
                        } else {
                            success();
                        }
                    } else {
                        FormHandler formHandler = getCommandLineFormHandler();
                        FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);

                        if (delivery.getAppId().isEmpty()) {
                            throw new IllegalArgumentException("Need Application ID: " + args.valueOf(OPT_RUN));
                        }

                        final ServiceDeliverySession cardSession = ServiceDeliverySession.getInstance(
                                () -> bibo, fidesmoCard, client, delivery.getAppId().get(), delivery.getService(), formHandler
                        );

                        if (args.has(OPT_TIMEOUT))
                            cardSession.setTimeoutMinutes(args.valueOf(OPT_TIMEOUT));

                        RunnableFuture<ServiceDeliverySession.DeliveryResult> serviceFuture = new CancellationWaitingFuture<>(cardSession);
                        ServiceDeliverySession.DeliveryResult result = ServiceDeliverySession.deliverService(serviceFuture);

                        if (!result.isSuccess()) {
                            fail("Failed to run service");
                        } else {
                            success(); // Explicitly quit to signal successful service. Which implies only one service per invocation
                        }
                    }
                    // --run always exists
                }

                if (args.has(OPT_CARD_APPS)) {
                    FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);
                    fidesmoCard.ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                    List<byte[]> apps = FidesmoCard.listApps(bibo);
                    if (apps.size() > 0) {
                        printApps(queryApps(client, apps, verbose), System.out, verbose);
                    } else {
                        success("No applications");
                    }
                } else if (requiresAuthentication()) {
                    AuthenticatedFidesmoApiClient authenticatedClient = getAuthenticatedClient();
                    FormHandler formHandler = getCommandLineFormHandler();

                    if (args.has(OPT_INSTALL)) {
                        FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);
                        fidesmoCard.ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());

                        CAPFile cap = CAPFile.fromStream(new FileInputStream(args.valueOf(OPT_INSTALL)));
                        // Which applet
                        final AID applet;
                        if (cap.getAppletAIDs().size() > 1) {
                            if (!args.has(OPT_APPLET))
                                throw new IllegalArgumentException("Must specify --applet with multiple applets in CAP!");
                            applet = AID.fromString(args.valueOf(OPT_APPLET));
                        } else {
                            applet = cap.getAppletAIDs().get(0);
                        }

                        // What instance
                        AID instance = applet;
                        if (args.has(OPT_CREATE))
                            instance = AID.fromString(args.valueOf(OPT_CREATE));
                        byte[] params = null;
                        if (args.has(OPT_PARAMS)) {
                            params = args.valueOf(OPT_PARAMS).value();
                            // Restriction
                            if (params.length > 0 && params[0] == (byte) 0xC9) {
                                throw new IllegalArgumentException("Installation parameters must be without C9 tag");
                            }
                        }
                        byte[] lfdbh = cap.getLoadFileDataHash("SHA-256");
                        JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.CAPFILES_URL, getAppId()));
                        boolean present = false;
                        for (JsonNode e : applets) {
                            if (Arrays.equals(Hex.decodeHex(e.get("id").asText()), lfdbh)) {
                                present = true;
                            }
                        }
                        // Upload
                        if (!present) {
                            authenticatedClient.upload(getAppId(), cap);
                        }
                        ObjectNode recipe = RecipeGenerator.makeInstallRecipe(lfdbh, applet, instance, params);
                        FidesmoCard.deliverRecipe(bibo, fidesmoCard, authenticatedClient, formHandler, getAppId(), recipe);
                    } else if (args.has(OPT_UNINSTALL)) {
                        FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);
                        fidesmoCard.ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                        String s = args.valueOf(OPT_UNINSTALL);
                        Path p = Paths.get(s);

                        AID aid;
                        if (!Files.exists(p)) {
                            try {
                                aid = AID.fromString(s);
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("Not a file or AID: " + s);
                            }
                        } else {
                            aid = CAPFile.fromBytes(Files.readAllBytes(p)).getPackageAID();
                        }
                        ObjectNode recipe = RecipeGenerator.makeDeleteRecipe(aid);
                        FidesmoCard.deliverRecipe(bibo, fidesmoCard, authenticatedClient, formHandler, getAppId(), recipe);
                    }

                    // Can be chained
                    if (args.has(OPT_STORE_DATA)) {
                        FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);
                        fidesmoCard.ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                        List<byte[]> blobs = args.valuesOf(OPT_STORE_DATA).stream().map(HexBytes::value).collect(Collectors.toList());
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET));
                        ObjectNode recipe = RecipeGenerator.makeStoreDataRecipe(applet, blobs);
                        FidesmoCard.deliverRecipe(bibo, fidesmoCard, authenticatedClient, formHandler, getAppId(), recipe);
                    }

                    // Can be chained
                    if (args.has(OPT_SECURE_APDU)) {
                        FidesmoCard fidesmoCard = requireDevice(fidesmoMetadata);
                        fidesmoCard.ensureBatched(bibo, client, optTimeout, ignoreImplicitBatching, getCommandLineFormHandler());
                        List<byte[]> apdus = args.valuesOf(OPT_SECURE_APDU).stream().map(HexBytes::value).collect(Collectors.toList());
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET));
                        ObjectNode recipe = RecipeGenerator.makeSecureTransceiveRecipe(applet, apdus);
                        FidesmoCard.deliverRecipe(bibo, fidesmoCard, authenticatedClient, formHandler, getAppId(), recipe);
                    }
                }
            }
        } catch (CancellationException e) {
            fail("Cancelled: " + e.getMessage());
        } catch (FDSMException e) {
            fail("FDSM: " + e.getMessage());
        } catch (HttpResponseException e) {
            fail("API error: " + e.getMessage());
        } catch (NoSuchFileException e) {
            fail("No such file: " + e.getMessage());
        } catch (IOException e) {
            fail("I/O error: " + e.getMessage());
        } catch (CardException e) {
            String s = SCard.getExceptionMessage(e);
            fail("Card communication error: " + s);
        } catch (NoSuchAlgorithmException | Smartcardio.EstablishContextException e) {
            String s = SCard.getExceptionMessage(e);
            fail("No smart card readers: " + s);
        } catch (IllegalArgumentException e) {
            if (verbose)
                e.printStackTrace();
            fail("Illegal argument: " + e.getMessage());
        } catch (IllegalStateException e) {
            fail("Illegal state: " + e.getMessage());
        } catch (Exception e) {
            if (verbose)
                e.printStackTrace();
            fail("Unexpected error: " + e.getMessage());
        }
    }

    private static String printableCIN(byte[] cin) {
        return String.format("%s-%s", HexUtils.bin2hex(Arrays.copyOfRange(cin, 0, 3)), HexUtils.bin2hex(Arrays.copyOfRange(cin, 3, 7)));
    }

    private static List<FidesmoApp> queryApps(FidesmoApiClient client, List<byte[]> apps, boolean verbose) throws IOException {
        List<FidesmoApp> result = new ArrayList<>();
        // Construct list in one go
        for (byte[] app : apps) {
            JsonNode appDesc = client.rpc(client.getURI(FidesmoApiClient.APP_INFO_URL, HexUtils.bin2hex(app)));
            // Multilanguague
            String appID = HexUtils.bin2hex(app);
            String appName = FidesmoApiClient.lamei18n(appDesc.get("name"));
            String appVendor = FidesmoApiClient.lamei18n(appDesc.get("organization").get("name"));
            FidesmoApp fidesmoApp = new FidesmoApp(app, appName, appVendor);
            // Fetch services
            JsonNode services = client.rpc(client.getURI(FidesmoApiClient.APP_SERVICES_URL, appID));
            if (!services.isEmpty()) {
                for (JsonNode s : services) {
                    if (verbose) {
                        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_URL, appID, s.asText()));
                        JsonNode d = service.get("description").get("description");
                        fidesmoApp.addService(new FidesmoService(s.asText(), FidesmoApiClient.lamei18n(d)));
                    } else {
                        fidesmoApp.addService(new FidesmoService(s.asText(), null));
                    }
                }
            }
            result.add(fidesmoApp);
        }
        return result;
    }

    private static void printApps(List<FidesmoApp> apps, PrintStream out, boolean verbose) {
        // Display list in one go.
        out.println("#  appId - name and vendor");
        for (FidesmoApp app : apps) {
            out.println(HexUtils.bin2hex(app.id).toLowerCase() + " - " + app.name + " (by " + app.vendor + ")");
            if (!app.services.isEmpty()) {
                if (verbose) {
                    for (FidesmoService service : app.services) {
                        out.println("           " + service.name + " - " + service.description);
                    }
                } else {
                    out.println("           Services: " + app.services.stream().map(e -> e.name).collect(Collectors.joining(", ")));
                }
            }
        }
    }

    private static FidesmoApiClient getClient() {
        return new FidesmoApiClient(apiurl, auth, apiTraceStream, clientInfo());
    }

    private static FidesmoCard requireDevice(Optional<FidesmoCard> device) {
        return device.orElseThrow(() -> new IllegalStateException("Need a Fidesmo device to continue!"));
    }

    static void checkVersions() {
        if (offline) {
            if (verbose)
                System.out.println("# Omitting online version check");
            return;
        }
        FidesmoApiClient client = new FidesmoApiClient(apiurl, null, apiTraceStream, clientInfo());
        try {
            JsonNode v = client.rpc(new URI("https://api.fidesmo.com/fdsm-version"));
            // Convert both to numbers
            String latestTag = v.get("tag_name").asText("v00.00.00");
            int latest = Integer.parseInt((latestTag.startsWith("v") ? latestTag.substring(1, 9) : latestTag.substring(0, 8)).replace(".", ""));
            String currentTag = ClientInfo.getBuildVersion();
            int current = Integer.parseInt((currentTag.startsWith("v") ? currentTag.substring(1, 9) : currentTag.substring(0, 8)).replace(".", ""));
            if (current < latest) {
                System.out.println("Please download updated version from\n\n" + v.get("html_url").asText());
            }
        } catch (Exception e) {
            // Do nothing.
            if (verbose)
                System.err.println("Warning: could not check for updates: " + e.getMessage());
        }
    }

    static Optional<byte[]> getUID(CardChannel channel) throws CardException {
        // See if we get the UID from ACS(-compatible) and other PCSC v2.0 readers
        // NOTE: to make sure we get a sane response if the reader does not support
        // the command, it MUST be called as first command after connecting

        CommandAPDU getUID = new CommandAPDU(HexUtils.hex2bin("FFCA000000"));
        ResponseAPDU response = channel.transmit(getUID);
        // Sensibility check: UID size
        if (response.getSW() == 0x9000 && (response.getData().length == 7 || response.getData().length == 4)) {
            return Optional.of(response.getData());
        } else {
            return Optional.empty();
        }
    }

    private static FormHandler getCommandLineFormHandler() {
        Map<String, String> cliFields = new HashMap<>();

        if (args.has(OPT_FIELDS)) {
            String[] fieldPairs = args.valueOf(OPT_FIELDS).split(",");

            for (String pair : fieldPairs) {
                if (!pair.isEmpty()) {
                    String[] fieldAndValue = pair.split("=", 2);

                    if (fieldAndValue.length != 2) {
                        throw new IllegalArgumentException("Wrong format for fields pair: " + pair + ". Required: fieldId=fieldValue,");
                    }

                    cliFields.put(fieldAndValue[0], fieldAndValue[1]);
                }
            }
        }

        return new CommandLineFormHandler(cliFields);
    }

    private static AuthenticatedFidesmoApiClient getAuthenticatedClient() {
        if (auth == null) {
            throw new IllegalArgumentException("Provide authentication either via --auth or $FIDESMO_AUTH");
        }
        return AuthenticatedFidesmoApiClient.getInstance(apiurl, auth, apiTraceStream, clientInfo());
    }

    private static String getAppId() {
        // Value is required, thus we can safely use valueOf
        if (args.has(OPT_APP_ID)) {
            return args.valueOf(OPT_APP_ID);
        } else if (System.getenv(ENV_FIDESMO_APPID) != null) {
            return System.getenv(ENV_FIDESMO_APPID);
        } else {
            throw new IllegalArgumentException("Operation requires --app-id or $FIDESMO_APPID");
        }
    }

    private static class FidesmoService {
        String name;
        String description;

        public FidesmoService(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    private static class FidesmoApp {
        byte[] id;
        String name;
        String vendor;
        List<FidesmoService> services;

        public FidesmoApp(byte[] id, String name, String vendor) {
            this.id = id;
            this.name = name;
            this.vendor = vendor;
            this.services = new ArrayList<>();
        }

        void addService(FidesmoService service) {
            services.add(service);
        }
    }
}
