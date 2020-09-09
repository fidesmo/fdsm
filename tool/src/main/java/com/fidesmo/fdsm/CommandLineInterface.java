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
import joptsimple.OptionSpec;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

abstract class CommandLineInterface {
    static OptionParser parser = new OptionParser();

    final static protected OptionSpec<Void> OPT_HELP = parser.acceptsAll(Arrays.asList("h", "?", "help"), "Shows this help").forHelp();
    final static protected OptionSpec<Void> OPT_VERSION = parser.acceptsAll(Arrays.asList("V", "version"), "Show version and check for updates");

    final static protected OptionSpec<String> OPT_READER = parser.accepts("reader", "Specify reader to use").withRequiredArg().describedAs("reader");
    final static protected OptionSpec<Void> OPT_TRACE_API = parser.accepts("trace-api", "Trace Fidesmo API");
    final static protected OptionSpec<Void> OPT_TRACE_APDU = parser.accepts("trace-apdu", "Trace APDU-s");
    final static protected OptionSpec<Void> OPT_VERBOSE = parser.accepts("verbose", "Be verbose");

    final static protected OptionSpec<String> OPT_AUTH = parser.accepts("auth", "Use authentication credentials").withRequiredArg().describedAs("username:password / token");

    final static protected OptionSpec<String> OPT_STORE_DATA = parser.accepts("store-data", "STORE DATA to applet").withRequiredArg().describedAs("HEX");
    final static protected OptionSpec<String> OPT_APPLET = parser.accepts("applet", "Specify applet").requiredIf(OPT_STORE_DATA).withRequiredArg().describedAs("AID");

    final static protected OptionSpec<String> OPT_RUN = parser.accepts("run", "Run service").withRequiredArg().describedAs("appId/serviceId or URL");
    final static protected OptionSpec<String> OPT_FIELDS = parser.accepts("fields", "Service parameters").withRequiredArg().describedAs("field=value,...");

    final static protected OptionSpec<File> OPT_UPLOAD = parser.accepts("upload", "Upload CAP or recipe to Fidesmo").withRequiredArg().ofType(File.class).describedAs(".cap/.json file");
    final static protected OptionSpec<Void> OPT_LIST_APPLETS = parser.accepts("list-applets", "List applets at Fidesmo");
    final static protected OptionSpec<String> OPT_DELETE_APPLET = parser.accepts("delete-applet", "Deletes applet from Fidesmo").withRequiredArg().describedAs("ID");
    final static protected OptionSpec<Void> OPT_CARD_APPS = parser.accepts("card-apps", "List apps on the card");
    final static protected OptionSpec<Void> OPT_CARD_INFO = parser.accepts("card-info", "Show info about the card");
    final static protected OptionSpec<Void> OPT_OFFLINE = parser.accepts("offline", "Do not connect to Fidesmo server for retrieving further device info");
    final static protected OptionSpec<String> OPT_SECURE_APDU = parser.accepts("secure-apdu", "Send APDU via secure channel").withRequiredArg().describedAs("HEX");

    final static protected OptionSpec<String> OPT_STORE_APPS = parser.accepts("store-apps", "List apps in the store").withOptionalArg().describedAs("status");
    final static protected OptionSpec<Void> OPT_FLUSH_APPLETS = parser.accepts("flush-applets", "Flush all applets from Fidesmo");
    final static protected OptionSpec<Void> OPT_LIST_RECIPES = parser.accepts("list-recipes", "List recipes at Fidesmo");
    final static protected OptionSpec<Void> OPT_CLEANUP = parser.accepts("cleanup", "Clean up stale recipes");
    final static protected OptionSpec<File> OPT_INSTALL = parser.accepts("install", "Install CAP to card").withRequiredArg().ofType(File.class).describedAs("CAP file");

    final static protected OptionSpec<String> OPT_PARAMS = parser.accepts("params", "Installation parameters").withRequiredArg().describedAs("HEX");
    final static protected OptionSpec<String> OPT_CREATE = parser.accepts("create", "Applet instance AID").withRequiredArg().describedAs("AID");
    final static protected OptionSpec<String> OPT_UNINSTALL = parser.accepts("uninstall", "Uninstall CAP from card").withRequiredArg().describedAs("CAP file / AID");

    final static protected OptionSpec<Integer> OPT_QA = parser.accepts("qa", "Run a QA support session").withOptionalArg().ofType(Integer.class).describedAs("QA number");

    final static protected OptionSpec<Integer> OPT_TIMEOUT = parser.accepts("timeout", "Timeout for services").withRequiredArg().ofType(Integer.class).describedAs("minutes");

    protected static ClientAuthentication auth;
    protected static String apiurl;

    protected static boolean apduTrace = false;
    protected static boolean apiTrace = false;
    protected static boolean verbose = false;
    protected static boolean offline = false;

    protected static OptionSet args = null;

    protected static void inspectEnvironment(OptionSet args) {
        // Authentication
        if (!args.has(OPT_AUTH) && System.getenv().containsKey("FIDESMO_AUTH")) {
            System.out.println("Using $FIDESMO_AUTH for authentication");
            auth = ClientAuthentication.forUserPasswordOrToken(System.getenv("FIDESMO_AUTH"));
        }
        if (args.has(OPT_AUTH)) {
            auth = ClientAuthentication.forUserPasswordOrToken(args.valueOf(OPT_AUTH));
        }

        // API URL
        final String FIDESMO_API_URL = "FIDESMO_API_URL";
        try {
            apiurl = new URL(System.getenv().getOrDefault(FIDESMO_API_URL, FidesmoApiClient.APIv2)).toString();
        } catch (MalformedURLException e) {
            System.err.println("Invalid $FIDESMO_API_URL: " + System.getenv(FIDESMO_API_URL));
        }
    }

    protected static OptionSet parseArguments(String[] argv) throws IOException {

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

        if (args.has(OPT_HELP) || args.specs().size() == 0) {
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
        if (args.has(OPT_OFFLINE))
            offline = true;
        return args;
    }

    public static boolean requiresCard() {
        OptionSpec<?>[] commands = new OptionSpec<?>[]{
                OPT_INSTALL, OPT_UNINSTALL, OPT_STORE_DATA, OPT_SECURE_APDU, OPT_RUN, OPT_CARD_APPS, OPT_CARD_INFO, OPT_QA
        };
        return Arrays.stream(commands).anyMatch(args::has);
    }

    public static boolean requiresAuthentication() {
        OptionSpec<?>[] commands = new OptionSpec<?>[]{
                OPT_INSTALL, OPT_UNINSTALL, OPT_STORE_DATA, OPT_SECURE_APDU, OPT_UPLOAD, OPT_DELETE_APPLET, OPT_FLUSH_APPLETS, OPT_CLEANUP, OPT_LIST_APPLETS, OPT_LIST_RECIPES
        };
        return Arrays.stream(commands).anyMatch(a -> args.has(a));
    }

    static void fail(String message) {
        System.err.println();
        System.err.println(message);
        System.exit(1);
    }

    static void success() {
        System.exit(0);
    }

    static void success(String message) {
        System.out.println(message);
        System.exit(0);
    }
}
