package com.fidesmo.fdsm;

import apdu4j.BIBO;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.util.ArrayList;
import java.util.List;

public class TestChannel implements BIBO {

    private int nextResponse;
    private List<byte[]> responses;

    public TestChannel(List<byte[]> responses) {
        this.responses = responses;
    }

    @Override
    public byte[] transceive(byte[] commandAPDU) {
        return responses.get(nextResponse++);
    }

    @Override
    public void close() {

    }

    public static TestChannel fromStrings(String... responses) {
        try {
            List<byte[]> apdus = new ArrayList<>();

            for (String s : responses) {
                apdus.add(Hex.decodeHex(s));
            }

            return new TestChannel(apdus);
        } catch (DecoderException de) {
            throw new RuntimeException(de);
        }

    }
}
