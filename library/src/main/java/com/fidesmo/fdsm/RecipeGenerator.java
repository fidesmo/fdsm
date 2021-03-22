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

import apdu4j.core.HexUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pro.javacard.AID;

import java.util.List;
import java.util.stream.Collectors;

public class RecipeGenerator {
    static ObjectMapper mapper = new ObjectMapper();

    // uses LFDBH
    public static ObjectNode makeInstallRecipe(byte[] pkg, AID app, AID instance, byte[] params) {
        if (instance == null)
            instance = app;
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putObject("failureMessage").put("en", "Could not install " + instance);
        r.putObject("successMessage").put("en", "Successfully installed " + instance);
        r.putObject("description").put("title", "Install application " + instance);

        ObjectNode action = JsonNodeFactory.instance.objectNode();
        action.put("endpoint", "/ccm/install");
        ObjectNode content = action.putObject("content");
        content.put("id", HexUtils.bin2hex(pkg));
        content.put("module", app.toString());
        content.put("application", instance.toString());
        content.put("searchBy", "lfdbh");
        if (params != null)
            content.put("parameters", HexUtils.bin2hex(params));
        r.putArray("actions").add(action);

        return r;
    }

    public static ObjectNode makeDeleteRecipe(AID pkg) {
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

        return r;
    }

    public static ObjectNode makeStoreDataRecipe(AID app, List<byte[]> payloads) {
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
        return r;
    }

    public static ObjectNode makeSecureTransceiveRecipe(AID app, List<byte[]> apdus) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putObject("failureMessage").put("en", "Could not send apdus to " + app);
        r.putObject("successMessage").put("en", "Successfully sent apdus to " + app);
        r.putObject("description").put("title", "Secure send apdus to " + app);
        ArrayNode actions = r.putArray("actions");

        // All APDU-s are sent in one batch, without a reply
        ObjectNode action = JsonNodeFactory.instance.objectNode();
        action.put("endpoint", "/secure-transceive");
        ObjectNode content = action.putObject("content");
        content.put("application", app.toString());
        content.set("commands", mapper.valueToTree(apdus.stream().map(HexUtils::bin2hex).collect(Collectors.toList())));
        actions.add(action);
        return r;
    }
}
