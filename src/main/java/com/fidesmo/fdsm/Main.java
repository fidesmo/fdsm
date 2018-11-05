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

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.Collectors;

public class Main extends CommandLineInterface {
    private static FidesmoCard fidesmoCard;

    public static void main(String[] argv) {
        try {
            // Inspect arguments
            parseArguments(argv);

            if (args.has(OPT_STORE_APPS)) {
                FidesmoApiClient client = getClient();

                JsonNode apps = client.rpc(client.getURI(FidesmoApiClient.APPS_URL));
                if (apps.size() > 0) {
                    List<byte[]> appids = new LinkedList<>();
                    for (JsonNode appid : apps) {
                        appids.add(HexUtils.hex2bin(appid.asText()));
                    }
                    printApps(queryApps(client, appids, verbose), System.out, verbose);
                } else {
                    System.out.println("No apps in the appstore!");
                }
            }

            if (args.has(OPT_UPLOAD) || args.has(OPT_DELETE_APPLET) || args.has(OPT_FLUSH_APPLETS) || args.has(OPT_CLEANUP) || args.has(OPT_LIST_APPLETS) || args.has(OPT_LIST_RECIPES)) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();

                // Delete a specific applet
                if (args.has(OPT_DELETE_APPLET)) {
                    String id = args.valueOf(OPT_DELETE_APPLET).toString();
                    try {
                        UUID uuid = UUID.fromString(id);
                        client.delete(client.getURI(FidesmoApiClient.ELF_ID_URL, uuid.toString()));
                        System.out.println(id + " deleted.");
                    } catch (IllegalArgumentException e) {
                        fail("Not a valid UUID: " + id);
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
                            if (e.has("globalPlatformVersion")) {
                                variant.add("GP/" + e.get("globalPlatformVersion").asText());
                            }
                            if (e.has("javaCardVersion")) {
                                variant.add("JC/" + e.get("javaCardVersion").asText());
                            }
                            if (e.has("otvVersion")) {
                                variant.add(e.get("otvVersion").asText());
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
            if (args.has(OPT_INSTALL) || args.has(OPT_UNINSTALL) || args.has(OPT_STORE_DATA) || args.has(OPT_DELIVER) || args.has(OPT_CARD_APPS) || args.has(OPT_CARD_INFO) || args.has(OPT_SECURE_APDU)) {
                // Locate a Fidesmo card
                CardTerminal terminal = TerminalManager.getByAID(Collections.singletonList(FidesmoCard.FIDESMO_APP_AID.getBytes()));
                if (apduTrace) {
                    terminal = LoggingCardTerminal.getInstance(terminal);
                }
                Card card = terminal.connect("*");
                fidesmoCard = FidesmoCard.getInstance(card.getBasicChannel());
                System.out.println("Using card in " + terminal.getName());

                if (args.has(OPT_CARD_INFO)) {
                    System.out.format("CIN: %s batch: %s UID: %s%n",
                            printableCIN(fidesmoCard.getCIN()),
                            HexUtils.bin2hex(fidesmoCard.getBatchId()),
                            fidesmoCard.getUID().map(i -> HexUtils.bin2hex(i)).orElse("N/A"));
                    // For platforms that are not yet supported by fdsm
                    String platform = FidesmoCard.ChipPlatform.valueOf(fidesmoCard.platformType).orElseThrow(() -> new NotSupportedException("Chip platform not supported")).toString();
                    System.out.format("OS type: %s (platfrom v%d)%n", platform, fidesmoCard.platformVersion);
                } else if (args.has(OPT_CARD_APPS)) {
                    FidesmoApiClient client = getClient();
                    List<byte[]> apps = fidesmoCard.listApps();
                    if (apps.size() > 0) {
                        printApps(queryApps(client, apps, verbose), System.out, verbose);
                    } else {
                        success("No applications");
                    }
                } else if (args.has(OPT_DELIVER)) {
                    FidesmoApiClient client = getClient();
                    FormHandler formHandler = getCommandLineFormHandler();

                    String service = args.valueOf(OPT_DELIVER).toString();
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
                    cardSession.deliver(appId, service);
                } else {
                    AuthenticatedFidesmoApiClient client = getAuthenticatedClient();
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
                            client.upload(cap);
                        }
                        fidesmoCard.deliverRecipe(client, formHandler, recipe);
                    } else if (args.has(OPT_UNINSTALL)) {
                        CAPFile cap = CAPFile.fromStream(new FileInputStream((File) args.valueOf(OPT_UNINSTALL)));
                        String recipe = RecipeGenerator.makeDeleteRecipe(cap.getPackageAID());
                        fidesmoCard.deliverRecipe(client, formHandler, recipe);
                    }

                    if (args.has(OPT_STORE_DATA)) {
                        List<byte[]> blobs = new ArrayList<>();
                        for (Object s : args.valuesOf(OPT_STORE_DATA)) {
                            blobs.add(HexUtils.stringToBin((String) s));
                        }
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        String recipe = RecipeGenerator.makeStoreDataRecipe(applet, blobs);
                        fidesmoCard.deliverRecipe(client, formHandler, recipe);
                    }

                    if (args.has(OPT_SECURE_APDU)) {
                        List<byte[]> apdus = new ArrayList<>();
                        for (Object s : args.valuesOf(OPT_SECURE_APDU)) {
                            apdus.add(HexUtils.stringToBin((String) s));
                        }
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        String recipe = RecipeGenerator.makeSecureTransceiveRecipe(applet, apdus);
                        fidesmoCard.deliverRecipe(client, formHandler, recipe);
                    }
                }
            }
        } catch (UserCancelledException e) {
            fail("Cancelled: " + e.getMessage());
        } catch (NotSupportedException e) {
            fail("Not supported: " + e.getMessage());
        } catch (HttpResponseException e) {
            fail("API error: " + e.getMessage());
        } catch (IOException e) {
            fail("I/O error: " + e.getMessage());
        } catch (CardException e) {
            fail("Card communication error: " + e.getMessage());
        } catch (GeneralSecurityException | Smartcardio.EstablishContextException e) {
            String s = TerminalManager.getExceptionMessage(e);
            fail("No smart card readers: " + (s == null ? e.getMessage() : s));
        } catch (Exception e) {
            e.printStackTrace();
            fail("Unknown error: " + e.getMessage());
        }
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
        FidesmoApiClient client = new FidesmoApiClient();
        if (apiTrace) {
            client.setTrace(true);
        }
        return client;
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
        // Check environment
        inspectEnvironment(args);
        if (appId == null || appKey == null) {
            fail("Provide appId and appKey, either via --app-id and --app-key or $FIDESMO_APP_ID and $FIDESMO_APP_KEY");
        }
        AuthenticatedFidesmoApiClient client = AuthenticatedFidesmoApiClient.getInstance(appId, appKey);
        if (apiTrace) {
            client.setTrace(true);
        }
        return client;
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
