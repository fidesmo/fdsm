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
import apdu4j.LoggingCardTerminal;
import apdu4j.TerminalManager;
import com.fasterxml.jackson.databind.JsonNode;
import jnasmartcardio.Smartcardio;
import org.apache.http.client.HttpResponseException;
import pro.javacard.AID;
import pro.javacard.CAPFile;

import javax.crypto.Cipher;
import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.Collectors;

public class Main extends CommandLineInterface {
    static final String FDSM_SP = "8e5cdaae";
    private static FidesmoCard fidesmoCard;

    public static void main(String[] argv) {
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        try {
            // Inspect arguments
            parseArguments(argv);
            // Show useful stuff
            if (args.has(OPT_VERBOSE))
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");

            // Check environment
            inspectEnvironment(args);

            // Check for version
            if (args.has(OPT_VERSION)) {
                System.out.println("# fdsm v" + FidesmoApiClient.getVersion());
                checkVersions(getClient());
            }

            // Check if using payment card encryption would fail (Java 1.8 < u151)
            if (Cipher.getMaxAllowedKeyLength("AES") == 128) {
                System.err.println("WARNING: Unlimited crypto policy is NOT installed and using too old Java version!");
                System.err.println("Please update to latest Java!");
            }

            if (args.has(OPT_STORE_APPS)) {
                FidesmoApiClient client = getClient();

                JsonNode apps = client.rpc(client.getURI(FidesmoApiClient.APPS_URL));
                if (apps.size() > 0) {
                    List<byte[]> appids = new LinkedList<>();
                    for (JsonNode appid : apps) {
                        appids.add(HexUtils.hex2bin(appid.asText()));
                    }
                    printApps(queryApps(client, appids, verbose), System.out, verbose);
                    success();
                } else {
                    success("No apps in the appstore!");
                }
            }

            if (requiresAuthentication()) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();

                // Delete a specific applet
                if (args.has(OPT_DELETE_APPLET)) {
                    String id = args.valueOf(OPT_DELETE_APPLET).toString();
                    // DWIM: take ID or CAP file as argument
                    if (!id.toLowerCase().matches("[a-f0-9]{64}")) {
                        Path candidate = Paths.get(id);
                        if (Files.exists(candidate)) {
                            CAPFile tmp = CAPFile.fromBytes(Files.readAllBytes(candidate));
                            id = HexUtils.bin2hex(tmp.getLoadFileDataHash("SHA-256", false));
                        } else {
                            fail("Not a SHA-256: " + id);
                        }
                    }
                    try {
                        // XXX: case-sensitive
                        client.delete(client.getURI(FidesmoApiClient.ELF_ID_URL, id.toLowerCase()));
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
                    JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.ELF_URL));
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
                    JsonNode recipes = client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, appId));
                    if (recipes.size() > 0) {
                        System.out.println(FidesmoApiClient.mapper.writer(FidesmoApiClient.printer).writeValueAsString(recipes));
                    } else {
                        success("No recipes");
                    }
                }

                // Cleanup recipes
                if (args.has(OPT_CLEANUP)) {
                    JsonNode recipes = client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, appId));
                    int removed = 0;
                    if (recipes.size() > 0) {
                        for (JsonNode r : recipes) {
                            try {
                                UUID uuid = UUID.fromString(r.asText());
                                URI recipe = client.getURI(FidesmoApiClient.SERVICE_RECIPE_URL, appId, uuid.toString());
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

                if (args.has(OPT_UPLOAD) && args.valueOf(OPT_UPLOAD) != null) {
                    CAPFile cap = CAPFile.fromStream(new FileInputStream((File) args.valueOf(OPT_UPLOAD)));
                    client.upload(cap);
                } else if (args.has(OPT_FLUSH_APPLETS)) {
                    JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.ELF_URL));
                    for (JsonNode e : applets) {
                        client.delete(client.getURI(FidesmoApiClient.ELF_ID_URL, e.get("id").asText()));
                    }
                }
            }

            // Following requires card access
            if (requiresCard()) {
                FidesmoApiClient client = getClient();
                checkVersions(client);
                // Locate a Fidesmo card, unless asked for a specific terminal
                CardTerminal terminal = null;
                if (args.has(OPT_READER)) {
                    String reader = args.valueOf(OPT_READER).toString();
                    for (CardTerminal t : TerminalManager.getTerminalFactory(null).terminals().list()) {
                        if (t.getName().toLowerCase().contains(reader.toLowerCase())) {
                            terminal = t;
                        }
                    }
                    if (terminal == null) {
                        fail(String.format("Reader \"%s\" not found", reader));
                    }
                } else {
                    terminal = TerminalManager.getByAID(FidesmoCard.FIDESMO_CARD_AIDS);
                }

                if (apduTrace) {
                    terminal = LoggingCardTerminal.getInstance(terminal);
                }
                Card card = terminal.connect("*");
                // Allows to run with any card
                if (args.has(OPT_QA)) {
                    String number = Integer.toString(new Random().nextInt(900000) + 100000).substring(0, 6);
                    if (args.valueOf(OPT_QA) != null) {
                        number = args.valueOf(OPT_QA).toString();
                    } else {
                        System.out.printf("Your QA number is %s-%s%n", number.substring(0, 3), number.substring(3, 6));
                    }
                    fidesmoCard = FidesmoCard.fakeInstance(card.getBasicChannel());

                    FormHandler formHandler = getCommandLineFormHandler();

                    ServiceDeliverySession cardSession = ServiceDeliverySession.getInstance(fidesmoCard, client, formHandler);
                    if (args.has(OPT_TIMEOUT))
                        cardSession.setTimeout((Integer) args.valueOf(OPT_TIMEOUT));
                    if (!cardSession.deliver(FDSM_SP, number).isSuccess()) {
                        fail("Failed to run service");
                    } else {
                        success();
                    }
                }
                fidesmoCard = FidesmoCard.getInstance(card.getBasicChannel());
                System.out.println("Using card in " + terminal.getName());

                // Can be used always
                if (args.has(OPT_CARD_INFO)) {
                    System.out.format("CIN: %s BATCH: %s UID: %s%n",
                            printableCIN(fidesmoCard.getCIN()),
                            HexUtils.bin2hex(fidesmoCard.getBatchId()),
                            fidesmoCard.getUID().map(i -> HexUtils.bin2hex(i)).orElse("N/A"));
                    if (!args.has(OPT_OFFLINE)) {
                        boolean showIIN = isDeveloperMode() || args.has(OPT_VERBOSE);
                        JsonNode device = client.rpc(client.getURI(FidesmoApiClient.DEVICES_URL, HexUtils.bin2hex(fidesmoCard.getCIN()), new BigInteger(1, fidesmoCard.getBatchId()).toString()));
                        byte[] iin = HexUtils.decodeHexString_imp(device.get("iin").asText());
                        // Read capabilities
                        JsonNode capabilities = device.get("description").get("capabilities");
                        int platformVersion = capabilities.get("platformVersion").asInt();
                        int platformType = capabilities.get("osTypeVersion").asInt();

                        System.out.format("IIN: %s %n",
                                showIIN ? String.format(" IIN: %s", HexUtils.bin2hex(iin)) : "");
                        // For platforms that are not yet supported by fdsm
                        String platform = FidesmoCard.ChipPlatform.valueOf(platformType).toString();
                        System.out.format("OS type: %s (platform v%d)%n", platform, platformVersion);
                    }
                }

                if (args.has(OPT_CARD_APPS)) {
                    List<byte[]> apps = fidesmoCard.listApps();
                    if (apps.size() > 0) {
                        printApps(queryApps(client, apps, verbose), System.out, verbose);
                    } else {
                        success("No applications");
                    }
                } else if (args.has(OPT_DELIVER) || args.has(OPT_RUN)) {
                    String service;
                    if (args.has(OPT_DELIVER)) {
                        System.err.println("--deliver is deprecated for --run. Please update your scripts");
                        service = args.valueOf(OPT_DELIVER).toString();
                    } else {
                        service = args.valueOf(OPT_RUN).toString();
                    }
                    FormHandler formHandler = getCommandLineFormHandler();

                    if (service.contains("/")) {
                        String[] bits = service.split("/");
                        if (bits.length == 2 && bits[0].length() == 8) {
                            service = bits[1];
                            appId = bits[0];
                        } else {
                            fail("Invalid argument: " + service);
                        }
                    }
                    if (appId == null) {
                        fail("Need Application ID");
                    }
                    ServiceDeliverySession cardSession = ServiceDeliverySession.getInstance(fidesmoCard, client, formHandler);
                    if (args.has(OPT_TIMEOUT))
                        cardSession.setTimeout((Integer) args.valueOf(OPT_TIMEOUT));
                    if (!cardSession.deliver(appId, service).isSuccess()) {
                        fail("Failed to run service");
                    } else {
                        success(); // Explicitly quit to signal successful service. Which implies only one service per invocation
                    }
                } else if (requiresAuthentication()) { // XXX
                    AuthenticatedFidesmoApiClient authenticatedClient = getAuthenticatedClient();
                    checkVersions(authenticatedClient); // Always check versions
                    FormHandler formHandler = getCommandLineFormHandler();

                    if (args.has(OPT_INSTALL)) {
                        CAPFile cap = CAPFile.fromStream(new FileInputStream((File) args.valueOf(OPT_INSTALL)));
                        // Which applet
                        final AID applet;
                        if (cap.getAppletAIDs().size() > 1) {
                            if (!args.has(OPT_APPLET))
                                fail("Must specify --applet with multiple applets in CAP!");
                            applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        } else {
                            applet = cap.getAppletAIDs().get(0);
                        }

                        // What instance
                        AID instance = applet;
                        if (args.has(OPT_CREATE))
                            instance = AID.fromString(args.valueOf(OPT_CREATE).toString());
                        byte[] params = null;
                        if (args.has(OPT_PARAMS)) {
                            params = HexUtils.stringToBin(args.valueOf(OPT_PARAMS).toString());
                            // Restriction
                            if (params.length > 0 && params[0] == (byte) 0xC9) {
                                fail("Installation parameters must be without C9 tag");
                            }
                        }
                        String recipe = RecipeGenerator.makeInstallRecipe(cap.getPackageAID(), applet, instance, params);
                        if (args.has(OPT_UPLOAD)) {
                            authenticatedClient.upload(cap);
                        }
                        fidesmoCard.deliverRecipe(authenticatedClient, formHandler, recipe);
                    } else if (args.has(OPT_UNINSTALL)) {
                        String s = (String) args.valueOf(OPT_UNINSTALL);
                        Path p = Paths.get(s);

                        AID aid = null;
                        if (!Files.exists(p)) {
                            try {
                                aid = AID.fromString(s);
                            } catch (IllegalArgumentException e) {
                                fail("Not a file or AID: " + s);
                            }
                        } else {
                            aid = CAPFile.fromBytes(Files.readAllBytes(p)).getPackageAID();
                        }
                        String recipe = RecipeGenerator.makeDeleteRecipe(aid);
                        fidesmoCard.deliverRecipe(authenticatedClient, formHandler, recipe);
                    }

                    // Can be chained
                    if (args.has(OPT_STORE_DATA)) {
                        List<byte[]> blobs = args.valuesOf(OPT_STORE_DATA).stream().map(s -> HexUtils.stringToBin((String) s)).collect(Collectors.toList());
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        String recipe = RecipeGenerator.makeStoreDataRecipe(applet, blobs);
                        fidesmoCard.deliverRecipe(authenticatedClient, formHandler, recipe);
                    }

                    // Can be chained
                    if (args.has(OPT_SECURE_APDU)) {
                        List<byte[]> apdus = args.valuesOf(OPT_SECURE_APDU).stream().map(s -> HexUtils.stringToBin((String) s)).collect(Collectors.toList());
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        String recipe = RecipeGenerator.makeSecureTransceiveRecipe(applet, apdus);
                        fidesmoCard.deliverRecipe(authenticatedClient, formHandler, recipe);
                    }
                }
            }
        } catch (UserCancelledException e) {
            fail("Cancelled: " + e.getMessage());
        } catch (NotSupportedException e) {
            fail("Not supported: " + e.getMessage());
        } catch (HttpResponseException e) {
            fail("API error: " + e.getMessage());
        } catch (NoSuchFileException e) {
            fail("No such file: " + e.getMessage());
        } catch (IOException e) {
            fail("I/O error: " + e.getMessage());
        } catch (CardException e) {
            fail("Card communication error: " + e.getMessage());
        } catch (GeneralSecurityException | Smartcardio.EstablishContextException e) {
            String s = TerminalManager.getExceptionMessage(e);
            fail("No smart card readers: " + (s == null ? e.getMessage() : s));
        } catch (IllegalArgumentException e) {
            fail("Illegal argument: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unknown error: " + e.getMessage());
        }
    }

    static boolean isDeveloperMode() {
        return System.getenv().getOrDefault("FIDESMO_DEVELOPER", "false").equalsIgnoreCase("true");
    }

    private static String printableCIN(byte[] cin) {
        return String.format("%s-%s", HexUtils.bin2hex(Arrays.copyOfRange(cin, 0, 3)), HexUtils.bin2hex(Arrays.copyOfRange(cin, 3, 7)));
    }

    private static List<FidesmoApp> queryApps(FidesmoApiClient client, List<byte[]> apps, boolean verbose) throws IOException {
        List<FidesmoApp> result = new ArrayList<>();
        // Construct list in one go
        for (byte[] app : apps) {
            JsonNode appdesc = client.rpc(client.getURI(FidesmoApiClient.APP_INFO_URL, HexUtils.bin2hex(app)));
            // Multilanguague
            String appID = HexUtils.bin2hex(app);
            String appName = FidesmoApiClient.lamei18n(appdesc.get("name"));
            String appVendor = FidesmoApiClient.lamei18n(appdesc.get("organization").get("name"));
            FidesmoApp fidesmoApp = new FidesmoApp(app, appName, appVendor);
            // Fetch services
            JsonNode services = client.rpc(client.getURI(FidesmoApiClient.APP_SERVICES_URL, appID));
            if (services.size() > 0) {
                for (JsonNode s : services) {
                    if (verbose) {
                        JsonNode service = client.rpc(client.getURI(FidesmoApiClient.SERVICE_URL, appID, s.asText()));
                        JsonNode d = service.get("description").get("description");
                        fidesmoApp.addService(new FidesmoService(s.asText(), FidesmoApiClient.lamei18n(service.get("description").get("description"))));
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
            if (app.services.size() > 0) {
                if (verbose) {
                    for (FidesmoService service : app.services) {
                        out.println("           " + service.name + " - " + service.description);
                    }
                } else {
                    out.println("           Services: " + String.join(", ", app.services.stream().map(e -> e.name).collect(Collectors.toList())));
                }
            }
        }
    }

    private static FidesmoApiClient getClient() {
        return new FidesmoApiClient(apiTrace ? System.out : null);
    }

    static void checkVersions(FidesmoApiClient client) {
        try {
            JsonNode v = client.rpc(new URI("https://api.fidesmo.com/fdsm-version"));
            // Convert both to numbers
            int latest = Integer.parseInt(v.get("tag_name").asText("00.00.00").substring(0, 8).replace(".", ""));
            int current = Integer.parseInt(FidesmoApiClient.getVersion().substring(0, 8).replace(".", ""));
            if (current < latest) {
                System.out.println("Please download updated version from\n\n" + v.get("html_url").asText());
            }
        } catch (URISyntaxException | IOException e) {
            // Do nothing.
            System.err.println("Warning: could not check for updates!");
        }
    }

    private static FormHandler getCommandLineFormHandler() {
        Map<String, String> cliFields = new HashMap<>();

        if (args.has(OPT_FIELDS)) {
            String[] fieldPairs = args.valueOf(OPT_FIELDS).toString().split(",");

            for (String pair : fieldPairs) {
                if (!pair.isEmpty()) {
                    String[] fieldAndValue = pair.split("=");

                    if (fieldAndValue.length != 2) {
                        fail("Wrong format for fields pair: " + pair + ". Required: fieldId=fieldValue,");
                    }

                    cliFields.put(fieldAndValue[0], fieldAndValue[1]);
                }
            }
        }

        return new CommandLineFormHandler(cliFields);
    }

    private static AuthenticatedFidesmoApiClient getAuthenticatedClient() {
        if (appId == null || appKey == null) {
            fail("Provide appId and appKey, either via --app-id and --app-key or $FIDESMO_APP_ID and $FIDESMO_APP_KEY");
        }
        return AuthenticatedFidesmoApiClient.getInstance(appId, appKey, apiTrace ? System.out : null);
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
