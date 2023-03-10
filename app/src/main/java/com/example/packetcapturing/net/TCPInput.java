package com.example.packetcapturing.net;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TCPInput implements Runnable {
    private static final String TAG = TCPInput.class.getSimpleName();
    private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    private final ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private final ConcurrentLinkedQueue<Packet> packetQueue;
    private final Selector byteBufferSelector;
    private final Selector packetSelector;

    public TCPInput(ConcurrentLinkedQueue<ByteBuffer> outputQueue, Selector byteBufferSelector, ConcurrentLinkedQueue<Packet> packetQueue, Selector packetSelector) {
        this.outputQueue = outputQueue;
        this.byteBufferSelector = byteBufferSelector;
        this.packetQueue = packetQueue;
        this.packetSelector = packetSelector;
    }

    @Override
    public void run() {
        try {
            Log.d(TAG, "Started");
            while (!Thread.interrupted()) {
                int readyChannels = byteBufferSelector.select();

                if (readyChannels == 0) {
                    Thread.sleep(10);
                    continue;
                }

                Set<SelectionKey> keys = byteBufferSelector.selectedKeys();
                Iterator<SelectionKey> keyIterator = keys.iterator();

                while (keyIterator.hasNext() && !Thread.interrupted()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        if (key.isConnectable()) processConnect(key, keyIterator);
                        else if (key.isReadable()) processInput(key, keyIterator);
                    }
                }
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (IOException e) {
            Log.w(TAG, e.toString(), e);
        }
    }

    private void processConnect(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        TCB tcb = (TCB) key.attachment();
        Packet referencePacket = tcb.referencePacket;
        try {
            if (tcb.channel.finishConnect()) {
                keyIterator.remove();
                tcb.status = TCB.TCBStatus.SYN_RECEIVED;

                // TODO: Set MSS for receiving larger packets from the device
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                Log.d(TAG,referencePacket.toString());

                Packet clone = (Packet) referencePacket.clone();
                outputQueue.offer(responseBuffer);
                packetQueue.offer(clone);

                tcb.mySequenceNum++; // SYN counts as a byte
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection error: " + tcb.ipAndPort, e);
            ByteBuffer responseBuffer = ByteBufferPool.acquire();
            referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
            outputQueue.offer(responseBuffer);
            TCB.closeTCB(tcb);
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
    }

    private void processInput(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        keyIterator.remove();
        ByteBuffer receiveBuffer = ByteBufferPool.acquire();
        // Leave space for the header
        receiveBuffer.position(HEADER_SIZE);

        TCB tcb = (TCB) key.attachment();
        synchronized (tcb) {
            Packet referencePacket = tcb.referencePacket;
            SocketChannel inputChannel = (SocketChannel) key.channel();
            int readBytes;
            try {
                readBytes = inputChannel.read(receiveBuffer);
            } catch (IOException e) {
                Log.e(TAG, "Network read error: " + tcb.ipAndPort, e);
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                outputQueue.offer(receiveBuffer);
                TCB.closeTCB(tcb);
                return;
            }

            if (readBytes == -1) {
                // End of stream, stop waiting until we push more data
                key.interestOps(0);
                tcb.waitingForNetworkData = false;

                if (tcb.status != TCB.TCBStatus.CLOSE_WAIT) {
                    ByteBufferPool.release(receiveBuffer);
                    return;
                }

                tcb.status = TCB.TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.FIN, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            } else {
                // XXX: We should ideally be splitting segments by MTU/MSS, but this seems to work without
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes);
                tcb.mySequenceNum += readBytes; // Next sequence number
                receiveBuffer.position(HEADER_SIZE + readBytes);

            }
            Log.d(TAG,referencePacket.toString());
            try {
                Packet clone = (Packet) referencePacket.clone();
                packetQueue.offer(clone);
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }

        }
        outputQueue.offer(receiveBuffer);
    }

}
