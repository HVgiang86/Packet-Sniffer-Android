package com.example.packetcapturing.net;

import androidx.annotation.NonNull;

import com.example.packetcapturing.managers.SnifferLogManager;
import com.example.packetcapturing.model.LogModel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class Packet implements Cloneable{
    public static final int IP4_HEADER_SIZE = 20;
    public static final int TCP_HEADER_SIZE = 20;
    public static final int UDP_HEADER_SIZE = 8;

    public IP4Header ip4Header;
    public TCPHeader tcpHeader;
    public UDPHeader udpHeader;
    public ByteBuffer backingBuffer;

    private boolean isTCP;
    private boolean isUDP;

    public Packet(ByteBuffer buffer) throws UnknownHostException {
        this.ip4Header = new IP4Header(buffer);
        if (this.ip4Header.protocol == IP4Header.TransportProtocol.TCP) {
            this.tcpHeader = new TCPHeader(buffer);
            this.isTCP = true;
        } else if (ip4Header.protocol == IP4Header.TransportProtocol.UDP) {
            this.udpHeader = new UDPHeader(buffer);
            this.isUDP = true;
        }
        this.backingBuffer = buffer;
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Packet{");
        sb.append("ip4Header=").append(ip4Header);
        if (isTCP) sb.append(", tcpHeader=").append(tcpHeader);
        else if (isUDP) sb.append(", udpHeader=").append(udpHeader);
        sb.append(", payloadSize=").append(backingBuffer.limit() - backingBuffer.position());
        sb.append('}');
        SnifferLogManager.getInstance().addLog(getLog());
        return sb.toString();
    }

    public LogModel getLog() {
        if (isTCP) {
            return new LogModel(4,"TCP", ip4Header.sourceAddress.toString(), tcpHeader.sourcePort, backingBuffer.array(), "...", System.currentTimeMillis());
        }
        else {
            return new LogModel(4,"UDP", ip4Header.sourceAddress.toString(), udpHeader.sourcePort, backingBuffer.array(), "...", System.currentTimeMillis());
        }
    }

    public boolean isTCP() {
        return isTCP;
    }

    public boolean isUDP() {
        return isUDP;
    }

    public void swapSourceAndDestination() {
        InetAddress newSourceAddress = ip4Header.destinationAddress;
        ip4Header.destinationAddress = ip4Header.sourceAddress;
        ip4Header.sourceAddress = newSourceAddress;

        if (isUDP) {
            int newSourcePort = udpHeader.destinationPort;
            udpHeader.destinationPort = udpHeader.sourcePort;
            udpHeader.sourcePort = newSourcePort;
        } else if (isTCP) {
            int newSourcePort = tcpHeader.destinationPort;
            tcpHeader.destinationPort = tcpHeader.sourcePort;
            tcpHeader.sourcePort = newSourcePort;
        }
    }

    public void updateTCPBuffer(ByteBuffer buffer, byte flags, long sequenceNum, long ackNum, int payloadSize) {
        buffer.position(0);
        fillHeader(buffer);
        backingBuffer = buffer;

        tcpHeader.flags = flags;
        backingBuffer.put(IP4_HEADER_SIZE + 13, flags);

        tcpHeader.sequenceNumber = sequenceNum;
        backingBuffer.putInt(IP4_HEADER_SIZE + 4, (int) sequenceNum);

        tcpHeader.acknowledgementNumber = ackNum;
        backingBuffer.putInt(IP4_HEADER_SIZE + 8, (int) ackNum);

        // Reset header size, since we don't need options
        byte dataOffset = (byte) (TCP_HEADER_SIZE << 2);
        tcpHeader.dataOffsetAndReserved = dataOffset;
        backingBuffer.put(IP4_HEADER_SIZE + 12, dataOffset);

        updateTCPChecksum(payloadSize);

        int ip4TotalLength = IP4_HEADER_SIZE + TCP_HEADER_SIZE + payloadSize;
        backingBuffer.putShort(2, (short) ip4TotalLength);
        ip4Header.totalLength = ip4TotalLength;

        updateIP4Checksum();
    }

    public void updateUDPBuffer(ByteBuffer buffer, int payloadSize) {
        buffer.position(0);
        fillHeader(buffer);
        backingBuffer = buffer;

        int udpTotalLength = UDP_HEADER_SIZE + payloadSize;
        backingBuffer.putShort(IP4_HEADER_SIZE + 4, (short) udpTotalLength);
        udpHeader.length = udpTotalLength;

        // Disable UDP checksum validation
        backingBuffer.putShort(IP4_HEADER_SIZE + 6, (short) 0);
        udpHeader.checksum = 0;

        int ip4TotalLength = IP4_HEADER_SIZE + udpTotalLength;
        backingBuffer.putShort(2, (short) ip4TotalLength);
        ip4Header.totalLength = ip4TotalLength;

        updateIP4Checksum();
    }

    private void updateIP4Checksum() {
        ByteBuffer buffer = backingBuffer.duplicate();
        buffer.position(0);

        // Clear previous checksum
        buffer.putShort(10, (short) 0);

        int ipLength = ip4Header.headerLength;
        int sum = 0;
        while (ipLength > 0) {
            sum += BitUtils.getUnsignedShort(buffer.getShort());
            ipLength -= 2;
        }
        while (sum >> 16 > 0) sum = (sum & 0xFFFF) + (sum >> 16);

        sum = ~sum;
        ip4Header.headerChecksum = sum;
        backingBuffer.putShort(10, (short) sum);
    }

    private void updateTCPChecksum(int payloadSize) {
        int sum = 0;
        int tcpLength = TCP_HEADER_SIZE + payloadSize;

        // Calculate pseudo-header checksum
        ByteBuffer buffer = ByteBuffer.wrap(ip4Header.sourceAddress.getAddress());
        sum = BitUtils.getUnsignedShort(buffer.getShort()) + BitUtils.getUnsignedShort(buffer.getShort());

        buffer = ByteBuffer.wrap(ip4Header.destinationAddress.getAddress());
        sum += BitUtils.getUnsignedShort(buffer.getShort()) + BitUtils.getUnsignedShort(buffer.getShort());

        sum += IP4Header.TransportProtocol.TCP.getNumber() + tcpLength;

        buffer = backingBuffer.duplicate();
        // Clear previous checksum
        buffer.putShort(IP4_HEADER_SIZE + 16, (short) 0);

        // Calculate TCP segment checksum
        buffer.position(IP4_HEADER_SIZE);
        while (tcpLength > 1) {
            sum += BitUtils.getUnsignedShort(buffer.getShort());
            tcpLength -= 2;
        }
        if (tcpLength > 0) sum += BitUtils.getUnsignedByte(buffer.get()) << 8;

        while (sum >> 16 > 0) sum = (sum & 0xFFFF) + (sum >> 16);

        sum = ~sum;
        tcpHeader.checksum = sum;
        backingBuffer.putShort(IP4_HEADER_SIZE + 16, (short) sum);
    }

    private void fillHeader(ByteBuffer buffer) {
        ip4Header.fillHeader(buffer);
        if (isUDP) udpHeader.fillHeader(buffer);
        else if (isTCP) tcpHeader.fillHeader(buffer);
    }

    public static class IP4Header {
        public byte version;
        byte versionAndIHL;
        public byte IHL;
        public int headerLength;
        public short typeOfService;
        public int totalLength;

        public int identificationAndFlagsAndFragmentOffset;

        public short TTL;
        public TransportProtocol protocol;
        public int headerChecksum;
        public InetAddress sourceAddress;
        public InetAddress destinationAddress;
        public int optionsAndPadding;
        private final short protocolNum;
        byte[] sourceBytes;
        byte[] destinationBytes;

        private IP4Header(ByteBuffer buffer) throws UnknownHostException {
            versionAndIHL = buffer.get();
            this.version = (byte) (versionAndIHL >> 4);
            this.IHL = (byte) (versionAndIHL & 0x0F);
            this.headerLength = this.IHL << 2;

            this.typeOfService = BitUtils.getUnsignedByte(buffer.get());
            this.totalLength = BitUtils.getUnsignedShort(buffer.getShort());

            this.identificationAndFlagsAndFragmentOffset = buffer.getInt();

            this.TTL = BitUtils.getUnsignedByte(buffer.get());
            this.protocolNum = BitUtils.getUnsignedByte(buffer.get());
            this.protocol = TransportProtocol.numberToEnum(protocolNum);
            this.headerChecksum = BitUtils.getUnsignedShort(buffer.getShort());

            sourceBytes = new byte[4];
            buffer.get(sourceBytes, 0, 4);
            this.sourceAddress = InetAddress.getByAddress(sourceBytes);

            destinationBytes = new byte[4];
            buffer.get(destinationBytes, 0, 4);
            this.destinationAddress = InetAddress.getByAddress(destinationBytes);

            //this.optionsAndPadding = buffer.getInt();
        }

        public byte[] getHeaderInBytes() {
            int i = 0;
            byte[] header = new byte[20];
            header[i++] = versionAndIHL;
            header[i++] = (byte) typeOfService;
            header[i++] = (byte) totalLength;
            header[i++] = (byte) (totalLength >>> 8);
            header[i++] = (byte) identificationAndFlagsAndFragmentOffset;
            header[i++] = (byte) (identificationAndFlagsAndFragmentOffset >>> 8);
            header[i++] = (byte) (identificationAndFlagsAndFragmentOffset >>> 16);
            header[i++] = (byte) (identificationAndFlagsAndFragmentOffset >>> 24);
            header[i++] = (byte) TTL;
            header[i++] = (byte) protocolNum;
            header[i++] = (byte) headerChecksum;
            header[i++] = (byte) (headerChecksum >>> 8);
            header[i++] = sourceBytes[0];
            header[i++] = sourceBytes[1];
            header[i++] = sourceBytes[2];
            header[i++] = sourceBytes[3];
            header[i++] = destinationBytes[0];
            header[i++] = destinationBytes[1];
            header[i++] = destinationBytes[2];
            header[i++] = destinationBytes[3];

            return header;
        }

        public void fillHeader(ByteBuffer buffer) {
            buffer.put((byte) (this.version << 4 | this.IHL));
            buffer.put((byte) this.typeOfService);
            buffer.putShort((short) this.totalLength);

            buffer.putInt(this.identificationAndFlagsAndFragmentOffset);

            buffer.put((byte) this.TTL);
            buffer.put((byte) this.protocol.getNumber());
            buffer.putShort((short) this.headerChecksum);

            buffer.put(this.sourceAddress.getAddress());
            buffer.put(this.destinationAddress.getAddress());
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("IP4Header{");
            sb.append("version=").append(version);
            sb.append(", IHL=").append(IHL);
            sb.append(", typeOfService=").append(typeOfService);
            sb.append(", totalLength=").append(totalLength);
            sb.append(", identificationAndFlagsAndFragmentOffset=").append(identificationAndFlagsAndFragmentOffset);
            sb.append(", TTL=").append(TTL);
            sb.append(", protocol=").append(protocolNum).append(":").append(protocol);
            sb.append(", headerChecksum=").append(headerChecksum);
            sb.append(", sourceAddress=").append(sourceAddress.getHostAddress());
            sb.append(", destinationAddress=").append(destinationAddress.getHostAddress());
            sb.append('}');
            return sb.toString();
        }

        private enum TransportProtocol {
            TCP(6), UDP(17), Other(0xFF);

            private final int protocolNumber;

            TransportProtocol(int protocolNumber) {
                this.protocolNumber = protocolNumber;
            }

            private static TransportProtocol numberToEnum(int protocolNumber) {
                if (protocolNumber == 6) return TCP;
                else if (protocolNumber == 17) return UDP;
                else return Other;
            }

            public int getNumber() {
                return this.protocolNumber;
            }
        }
    }

    public static class TCPHeader {
        public static final int FIN = 0x01;
        public static final int SYN = 0x02;
        public static final int RST = 0x04;
        public static final int PSH = 0x08;
        public static final int ACK = 0x10;
        public static final int URG = 0x20;

        public int sourcePort;
        public int destinationPort;

        public long sequenceNumber;
        public long acknowledgementNumber;

        public byte dataOffsetAndReserved;
        public int headerLength;
        public byte flags;
        public int window;

        public int checksum;
        public int urgentPointer;

        public byte[] optionsAndPadding;

        private TCPHeader(ByteBuffer buffer) {
            this.sourcePort = BitUtils.getUnsignedShort(buffer.getShort());
            this.destinationPort = BitUtils.getUnsignedShort(buffer.getShort());

            this.sequenceNumber = BitUtils.getUnsignedInt(buffer.getInt());
            this.acknowledgementNumber = BitUtils.getUnsignedInt(buffer.getInt());

            this.dataOffsetAndReserved = buffer.get();
            this.headerLength = (this.dataOffsetAndReserved & 0xF0) >> 2;
            this.flags = buffer.get();
            this.window = BitUtils.getUnsignedShort(buffer.getShort());

            this.checksum = BitUtils.getUnsignedShort(buffer.getShort());
            this.urgentPointer = BitUtils.getUnsignedShort(buffer.getShort());

            int optionsLength = this.headerLength - TCP_HEADER_SIZE;
            if (optionsLength > 0) {
                optionsAndPadding = new byte[optionsLength];
                buffer.get(optionsAndPadding, 0, optionsLength);
            }
        }

        public boolean isFIN() {
            return (flags & FIN) == FIN;
        }

        public boolean isSYN() {
            return (flags & SYN) == SYN;
        }

        public boolean isRST() {
            return (flags & RST) == RST;
        }

        public boolean isPSH() {
            return (flags & PSH) == PSH;
        }

        public boolean isACK() {
            return (flags & ACK) == ACK;
        }

        public boolean isURG() {
            return (flags & URG) == URG;
        }

        private void fillHeader(ByteBuffer buffer) {
            buffer.putShort((short) sourcePort);
            buffer.putShort((short) destinationPort);

            buffer.putInt((int) sequenceNumber);
            buffer.putInt((int) acknowledgementNumber);

            buffer.put(dataOffsetAndReserved);
            buffer.put(flags);
            buffer.putShort((short) window);

            buffer.putShort((short) checksum);
            buffer.putShort((short) urgentPointer);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("TCPHeader{");
            sb.append("sourcePort=").append(sourcePort);
            sb.append(", destinationPort=").append(destinationPort);
            sb.append(", sequenceNumber=").append(sequenceNumber);
            sb.append(", acknowledgementNumber=").append(acknowledgementNumber);
            sb.append(", headerLength=").append(headerLength);
            sb.append(", window=").append(window);
            sb.append(", checksum=").append(checksum);
            sb.append(", flags=");
            if (isFIN()) sb.append(" FIN");
            if (isSYN()) sb.append(" SYN");
            if (isRST()) sb.append(" RST");
            if (isPSH()) sb.append(" PSH");
            if (isACK()) sb.append(" ACK");
            if (isURG()) sb.append(" URG");
            sb.append('}');
            return sb.toString();
        }

        public byte[] getHeaderInBytes() {
            int len = optionsAndPadding.length + 20;
            byte[] header = new byte[len];
            int i = 0;
            header[i++] = (byte) sourcePort;
            header[i++] = (byte) (sourcePort>>>8);
            header[i++] = (byte) destinationPort;
            header[i++] = (byte) (destinationPort>>>8);
            header[i++] = (byte) (sequenceNumber);
            header[i++] = (byte) (sequenceNumber>>>8);
            header[i++] = (byte) (sequenceNumber>>>16);
            header[i++] = (byte) (sequenceNumber>>>24);
            header[i++] = (byte) (acknowledgementNumber);
            header[i++] = (byte) (acknowledgementNumber>>>8);
            header[i++] = (byte) (acknowledgementNumber>>>16);
            header[i++] = (byte) (acknowledgementNumber>>>24);
            header[i++] = dataOffsetAndReserved;
            header[i++] = flags;
            header[i++] = (byte) window;
            header[i++] = (byte) (flags>>>8);
            header[i++] = (byte) checksum;
            header[i++] = (byte) (checksum>>>8);
            header[i++] = (byte) (urgentPointer);
            header[i++] = (byte) (urgentPointer>>>8);
            for (int j = 0; j < optionsAndPadding.length; ++j) {
                header[i++] = optionsAndPadding[j];
            }
            return header;
        }
    }

    public static class UDPHeader {
        public int sourcePort;
        public int destinationPort;

        public int length;
        public int checksum;

        private UDPHeader(ByteBuffer buffer) {
            this.sourcePort = BitUtils.getUnsignedShort(buffer.getShort());
            this.destinationPort = BitUtils.getUnsignedShort(buffer.getShort());

            this.length = BitUtils.getUnsignedShort(buffer.getShort());
            this.checksum = BitUtils.getUnsignedShort(buffer.getShort());
        }

        private void fillHeader(ByteBuffer buffer) {
            buffer.putShort((short) this.sourcePort);
            buffer.putShort((short) this.destinationPort);

            buffer.putShort((short) this.length);
            buffer.putShort((short) this.checksum);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("UDPHeader{");
            sb.append("sourcePort=").append(sourcePort);
            sb.append(", destinationPort=").append(destinationPort);
            sb.append(", length=").append(length);
            sb.append(", checksum=").append(checksum);
            sb.append('}');
            return sb.toString();
        }

        public byte[] getHeaderInBytes() {
            byte[] header = new byte[8];
            int i =0;
            header[i++] = (byte) sourcePort;
            header[i++] = (byte) (sourcePort >>> 8);
            header[i++] = (byte) (destinationPort);
            header[i++] = (byte) (destinationPort >>> 8);
            header[i++] = (byte) (length);
            header[i++] = (byte) (length >>> 8);
            header[i++] = (byte) (checksum);
            header[i++] = (byte) (checksum >>> 8);
            return header;
        }
    }

    private static class BitUtils {
        private static short getUnsignedByte(byte value) {
            return (short) (value & 0xFF);
        }

        private static int getUnsignedShort(short value) {
            return value & 0xFFFF;
        }

        private static long getUnsignedInt(int value) {
            return value & 0xFFFFFFFFL;
        }
    }

    @NonNull
    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
