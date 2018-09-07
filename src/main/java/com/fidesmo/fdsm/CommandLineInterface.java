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
    final static String OPT_DELETE_APPLET = "delete-applet";
    final static String OPT_CARD_APPS = "card-apps";
    final static String OPT_CARD_INFO = "card-info";
    final static String OPT_STORE_APPS = "store-apps";
    final static String OPT_FLUSH_APPLETS = "flush-applets";
    final static String OPT_LIST_RECIPES = "list-recipes";
    final static String OPT_CLEANUP = "cleanup";
    final static String OPT_INSTALL = "install";
    final static String OPT_PARAMS = "params";
    final static String OPT_CREATE = "create";
    final static String OPT_UNINSTALL = "uninstall";
    final static String OPT_STORE_DATA = "store-data";
    final static String OPT_SECURE_APDU = "secure-apdu";

    final static String OPT_TRACE_API = "trace-api";
    final static String OPT_TRACE_APDU = "trace-apdu";
    final static String OPT_VERBOSE = "verbose";

    protected static String appId = null;
    protected static String appKey = null;
    protected static boolean apduTrace = false;
    protected static boolean apiTrace = false;

    protected static OptionSet args = null;
    protected static boolean verbose = false;

    protected static void inspectEnvironment(OptionSet args) {
        // Get the app ID from the environment, if present
        appId = System.getenv("FIDESMO_APP_ID");
        if (appId != null && !args.has(OPT_APP_ID)) {
            // Lower case for similarity to dev portal
            System.out.println("Using $FIDESMO_APP_ID: " + appId.toLowerCase());
        }
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
            }

            if (appKey != null) {
                if (HexUtils.hex2bin(appKey).length != 16) {
                    throw new IllegalArgumentException("appKey hex must be 32 characters!");
                }
            }
        } catch (IllegalArgumentException e) {
            fail("Invalid value: " + e.getMessage());
        }
    }

    protected static OptionSet parseArguments(String[] argv) throws IOException {
        OptionParser parser = new OptionParser();

        parser.acceptsAll(Arrays.asList("h", "?", "help"), "Shows this help").forHelp();
        parser.accepts(OPT_APP_ID, "Specify application ID").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_APP_KEY, "Specify application key").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_STORE_DATA, "STORE DATA to applet").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_APPLET, "Specify applet").requiredIf(OPT_STORE_DATA).withRequiredArg().describedAs("AID");
        parser.accepts(OPT_DELIVER, "Deliver service").withRequiredArg().describedAs("appId/serviceId");
        parser.accepts(OPT_UPLOAD, "Upload CAP to Fidesmo").withOptionalArg().ofType(File.class).describedAs("CAP file");
        parser.accepts(OPT_LIST_APPLETS, "List applets at Fidesmo");
        parser.accepts(OPT_DELETE_APPLET, "Deletes applet from Fidesmo").withRequiredArg().describedAs("ID");
        parser.accepts(OPT_CARD_APPS, "List apps on the card");
        parser.accepts(OPT_CARD_INFO, "Show info about the card");
        parser.accepts(OPT_SECURE_APDU, "Send APDU via secure channel").withRequiredArg().describedAs("HEX");

        parser.accepts(OPT_STORE_APPS, "List apps in the store");
        parser.accepts(OPT_FLUSH_APPLETS, "Flush all applets from Fidesmo");
        parser.accepts(OPT_LIST_RECIPES, "List recipes at Fidesmo");
        parser.accepts(OPT_CLEANUP, "Clean up stale recipes");
        parser.accepts(OPT_INSTALL, "Install CAP to card").withRequiredArg().ofType(File.class).describedAs("CAP file");

        parser.accepts(OPT_PARAMS, "Installation parameters").withRequiredArg().describedAs("HEX");
        parser.accepts(OPT_CREATE, "Applet instance AID").withRequiredArg().describedAs("AID");
        parser.accepts(OPT_UNINSTALL, "Uninstall CAP from card").withRequiredArg().ofType(File.class).describedAs("CAP file");

        parser.accepts(OPT_TRACE_API, "Trace Fidesmo API");
        parser.accepts(OPT_TRACE_APDU, "Trace APDU-s");
        parser.accepts(OPT_VERBOSE, "Be verbose");


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
            success("\nMore information at https://github.com/fidesmo/fdsm\n");
        }

        // Set some variables
        if (args.has(OPT_TRACE_API))
            apiTrace = true;
        if (args.has(OPT_TRACE_APDU))
            apduTrace = true;
        if (args.has(OPT_VERBOSE))
            verbose = true;
        return args;
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    static void success(String message) {
        System.out.println(message);
        System.exit(0);
    }
}
