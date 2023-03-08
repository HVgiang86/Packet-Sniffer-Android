package com.example.packetcapturing.pcaputils;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

public class PcapRecord {
    byte[] ip4Header;
    byte[] protocolHeader;
    byte[] payload;
    private PacketHeader packetHeader;

    public PcapRecord(long time, byte[] payload, byte[] ip4Header, byte[] protocolHeader) {
        this.ip4Header = ip4Header;
        this.payload = payload;
        long ts_sec = time / 1000;
        long ts_usec = time - ts_sec;
        long incl_len = payload.length+14+ip4Header.length + protocolHeader.length;
        long orig_len = incl_len;
        packetHeader =  new PacketHeader(ts_sec, ts_usec, incl_len, orig_len);
        Log.d("PCAP FILE LOG", "size: " + incl_len +" : " +orig_len);
        this.protocolHeader = protocolHeader;

    }

    public byte[] linkLayerHeader() {
        byte[] arr = new byte[14];
        for (int i=0; i < 12; ++i) {
            arr[i] = 9;
        }
        arr[12] = 8;
        arr[13] = 0;
        return arr;
    }

    public void writeToStream(OutputStream os) throws IOException {

        os.write(packetHeader.getBytes());
        os.write(linkLayerHeader());
        os.write(ip4Header);
        os.write(protocolHeader);
        os.write(payload);

    }
}
