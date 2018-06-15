package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

abstract class CommandLineInterface {
    final static String OPT_APP_ID = "app-id";
    final static String OPT_APP_KEY = "app-key";
    final static String OPT_APPLET = "applet";
    final static String OPT_DELIVER = "deliver";
    final static String OPT_UPLOAD = "upload";
    final static String OPT_LIST_APPLETS = "list-applets";
    final static String OPT_FLUSH_APPLETS = "flush-applets";
    final static String OPT_LIST_RECIPES = "list-recipes";
    final static String OPT_CLEANUP = "cleanup";
    final static String OPT_INSTALL = "install";
    final static String OPT_PARAMS = "params";
    final static String OPT_CREATE = "create";
    final static String OPT_UNINSTALL = "uninstall";
    final static String OPT_STORE_DATA = "store-data";
    final static String OPT_TRACE_RPC = "trace-rpc";
    final static String OPT_TRACE_APDU = "trace-apdu";

    protected static String appId = null;
    protected static String appKey = null;
    protected static boolean apduTrace = false;
    protected static boolean rpcTrace = false;


    private static void inspectEnvironment(OptionSet args) {
        // Get the app ID from the environment, if present
        appId = System.getenv("FIDESMO_APP_ID");
        if (args.has(OPT_APP_ID)) {
            appId = args.valueOf(OPT_APP_ID).toString();
        }
        // Get the app key from the environment, if present
        appKey = System.getenv("FIDESMO_APP_KEY");
        if (args.has(OPT_APP_KEY)) {
            appKey = args.valueOf(OPT_APP_KEY).toString();
        }

        // Validate
        try {
            if (appId != null) {
                if (HexUtils.hex2bin(appId).length != 4) {
                    throw new IllegalArgumentException("appId hex must be 8 characters!");
                }
                // Lower case for similarity to dev portal
                System.out.println("# Using appID " + appId.toLowerCase());
            }

            if (appKey != null) {
                if (HexUtils.hex2bin(appKey).length != 16) {
                    throw new IllegalArgumentException("appKey hex must be 32 characters!");
                }
            }
        } catch (IllegalArgumentException e) {
            fail("Invalid value: " + e.getMessage());
        }

        if (args.has(OPT_TRACE_RPC))
            rpcTrace = true;
        if (args.has(OPT_TRACE_APDU))
            apduTrace = true;
    }

    protected static OptionSet parseArguments(String[] argv) throws IOException {
        OptionSet args = null;
        OptionParser parser = new OptionParser();

        parser.acceptsAll(Arrays.asList("h", "?", "help"), "Shows this help").forHelp();
        parser.accepts(OPT_APP_ID, "Specify application ID").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_APP_KEY, "Specify application key").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_STORE_DATA, "STORE DATA to applet").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_APPLET, "Specify applet").requiredIf(OPT_STORE_DATA).withRequiredArg().describedAs("AID");
        parser.accepts(OPT_DELIVER, "Deliver service").withRequiredArg();
        parser.accepts(OPT_UPLOAD, "Upload CAP to Fidesmo").withOptionalArg().ofType(File.class).describedAs("CAP file");
        parser.accepts(OPT_LIST_APPLETS, "List applets at Fidesmo");
        parser.accepts(OPT_FLUSH_APPLETS, "Flush all applets from Fidesmo");
        parser.accepts(OPT_LIST_RECIPES, "List recipes at Fidesmo");
        parser.accepts(OPT_CLEANUP, "Clean up stale recipes");
        parser.accepts(OPT_INSTALL, "Install CAP to card").withRequiredArg().ofType(File.class).describedAs("CAP file");
        parser.accepts(OPT_PARAMS, "Installation paremeters").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_CREATE, "Applet instance AID").withRequiredArg().describedAs("AID");
        parser.accepts(OPT_UNINSTALL, "Uninstall CAP from card").withRequiredArg().ofType(File.class).describedAs("CAP file");

        parser.accepts(OPT_TRACE_RPC, "Trace Fidesmo API");
        parser.accepts(OPT_TRACE_APDU, "Trace APDU-s");

        // Parse arguments
        try {
            args = parser.parse(argv);
        } catch (OptionException e) {
            if (e.getCause() != null) {
                System.err.println(e.getMessage() + ": " + e.getCause().getMessage());
            } else {
                System.err.println(e.getMessage());
            }
            System.err.println();
            parser.printHelpOn(System.err);
            System.exit(1);
        }

        if (args.has("help") || args.specs().size() == 0) {
            System.out.println("# fdsm v" + FidesmoApiClient.getVersion());
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        inspectEnvironment(args);
        return args;
    }

    static void fail(String message) {
        System.err.println("Failure: " + message);
        System.exit(1);
    }
}
