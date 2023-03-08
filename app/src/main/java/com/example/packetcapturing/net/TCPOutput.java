package com.example.packetcapturing.net;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.example.packetcapturing.net.TCB.*;
import com.example.packetcapturing.net.Packet.*;
import com.example.packetcapturing.services.SnifferService;

public class TCPOutput implements Runnable {
    private static final String TAG = TCPOutput.class.getSimpleName();

    private final SnifferService vpnService;
    private final ConcurrentLinkedQueue<Packet> inputQueue;
    private final ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private final Selector byteBufferSelector;
    private final ConcurrentLinkedQueue<Packet> packetQueue;
    private final Selector packetSelector;

    private final Random random = new Random();

    public TCPOutput(ConcurrentLinkedQueue<Packet> inputQueue, ConcurrentLinkedQueue<ByteBuffer> outputQueue, Selector byteBufferSelector, SnifferService vpnService, ConcurrentLinkedQueue<Packet> packetQueue, Selector packetSelector) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.byteBufferSelector = byteBufferSelector;
        this.vpnService = vpnService;
        this.packetQueue = packetQueue;
        this.packetSelector = packetSelector;
    }

    @Override
    public void run() {
        Log.i(TAG, "Started");
        try {

            Thread currentThread = Thread.currentThread();
            while (true) {
                Packet currentPacket;
                // TODO: Block when not connected
                do {
                    currentPacket = inputQueue.poll();
                    if (currentPacket != null) break;
                    Thread.sleep(10);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted()) break;

                ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                currentPacket.backingBuffer = null;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;

                Packet.TCPHeader tcpHeader = currentPacket.tcpHeader;
                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                TCB tcb = TCB.getTCB(ipAndPort);
                if (tcb == null)
                    initializeConnection(ipAndPort, destinationAddress, destinationPort, currentPacket, tcpHeader, responseBuffer);
                else if (tcpHeader.isSYN()) processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isRST()) closeCleanly(tcb, responseBuffer);
                else if (tcpHeader.isFIN()) processFIN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isACK())
                    processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);

                // XXX: cleanup later
                if (responseBuffer.position() == 0) ByteBufferPool.release(responseBuffer);
                ByteBufferPool.release(payloadBuffer);


            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (IOException e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            TCB.closeAll();
        }
    }

    private void initializeConnection(String ipAndPort, InetAddress destinationAddress, int destinationPort, Packet currentPacket, TCPHeader tcpHeader, ByteBuffer responseBuffer) throws IOException {
        currentPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN()) {
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            vpnService.protect(outputChannel.socket());

            TCB tcb = new TCB(ipAndPort, random.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1, tcpHeader.acknowledgementNumber, outputChannel, currentPacket);
            TCB.putTCB(ipAndPort, tcb);

            try {
                outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                if (outputChannel.finishConnect()) {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    // TODO: Set MSS for receiving larger packets from the device
                    currentPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    tcb.mySequenceNum++; // SYN counts as a byte
                } else {
                    tcb.status = TCBStatus.SYN_SENT;
                    byteBufferSelector.wakeup();
                    tcb.selectionKey = outputChannel.register(byteBufferSelector, SelectionKey.OP_CONNECT, tcb);
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection error: " + ipAndPort, e);
                currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        } else {
            currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcpHeader.sequenceNumber + 1, 0);
        }
        outputQueue.offer(responseBuffer);
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            if (tcb.status == TCBStatus.SYN_SENT) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            if (tcb.waitingForNetworkData) {
                tcb.status = TCBStatus.CLOSE_WAIT;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            } else {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            }
            Log.d(TAG,referencePacket.toString());
        }
        outputQueue.offer(responseBuffer);
    }

    private void processACK(TCB tcb, Packet.TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException {
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();

        synchronized (tcb) {
            SocketChannel outputChannel = tcb.channel;
            if (tcb.status == TCB.TCBStatus.SYN_RECEIVED) {
                tcb.status = TCB.TCBStatus.ESTABLISHED;

                byteBufferSelector.wakeup();
                tcb.selectionKey = outputChannel.register(byteBufferSelector, SelectionKey.OP_READ, tcb);
                tcb.waitingForNetworkData = true;
            } else if (tcb.status == TCB.TCBStatus.LAST_ACK) {
                closeCleanly(tcb, responseBuffer);
                return;
            }

            if (payloadSize == 0) return; // Empty ACK, ignore

            if (!tcb.waitingForNetworkData) {
                byteBufferSelector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            // Forward to remote server
            try {
                while (payloadBuffer.hasRemaining()) outputChannel.write(payloadBuffer);
            } catch (IOException e) {
                Log.e(TAG, "Network write error: " + tcb.ipAndPort, e);
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            Packet referencePacket = tcb.referencePacket;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            Log.d(TAG,referencePacket.toString());
        }
        outputQueue.offer(responseBuffer);
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer) {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);
        outputQueue.offer(buffer);
        TCB.closeTCB(tcb);
    }

    private void closeCleanly(TCB tcb, ByteBuffer buffer) {
        ByteBufferPool.release(buffer);
        TCB.closeTCB(tcb);
    }
}
