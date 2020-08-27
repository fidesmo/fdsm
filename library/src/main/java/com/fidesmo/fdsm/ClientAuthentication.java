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
