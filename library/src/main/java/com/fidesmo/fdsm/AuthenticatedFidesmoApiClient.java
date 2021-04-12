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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import pro.javacard.AID;
import pro.javacard.CAPFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class AuthenticatedFidesmoApiClient extends FidesmoApiClient {

    private AuthenticatedFidesmoApiClient(ClientAuthentication auth, PrintStream apidump) {
        super(auth, apidump);
    }

    public static AuthenticatedFidesmoApiClient getInstance(ClientAuthentication auth, PrintStream apidump) throws IllegalArgumentException {
        return new AuthenticatedFidesmoApiClient(auth, apidump);
    }

    public void put(URI uri, ObjectNode json) throws IOException {
        HttpPut put = new HttpPut(uri);
        put.setEntity(new StringEntity(RecipeGenerator.mapper.writeValueAsString(json), ContentType.APPLICATION_JSON));
        transmit(put).close();
    }

    public void delete(URI uri) throws IOException {
        HttpDelete delete = new HttpDelete(uri);
        transmit(delete).close();
    }

    // Upload a CAP file
    public void upload(CAPFile cap) throws IOException {
        Optional<String> javaCardVersion = cap.guessJavaCardVersion();
        List<String> unsupportedVersions = Arrays.asList("3.1.0");
        if (!javaCardVersion.isPresent() || unsupportedVersions.contains(javaCardVersion.get())) {
            throw new IOException("Fidesmo supports JavaCard up to version 3.0.5");
        }

        HttpPost post = new HttpPost(getURI(ELF_URL));
        // Metadata headers
        post.setHeader("Java-Card-Version", cap.guessJavaCardVersion().get());
        // Do not send this info at this moment
        if (cap.guessGlobalPlatformVersion().isPresent()) {
            String gpver = cap.guessGlobalPlatformVersion().get();
            // Always "upgrade" to (and verify against) 2.2
            if (gpver.equals("2.1.1"))
                gpver = "2.2";
            post.setHeader("Global-Platform-Version", gpver);
        }
        if (isJCOP242R2(cap)) {
            post.setHeader("OS-Type-Version", "JCOP 2.4.2r2");
        } else if (isJCOP242R1(cap)) {
            post.setHeader("OS-Type-Version", "JCOP 2.4.2r1");
        }

        // CAP content
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        cap.store(bos);
        post.setEntity(new ByteArrayEntity(bos.toByteArray()));
        transmit(post);
    }

    private static boolean isJCOPX(CAPFile cap, String version) {
        AID jcop = new AID(HexUtils.hex2bin("D276000085494A434F5058"));
        return cap.getImports().stream().anyMatch(p -> p.getAid().equals(jcop) && p.getVersionString().equals(version));
    }

    public static boolean isJCOP242R2(CAPFile cap) {
        // JC 3.0.1, GP 2.2.1, JCOPX 8.0
        return isJCOPX(cap, "8.0");
    }

    public static boolean isJCOP242R1(CAPFile cap) {
        // JC 3.0.1, GP 2.1.1, JCOPX 7.0
        return isJCOPX(cap, "7.0");
    }
}
