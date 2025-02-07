package com.fidesmo.fdsm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;

public class ClientDescription {
    public enum Capability {
        SE_ACCESS, PAYMENT_AID_ACCESS, ACCOUNTS, A2A;
    }

    public static final Set<Capability> DEFAULT_CAPABILITIES = Set.of(Capability.SE_ACCESS, Capability.PAYMENT_AID_ACCESS, Capability.ACCOUNTS);

    private final static ClientDescription fdsm = new ClientDescription("fdsm");

    private String name;
    private String version;
    private Set<Capability> capabilities;

    public ClientDescription(String name, String version, Set<Capability> capabilities) {
        this.name = name;
        this.version = version;
        this.capabilities = capabilities;
    }

  public ClientDescription(String name) {
      this.name = name;
      this.version = getBuildVersion();
      this.capabilities = DEFAULT_CAPABILITIES;
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

    public ClientDescription withCapabilities(Set<Capability> capabilities) {
        return new ClientDescription(this.name, this.version, capabilities);
    }

    public ClientDescription withVersion(String version) {
        return new ClientDescription(this.name, version, this.capabilities);
    }

    public ClientDescription withName(String name) {
        return new ClientDescription(name, this.version, this.capabilities);
    }

    public List<Header> asHeaders() {
        return List.of(
            new BasicHeader("Fidesmo-Client-Description", this.name + "/;//" + getVersion() + ";"),
            new BasicHeader("Fidesmo-Client-Capabilities", capabilities.stream().map(e -> e.name().replaceAll("_", "-")).collect(Collectors.joining(",")))
        );
    }

    public static ClientDescription fdsm() {
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
}
