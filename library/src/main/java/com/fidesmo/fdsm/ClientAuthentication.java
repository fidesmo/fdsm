package com.fidesmo.fdsm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

public class ClientAuthentication {
    private final String authentication;

    private ClientAuthentication(String auth) {
        if (auth.split(":").length > 2) {
            throw new IllegalArgumentException("Wrong authentication format");
        }
        this.authentication = auth;
    }

    public boolean isCredentials() {
        return getUsername().isPresent();
    }

    public Optional<String> getUsername() {
        String[] creds = authentication.split(":");
        if (creds.length == 2)
            return Optional.ofNullable(creds[0]);
        return Optional.empty();
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
                throw new IllegalArgumentException("Invalid username and password format");
            }
            return forUserPassword(creds[0], creds[1]);
        } else {
            return forToken(auth);
        }
    }

    public String toAuthenticationHeader() {
        if (isCredentials()) {
            return "Basic " + Base64.getEncoder().encodeToString(authentication.getBytes(StandardCharsets.UTF_8));
        } else {
            return "Bearer " + authentication;
        }
    }
}
