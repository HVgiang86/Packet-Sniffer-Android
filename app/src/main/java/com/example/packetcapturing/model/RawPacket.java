package com.example.packetcapturing.model;

public class RawPacket {
    private long timeVal;
    private byte[] data;
    private byte[] ip4Header;
    private byte[] protocolHeader;

    public byte[] getProtocolHeader() {
        return protocolHeader;
    }

    public void setProtocolHeader(byte[] protocolHeader) {
        this.protocolHeader = protocolHeader;
    }

    public RawPacket(long timeVal, byte[] ip4Header, byte[] protocolHeader, byte[] data) {
        this.timeVal = timeVal;
        this.data = data;
        this.ip4Header = ip4Header;
        this.protocolHeader = protocolHeader;
    }

    public byte[] getIp4Header() {
        return ip4Header;
    }

    public void setIp4Header(byte[] ip4Header) {
        this.ip4Header = ip4Header;
    }

    public long getTimeVal() {
        return timeVal;
    }

    public void setTimeVal(long timeVal) {
        this.timeVal = timeVal;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

}
