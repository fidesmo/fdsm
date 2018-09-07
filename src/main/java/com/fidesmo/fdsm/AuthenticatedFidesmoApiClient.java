package com.fidesmo.fdsm;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class AuthenticatedFidesmoApiClient extends FidesmoApiClient {
    private AuthenticatedFidesmoApiClient(String appId, String appKey) {
        super(appId, appKey);
    }

    public static AuthenticatedFidesmoApiClient getInstance(String appId, String appKey) throws IllegalArgumentException {
        return new AuthenticatedFidesmoApiClient(appId, appKey);
    }

    public void put(URI uri, String json) throws IOException {
        HttpPut put = new HttpPut(uri);
        put.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        transmit(put);
    }

    public void delete(URI uri) throws IOException {
        HttpDelete delete = new HttpDelete(uri);
        transmit(delete);
    }

    public String getAppId() {
        return appId;
    }


    // Upload a CAP file
    public void upload(File path) throws IOException {
        final FidesmoCapFile cap;
        try (InputStream in = new FileInputStream(path)) {
            cap = new FidesmoCapFile(in);
        }

        if (cap.guessJavaCardVersion().equals("3.0.5")) {
            throw new IOException("Fidesmo supports JavaCard up to version 3.0.4");
        }

        try (InputStream in = new FileInputStream(path)) {
            HttpPost post = new HttpPost(getURI(ELF_URL));
            // Metadata headers
            post.setHeader("Java-Card-Version", cap.guessJavaCardVersion());
            // Do not send this info at this moment
            if (cap.guessGlobalPlatformVersion() != null) {
                String gpver = cap.guessGlobalPlatformVersion();
                // Always "upgrade" to (and verify against) 2.2
                if (gpver.equals("2.1.1"))
                    gpver = "2.2";
                post.setHeader("Global-Platform-Version", gpver);
            }
            if (cap.isJCOP242R2()) {
                post.setHeader("OS-Type-Version", "JCOP 2.4.2r2");
            } else if (cap.isJCOP242R1()) {
                post.setHeader("OS-Type-Version", "JCOP 2.4.2r1");
            }
            // CAP content
            post.setEntity(new InputStreamEntity(in));
            transmit(post);
        }
    }
}
