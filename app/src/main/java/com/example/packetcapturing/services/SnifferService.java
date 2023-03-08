package com.example.packetcapturing.services;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import com.example.packetcapturing.R;
import com.example.packetcapturing.managers.RawPacketManager;
import com.example.packetcapturing.model.RawPacket;
import com.example.packetcapturing.net.ByteBufferPool;
import com.example.packetcapturing.net.FileWriterThread;
import com.example.packetcapturing.net.Packet;
import com.example.packetcapturing.net.TCPInput;
import com.example.packetcapturing.net.TCPOutput;
import com.example.packetcapturing.net.UDPInput;
import com.example.packetcapturing.net.UDPOutput;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SnifferService extends VpnService {

    public static final String STOP_SERVICE_ACTION = "com.example.packetcapturing.STOP_SERVICE";
    private static final String TAG = SnifferService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private static boolean isRunning = false;
    private ParcelFileDescriptor vpnInterface = null;    private final BroadcastReceiver stopServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STOP_SERVICE_ACTION.equals(intent.getAction())) {
                Log.d(TAG, "Broadcast received");
                stopService();

            }
        }
    };
    private PendingIntent pendingIntent;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ConcurrentLinkedQueue<Packet> packetQueue;
    private ExecutorService executorService;
    private Selector udpSelector;
    private Selector tcpSelector;
    private Selector packetSelector;
    public SnifferService() {
    }

    public static boolean isRunning() {
        return isRunning;
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(stopServiceReceiver, new IntentFilter(STOP_SERVICE_ACTION));
        isRunning = true;
        setupVPN();
        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            packetSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<>();
            packetQueue = new ConcurrentLinkedQueue<>();

            executorService = Executors.newFixedThreadPool(6);
            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector,packetQueue,packetSelector));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this,packetQueue,packetSelector));
            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(), deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
            executorService.submit(new FileWriterThread(packetQueue,packetSelector));

            Log.i(TAG, "Started");
        } catch (IOException e) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            cleanup();
        }
    }

    private void setupVPN() {
        if (vpnInterface == null) {
            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addRoute(VPN_ROUTE, 1);
            vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(pendingIntent).establish();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void stopService() {
        unregisterReceiver(stopServiceReceiver);
        stopSelf();
        stopForeground(true);
        onDestroy();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stop request called");
        super.onDestroy();
        isRunning = false;
        executorService.shutdownNow();
        cleanup();

        Log.i(TAG, "Stopped");
        Toast.makeText(this, "Stopped!", Toast.LENGTH_SHORT).show();
    }

    private void cleanup() {
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
    }

    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private final FileDescriptor vpnFileDescriptor;

        private final ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private final ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private final ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor, ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue, ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue, ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            Log.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;
                while (!Thread.interrupted()) {
                    if (dataSent) bufferToNetwork = ByteBufferPool.acquire();
                    else bufferToNetwork.clear();

                    // TODO: Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        bufferToNetwork.limit(readBytes);
                        bufferToNetwork.position(0);

                        Packet packet = new Packet(bufferToNetwork);
                        /*Log.d(TAG,"TO NET:");
                        IPPacket ipPacket = IPHelper.parse(bufferToNetwork.array());
                        Log.d(TAG, ipPacket.getPacketInfo() + "\nHOST NAME: " + ipPacket.getHostName());*/

                        byte[] protocolHeader;
                        if (packet.isTCP()) protocolHeader = packet.tcpHeader.getHeaderInBytes();
                        else protocolHeader = packet.udpHeader.getHeaderInBytes();
                        RawPacket rawPacket = new RawPacket(System.currentTimeMillis(), packet.ip4Header.getHeaderInBytes(), protocolHeader, packet.backingBuffer.array());
                        packet.backingBuffer.position(0);

                        RawPacketManager.getInstance().addRawPacket(rawPacket);

                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            Log.w(TAG, "Unknown packet type");
                            Log.w(TAG, packet.ip4Header.toString());
                            dataSent = false;
                        }
                    } else {
                        dataSent = false;
                    }

                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining()) vpnOutput.write(bufferFromNetwork);
                        dataReceived = true;
/*
                        Log.d(TAG,"FROM NET:");
                        IPPacket ipPacket = IPHelper.parse(bufferFromNetwork.array());
                        Log.d(TAG, ipPacket.getPacketInfo() + "\nHOST NAME: " + ipPacket.getHostName());*/
                        ByteBufferPool.release(bufferFromNetwork);
                        byte[] dataArray = bufferToNetwork.array();
                        //RawPacketManager.getInstance().addRawPacket(new RawPacket(System.currentTimeMillis(),dataArray,dataArray.length));

                    } else {
                        dataReceived = false;
                    }

                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataReceived) Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "Stopping");
            } catch (IOException e) {
                Log.w(TAG, e.toString(), e);
            } finally {
                closeResources(vpnInput, vpnOutput);
            }
        }
    }


}