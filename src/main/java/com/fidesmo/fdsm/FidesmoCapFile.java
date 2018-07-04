package com.fidesmo.fdsm;

import apdu4j.HexUtils;
import pro.javacard.AID;
import pro.javacard.CAPFile;
import pro.javacard.CAPPackage;

import java.io.IOException;
import java.io.InputStream;

// Handy wrapper to detect platform types. This is not very exact, it assumes that
// the applet is linked against exact platform exp files (R1 is loadable to R2)
public class FidesmoCapFile extends CAPFile {
    public FidesmoCapFile(InputStream in) throws IOException {
        super(in);
    }

    private boolean isJCOPX(String version) {
        AID jcop = new AID(HexUtils.hex2bin("D276000085494A434F5058"));
        for (CAPPackage p : getImports()) {
            if (p.getAid().equals(jcop) && p.getVersionString().equals(version))
                return true;
        }
        return false;
    }

    public boolean isJCOP242R2() {
        // JC 3.0.1, GP 2.2.1, JCOPX 8.0
        return isJCOPX("8.0");
    }

    public boolean isJCOP242R1() {
        // JC 3.0.1, GP 2.1.1, JCOPX 7.0
        return isJCOPX("7.0");
    }
}
