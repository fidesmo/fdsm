package com.fidesmo.fdsm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;

public class ClientInfo {
    public enum Capability {
        SE_ACCESS, PAYMENT_AID_ACCESS, ACCOUNTS, APP2APP;
        
        public static String stringify(Capability c) {
            return c.name().toLowerCase().replaceAll("_", "-");
        }
    }

    public static final Set<Capability> DEFAULT_CAPABILITIES = Set.of(Capability.SE_ACCESS, Capability.PAYMENT_AID_ACCESS, Capability.ACCOUNTS);

    private final static ClientInfo fdsm = new ClientInfo("fdsm", getBuildVersion());

    private String name;
    private String version;
    private Set<Capability> capabilities;
    private Locale locale;

    private ClientInfo(String name, String version, Set<Capability> capabilities, Locale locale) {
        this.name = name;
        this.version = version;
        this.capabilities = Collections.unmodifiableSet(capabilities);
        this.locale = locale;
    }

    private ClientInfo(String name, String version) {
        this(name, version, DEFAULT_CAPABILITIES, Locale.getDefault());
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public Set<Capability> getCapabilities() {
        return capabilities;
    }

    public ClientInfo withCapabilities(Set<Capability> capabilities) {
        return new ClientInfo(this.name, this.version, capabilities, this.locale);
    }

    public ClientInfo withVersion(String version) {
        return new ClientInfo(this.name, version, this.capabilities, this.locale);
    }

    public ClientInfo withName(String name) {
        return new ClientInfo(name, this.version, this.capabilities, this.locale);
    }

    public ClientInfo withLocale(Locale locale) {
        return new ClientInfo(this.name, this.version, this.capabilities, locale);
    }

    public List<Header> asHeaders() {
        
        return List.of(
            //Description format: platform/version;application/version/sdkVersion;deviceModel
            new BasicHeader("Fidesmo-Client-Description", String.format("fdsm/;%s/%s/%s;%s", name, version, getBuildVersion(), getOS())),
            new BasicHeader("Fidesmo-Client-Capabilities", capabilities.stream().map(Capability::stringify).collect(Collectors.joining(","))),
            new BasicHeader(HttpHeaders.ACCEPT_LANGUAGE, this.locale.toLanguageTag()),
            new BasicHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString()),
            new BasicHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
        );
    }

    public static ClientInfo fdsm() {
        return fdsm;
    }

    public static String getBuildVersion() {
        try (InputStream versionfile = FidesmoApiClient.class.getResourceAsStream("version.txt")) {
            String version = "unknown-development";
            if (versionfile != null) {
                try (BufferedReader vinfo = new BufferedReader(new InputStreamReader(versionfile, StandardCharsets.US_ASCII))) {
                    version = vinfo.readLine();
                }
            }
            return version;
        } catch (IOException e) {
            return "unknown-error";
        }
    }

    public static String getOS() {
        String os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            return "Windows-Desktop";
        } else if (os.startsWith("Mac")) {
            return "MacOS-Desktop";
        } else if (os.startsWith("Linux")) {
            return "Linux-Desktop";
        }
        return "Other-Desktop";
    }
}
