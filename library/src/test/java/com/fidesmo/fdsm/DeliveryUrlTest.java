package com.fidesmo.fdsm;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

import java.util.Optional;

public class DeliveryUrlTest {

    @Test
    public void testAppIdServiceStringParsed() {
        DeliveryUrl delivery = DeliveryUrl.parse("11223344/test-service-1");
        assertEquals(delivery.getAppId(), Optional.of("11223344"));
        assertEquals(delivery.getService(), "test-service-1");
    }

    @Test
    public void testStagingDeliveryUrlParsed() {
        DeliveryUrl delivery = DeliveryUrl.parse("https://apps-staging.fidesmo.com/ab11ffcc/services/test-service-2");
        assertEquals(delivery.getAppId(), Optional.of("ab11ffcc"));
        assertEquals(delivery.getService(), "test-service-2");
    }
    
    @Test
    public void testWebSockedUrlParsed() {
        DeliveryUrl delivery = DeliveryUrl.parse("ws://test.fidesmo.com/path/test-service-3");
        assertEquals(delivery.getAppId(), Optional.empty());
        assertEquals(delivery.getService(), "ws://test.fidesmo.com/path/test-service-3");
    }

    @Test
    public void testSecuredWebSockedUrlParsed() {
        DeliveryUrl delivery = DeliveryUrl.parse("wss://test.fidesmo.com/path/test-service-4");
        assertEquals(delivery.getAppId(), Optional.empty());
        assertEquals(delivery.getService(), "wss://test.fidesmo.com/path/test-service-4");
    }
}