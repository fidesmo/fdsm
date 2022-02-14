package com.fidesmo.fdsm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeliveryUrl {

  private static final Pattern URI_PATH_PATTERN = Pattern.compile("/(\\w+)/services/(.*)");

  private final Optional<String> appId;
  private final String service;
  private final boolean webSocket;

  public DeliveryUrl(Optional<String> appId, String service, boolean webSocket) {
    this.appId = appId;
    this.service = service;
    this.webSocket = webSocket;
  }

  public Optional<String> getAppId() {
    return appId;
  }

  public String getService() {
    return service;
  }

  public boolean isWebSocket() {
    return webSocket;
  }

  /**
   * Parses delivery parameters from the following representation forms:
   * * :appId/:service - usual form used in --run command
   * * https://apps.fidesmo.com/:appId/services/:service - web/mobile clients URL
   * * ws:// or wss:// URL – Web socket URLs
   */
  public static DeliveryUrl parse(String deliveryString) {
    try {
      if (deliveryString.startsWith("https://") || deliveryString.startsWith("https://")) {
        URI uri = new URI(deliveryString);
        // Expected format is /{appId}/services/{service}
        Matcher m = URI_PATH_PATTERN.matcher(uri.getPath());
        if (m.matches()) {
          return new DeliveryUrl(Optional.of(m.group(1)), m.group(2), false);
        } else {
          throw new IllegalArgumentException("Expected delivery string format is https://server/:appId/services/:service");
        }      
      } if (deliveryString.startsWith("wss://") || deliveryString.startsWith("ws://")) {
        new URI(deliveryString); // validate a string is a proper URI
        return new DeliveryUrl(Optional.empty(), deliveryString, true);
      } else {
        if (deliveryString.contains("/")) {
          String[] bits = deliveryString.split("/");
          if (bits.length == 2 && bits[0].length() == 8) {
            return new DeliveryUrl(Optional.of(bits[0]), bits[1], false);
          } else {
            throw new IllegalArgumentException("Invalid format for service: " + deliveryString);
          }        
        } else {
          return new DeliveryUrl(Optional.empty(), deliveryString, false);
        }
      }
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid URL syntax: " + e.getMessage());
    }
  }
}
