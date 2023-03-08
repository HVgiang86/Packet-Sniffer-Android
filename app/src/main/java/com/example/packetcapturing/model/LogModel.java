package com.example.packetcapturing.model;

public class LogModel {
    public int ipVersion;
    public String protocol;
    public String address;
    public int port;
    public byte[] data;
    public String hostName;
    public long timeVal;

    public LogModel(int ipVersion, String protocol, String address, int port, byte[] data, String hostName, long timeVal) {
        this.ipVersion = ipVersion;
        this.protocol = protocol;
        this.address = address;
        this.port = port;
        this.data = data;
        this.hostName = hostName;
        this.timeVal = timeVal;
    }


}
