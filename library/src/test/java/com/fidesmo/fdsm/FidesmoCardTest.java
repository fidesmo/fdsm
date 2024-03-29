package com.fidesmo.fdsm;

import org.apache.commons.codec.binary.Hex;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class FidesmoCardTest {

    @Test
    public void testOldStorageAppletSupport() {
        TestChannel channel = TestChannel.fromStrings(
                "001122334455669000",
                "6F108408A000000151000000A5049F6501FF9000",
                "9F7F2A47906B644700E4D80300816500485399064800000000000000005758594E4E4E4E4E00000000000000009000",
                "45073D5F8004132EDA9000",
                "6A82",
                "42030000C7430602396818B7440203009000"
        );

        FidesmoCard card = FidesmoCard.detectOffline(channel).get();
        assertEquals(Hex.encodeHexString(card.getCIN()), "3d5f8004132eda");
        assertEquals(card.getBatchId(), 199);
    }

    @Test
    public void testCorrectParsingCardInfo() {
        TestChannel channel = TestChannel.fromStrings(
                "001122334455669000",
                "6F108408A000000151000000A5049F6501FF9000",
                "9F7F2A47906B644700E4D80300816501062899064800000000000000005758594E4E4E4E4E00000000000000009000",
                "45073D5F8004132EDA9000",
                "6A82",
                "420300008C4306023967FE8B419000"
        );

        FidesmoCard card = FidesmoCard.detectOffline(channel).get();
        assertEquals(Hex.encodeHexString(card.getCIN()), "3d5f8004132eda");
        assertEquals(card.getBatchId(), 140);
    }
}