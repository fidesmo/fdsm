package com.fidesmo.fdsm;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import javax.smartcardio.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TestChannel extends CardChannel {

    private int nextResponse;
    private List<ResponseAPDU> responses;

    public TestChannel(List<ResponseAPDU> responses) {
        this.responses = responses;
    }

    @Override
    public Card getCard() {
        return null;
    }

    @Override
    public int getChannelNumber() {
        return 0;
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU commandAPDU) {
        return responses.get(nextResponse++);
    }

    @Override
    public int transmit(ByteBuffer byteBuffer, ByteBuffer byteBuffer1) {
        ResponseAPDU resp = responses.get(nextResponse++);
        byteBuffer1.put(resp.getBytes());
        return resp.getBytes().length;
    }

    @Override
    public void close() {}

    public static TestChannel fromStrings(String... responses) {
        try {
            List<ResponseAPDU> apdus = new ArrayList<>();

            for (String s : responses) {
                apdus.add(new ResponseAPDU(Hex.decodeHex(s)));
            }

            return new TestChannel(apdus);
        } catch (DecoderException de) {
            throw new RuntimeException(de);
        }

    }
}
