package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import pro.javacard.AID;
import pro.javacard.CAPFile;
import pro.javacard.CAPPackage;

import java.io.IOException;
import java.io.InputStream;

// Handy wrapper to detect platform types
public class FidesmoCapFile extends CAPFile {
    public FidesmoCapFile(InputStream in) throws IOException {
        super(in);
    }

    public boolean isJCOP242R2() {
        AID jcop = new AID(HexUtils.hex2bin("D276000085494A434F5058"));
        for (CAPPackage p : getImports()) {
            if (p.getAid().equals(jcop) && p.getVersionString().equals("8.0"))
                return true;
        }
        return false;
    }

    public boolean isJCOP242R1() {
        AID jcop = new AID(HexUtils.hex2bin("D276000085494A434F5058"));
        for (CAPPackage p : getImports()) {
            if (p.getAid().equals(jcop) && p.getVersionString().equals("7.0"))
                return true;
        }
        return false;
    }
}
