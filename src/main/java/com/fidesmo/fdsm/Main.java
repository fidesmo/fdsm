package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import apdu4j.LoggingCardTerminal;
import apdu4j.TerminalManager;
import com.fasterxml.jackson.databind.JsonNode;
import joptsimple.OptionSet;
import pro.javacard.gp.AID;
import pro.javacard.gp.CAPFile;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.*;

public class Main extends CommandLineInterface {
    private static FidesmoCard fidesmoCard;

    public static void main(String[] argv) {
        try {
            // Inspect arguments and environment
            OptionSet args = parseArguments(argv);

            // List applets
            if (args.has(OPT_LIST_APPLETS)) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();

                JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.ELF_URL));
                if (applets.size() > 0) {
                    Map<String, ArrayList<String>> r = new HashMap<>();
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
                        final ArrayList<String> l;
                        if (!r.containsKey(aid)) {
                            l = new ArrayList<>();
                            r.put(aid, l);
                        } else {
                            l = r.get(aid);
                        }
                        l.add(String.join(", ", variant));
                    }
                    for (Map.Entry<String, ArrayList<String>> e : r.entrySet()) {
                        System.out.println("AID: " + e.getKey() + " (" + String.join("; ", e.getValue()) + ")");
                    }
                } else {
                    System.out.println("No applets");
                }
            }

            // List applets
            if (args.has(OPT_LIST_RECIPES)) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();
                JsonNode recipes = client.rpc(client.getURI(FidesmoApiClient.RECIPE_SERVICES_URL, appId));
                if (recipes.size() > 0) {
                    System.out.println(FidesmoApiClient.mapper.writer(FidesmoApiClient.printer).writeValueAsString(recipes));
                } else {
                    System.out.println("No recipes");
                }
            }

            // Cleanup recipes
            if (args.has(OPT_CLEANUP)) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();
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
                    System.out.println("Cleaned up " + removed + " recipes");
                } else {
                    System.out.println("No recipes");
                }
            }

            if (args.has(OPT_UPLOAD) && args.valueOf(OPT_UPLOAD) != null) {
                getAuthenticatedClient().upload((File) args.valueOf(OPT_UPLOAD), true);
            } else if (args.has(OPT_FLUSH_APPLETS)) {
                AuthenticatedFidesmoApiClient client = getAuthenticatedClient();
                JsonNode applets = client.rpc(client.getURI(FidesmoApiClient.ELF_URL));
                for (JsonNode e : applets) {
                    client.delete(client.getURI(FidesmoApiClient.ELF_ID_URL, e.get("id").asText()));
                }
            }

            if (args.has(OPT_INSTALL) || args.has(OPT_UNINSTALL) || args.has(OPT_STORE_DATA) || args.has(OPT_DELIVER)) {
                // Following require card access
                // Locate a Fidesmo card
                CardTerminal terminal = TerminalManager.getByAID(Collections.singletonList(HexUtils.hex2bin("A000000617020002000001")));
                if (apduTrace) {
                    terminal = LoggingCardTerminal.getInstance(terminal);
                }
                Card card = terminal.connect("*");
                fidesmoCard = FidesmoCard.getInstance(card.getBasicChannel());
                System.out.println("Using card in " + terminal.getName());

                if (args.has(OPT_DELIVER)) {
                    FidesmoApiClient client = new FidesmoApiClient();

                    String service = args.valueOf(OPT_DELIVER).toString();
                    if (service.contains("/")) {
                        String[] bits = service.split("/");
                        if (bits.length == 2) {
                            service = bits[1];
                            appId = bits[0];
                        } else {
                            fail("Invalid argument: " + service);
                        }
                    }
                    if (appId == null) {
                        fail("Need Application ID");
                    }
                    ServiceDeliverySession cardSession = ServiceDeliverySession.getInstance(fidesmoCard, client);
                    cardSession.deliver(appId, service);
                } else {
                    AuthenticatedFidesmoApiClient client = getAuthenticatedClient();

                    if (args.has(OPT_INSTALL)) {
                        CAPFile cap = new CAPFile(new FileInputStream((File) args.valueOf(OPT_INSTALL)));
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
                            if (params.length > 0 && params[0] == 0xC9) {
                                fail("Installation parameters must be without C9 tag");
                            }
                        }
                        String recipe = RecipeGenerator.makeInstallRecipe(cap.getPackageAID(), applet, instance, params);
                        if (args.has(OPT_UPLOAD))
                            client.upload((File) args.valueOf(OPT_INSTALL), true);
                        fidesmoCard.deliverRecipe(client, recipe);
                    } else if (args.has(OPT_UNINSTALL)) {
                        CAPFile cap = new CAPFile(new FileInputStream((File) args.valueOf(OPT_UNINSTALL)));
                        String recipe = RecipeGenerator.makeDeleteRecipe(cap.getPackageAID());
                        fidesmoCard.deliverRecipe(client, recipe);
                    }

                    if (args.has(OPT_STORE_DATA)) {
                        List<byte[]> blobs = new ArrayList<>();
                        for (Object s : args.valuesOf(OPT_STORE_DATA)) {
                            blobs.add(HexUtils.stringToBin((String) s));
                        }
                        AID applet = AID.fromString(args.valueOf(OPT_APPLET).toString());
                        String recipe = RecipeGenerator.makeStoreDataRecipe(applet, blobs);
                        fidesmoCard.deliverRecipe(client, recipe);
                    }
                }
            }
        } catch (IOException e) {
            fail("I/O error: " + e.getMessage());
        } catch (CardException e) {
            fail("Card communication error: " + e.getMessage());
        } catch (GeneralSecurityException e) {
            fail("No smart card readers?" + e.getMessage());
        }
    }

    private static AuthenticatedFidesmoApiClient getAuthenticatedClient() {
        if (appId == null || appKey == null) {
            fail("Provide appId and appKey, either via --app-id and --app-key or $FIDESMO_APP_ID and $FIDESMO_APP_KEY");
        }
        AuthenticatedFidesmoApiClient client = AuthenticatedFidesmoApiClient.getInstance(appId, appKey);
        if (rpcTrace) {
            client.setTrace(true);
        }
        return client;
    }
}
