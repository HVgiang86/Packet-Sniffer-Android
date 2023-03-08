package com.example.packetcapturing.net;

import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FileWriterThread implements Runnable{
    private static final String TAG = TCPInput.class.getSimpleName();
    private final ConcurrentLinkedQueue<Packet> packetQueue;
    private final Selector selector;

    public FileWriterThread(ConcurrentLinkedQueue<Packet> packetQueue, Selector selector) {
        this.packetQueue = packetQueue;
        this.selector = selector;
    }

    @Override
    public void run() {

    }
}
