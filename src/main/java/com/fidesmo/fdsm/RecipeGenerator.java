package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pro.javacard.gp.AID;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class RecipeGenerator {
    public static String makeInstallRecipe(AID pkg, AID app, AID instance, byte[] params) throws IOException {
        if (instance == null)
            instance = app;
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putObject("failureMessage").put("en", "Could not install " + pkg);
        r.putObject("successMessage").put("en", "Successfully installed " + pkg);
        r.putObject("description").put("title", "Install application " + instance);

        ObjectNode action = JsonNodeFactory.instance.objectNode();
        action.put("endpoint", "/ccm/install");
        ObjectNode content = action.putObject("content");
        content.put("executableLoadFile", pkg.toString());
        content.put("executableModule", app.toString());
        content.put("application", instance.toString());
        if (params != null)
            content.put("parameters", HexUtils.bin2hex(params));
        r.putArray("actions").add(action);

        return r.toString();
    }

    public static String makeDeleteRecipe(AID pkg) throws IOException {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putObject("failureMessage").put("en", "Could not uninstall " + pkg);
        r.putObject("successMessage").put("en", "Successfully uninstalled " + pkg);
        r.putObject("description").put("title", "Uninstall application " + pkg);

        ObjectNode action = JsonNodeFactory.instance.objectNode();
        action.put("endpoint", "/ccm/delete");
        ObjectNode content = action.putObject("content");
        content.put("application", pkg.toString());
        content.put("withRelated", true);
        r.putArray("actions").add(action);

        return r.toString();
    }

    public static String makeStoreDataRecipe(AID app, List<byte[]> payloads) throws IOException {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putObject("failureMessage").put("en", "Could not store data to " + app);
        r.putObject("successMessage").put("en", "Successfully stored data to " + app);
        r.putObject("description").put("title", "Store data to " + app);

        ArrayNode actions = r.putArray("actions");
        for (byte[] payload : payloads) {
            ObjectNode action = JsonNodeFactory.instance.objectNode();
            action.put("endpoint", "/ccm/storedata");
            ObjectNode content = action.putObject("content");
            content.put("application", app.toString());
            content.put("tagType", "DGI");
            content.put("data", HexUtils.bin2hex(payload));
            actions.add(action);
        }
        return r.toString();
    }

    public static void main(String[] argv) throws Exception {
        System.out.println(makeInstallRecipe(AID.fromString("a00000061700d7d13e65"), AID.fromString("a00000061700d7d13e6501"), AID.fromString("A0000007810101002411111111111111"), HexUtils.hex2bin("0102030405")));
        System.out.println(makeDeleteRecipe(AID.fromString("a00000061700d7d13e65")));
        System.out.println(makeStoreDataRecipe(AID.fromString("a00000061700d7d13e65"), Arrays.asList(new byte[][]{HexUtils.hex2bin("0102030405")})));
    }
}
