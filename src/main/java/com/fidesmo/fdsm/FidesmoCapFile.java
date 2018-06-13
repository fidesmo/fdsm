package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import pro.javacard.gp.AID;
import pro.javacard.gp.CAPFile;

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
            if (p.aid.equals(jcop) && p.major == 8 && p.minor == 0)
                return true;
        }
        return false;
    }
}
