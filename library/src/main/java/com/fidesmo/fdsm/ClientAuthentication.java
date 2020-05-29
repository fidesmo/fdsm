package com.fidesmo.fdsm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ClientAuthentication {
    private final String authentication;

    private ClientAuthentication(String auth) {
        if (auth.split(":").length > 2) {
            throw new IllegalArgumentException("Wrong authentication format");
        }
        this.authentication = auth;
    }

    public boolean isToken() {
        return !isCredentials();
    }

    public boolean isCredentials() {
        return authentication.contains(":");
    }

    public String getUsername() {
        if (!isCredentials()) return null;
        return authentication.split(":")[0];
    }

    public static ClientAuthentication forToken(String token) {
        return new ClientAuthentication(token);
    }

    public static ClientAuthentication forUserPassword(String user, String password) {
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("Username is not set");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is not set");
        }
        return new ClientAuthentication(user + ":" + password);
    }

    public static ClientAuthentication forUserPasswordOrToken(String auth) {
        if (auth.contains(":")) {
            String[] creds = auth.split(":");
            if (creds.length != 2) {
                throw new IllegalArgumentException("Invalid user name and password format");
            }
            return forUserPassword(creds[0], creds[1]);
        } else {
            return forToken(auth);
        }
    }

    public String toAuthenticationHeader() {
        if (isToken()) {
            return "Bearer " + authentication;
        } else {
            return "Basic" + Base64.getEncoder().encodeToString(authentication.getBytes(StandardCharsets.UTF_8));
        }
    }
}
