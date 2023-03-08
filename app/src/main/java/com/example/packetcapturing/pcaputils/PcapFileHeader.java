package com.example.packetcapturing.pcaputils;

import java.io.IOException;
import java.io.OutputStream;

public class PcapFileHeader {
    public long magicNumber = Long.valueOf("d4c3b2a1", 16);
    public short majorVersion = Short.valueOf("0200", 16);
    public short minorVersion = Short.valueOf("0400", 16);
    public int thiszone = 0;
    public int sigfigs = 0;
    public long snaplen = Long.valueOf("0000FFFF", 16);
    public long network = Long.valueOf("01000000", 16);

    public byte[] getBytes() {
        byte[] byteArr = new byte[24];
        byteArr[0] = (byte) (magicNumber >>> 24);
        byteArr[1] = (byte) (magicNumber >>> 16);
        byteArr[2] = (byte) (magicNumber >>> 8);
        byteArr[3] = (byte) magicNumber;
        byteArr[4] = (byte) (majorVersion >>> 8);
        byteArr[5] = (byte) majorVersion;
        byteArr[6] = (byte) (minorVersion >>> 8);
        byteArr[7] = (byte) minorVersion;
        for (int i = 8; i < 16; ++i) {
            byteArr[i] = 0;
        }
        byteArr[16] = (byte) (snaplen >>> 24);
        byteArr[17] = (byte) (snaplen >>> 16);
        byteArr[18] = (byte) (snaplen >>> 8);
        byteArr[19] = (byte) snaplen;

        byteArr[20] = (byte) (network >>> 24);
        byteArr[21] = (byte) (network >>> 16);
        byteArr[22] = (byte) (network >>> 8);
        byteArr[23] = (byte) network;
        return byteArr;
    }

    public void writeToStream(OutputStream os) throws IOException {
        os.write(getBytes());
    }
}
