/*
 * Copyright (c) 2018 - present Fidesmo AB
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
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
