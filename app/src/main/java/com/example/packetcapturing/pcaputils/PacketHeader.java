package com.example.packetcapturing.pcaputils;

public class PacketHeader {
    public long ts_sec;         /* timestamp seconds */
    public long ts_usec;        /* timestamp microseconds */
    public long incl_len;       /* number of octets of packet saved in file */
    public long orig_len;       /* actual length of packet */

    public PacketHeader(long ts_sec, long ts_usec, long incl_len, long orig_len) {
        this.ts_sec = ts_sec;
        this.ts_usec = ts_usec;
        this.incl_len = incl_len;
        this.orig_len = orig_len;
    }

    public byte[] getBytes() {
        byte[] arr = new byte[16];
        int i = 0;
        arr[i++] = (byte) (ts_sec);
        arr[i++] = (byte) (ts_sec >>> 8);
        arr[i++] = (byte) (ts_sec >>> 16);
        arr[i++] = (byte) (ts_sec >>> 24);
        arr[i++] = (byte) ts_usec;
        arr[i++] = (byte) (ts_usec >>> 8);
        arr[i++] = (byte) (ts_usec >>> 16);
        arr[i++] = (byte) (ts_usec >>> 24);


        arr[i++] = (byte) incl_len;
        arr[i++] = (byte) (incl_len >>> 8);
        arr[i++] = (byte) (incl_len >>> 16);
        arr[i++] = (byte) (incl_len >>> 24);


        arr[i++] = (byte) orig_len;
        arr[i++] = (byte) (orig_len >>> 8);
        arr[i++] = (byte) (orig_len >>> 16);
        arr[i] = (byte) (orig_len >>> 24);

        return arr;
    }


}
