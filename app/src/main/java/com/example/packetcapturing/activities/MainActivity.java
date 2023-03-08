package com.example.packetcapturing.activities;


import static com.example.packetcapturing.services.SnifferService.STOP_SERVICE_ACTION;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.example.packetcapturing.R;
import com.example.packetcapturing.adapter.LogAdapter;
import com.example.packetcapturing.managers.RawPacketManager;
import com.example.packetcapturing.model.RawPacket;
import com.example.packetcapturing.pcaputils.PcapFileHeader;
import com.example.packetcapturing.pcaputils.PcapRecord;
import com.example.packetcapturing.services.SnifferService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 0x0F;
    Intent serviceIntent;
    RecyclerView rv;
    LogAdapter adapter;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //ask for external storage permission
        if (shouldAskPermissions()) {
            askPermissions();
        }

        serviceIntent = new Intent(this, SnifferService.class);
        rv = findViewById(R.id.recycler_view);

        adapter = new LogAdapter(this);
        rv.setAdapter(adapter);

    }

    //request external memory permission

    private boolean shouldAskPermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * This function request for important permissions, if user accept, application will work correctly
     */

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void askPermissions() {
        String[] permissions = {"android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"};
        int requestCode = 200;
        requestPermissions(permissions, requestCode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public void startVPN(View v) {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    public void stopVPN(View v) {
        Intent intent = new Intent(STOP_SERVICE_ACTION);
        sendBroadcast(intent);
    }

    public void updateLog(View v) {
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startService(serviceIntent);
        }
    }

    public void writeFile(View v) {
        ((Runnable) () -> {
            RawPacketManager.getInstance().disableAdd();

            List<RawPacket> list = RawPacketManager.getInstance().packetList;

            String path = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "Sniffer";
            Log.d("Sniffer", path);

            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            Date date = new Date(System.currentTimeMillis());
            String fileName = "Pcap_file_" + date.getHours() + "_" + date.getMinutes() + "_" + date.getSeconds() + "_" + date.getDate() + "_" + (date.getMonth() + 1) + "_" + date.getYear() + ".pcap";
            File file = new File(dir, fileName);

            try {
                if (!file.isFile() || !file.exists()) file.createNewFile();

                FileOutputStream fos = new FileOutputStream(file);
                PcapFileHeader header = new PcapFileHeader();
                header.writeToStream(fos);

                for (RawPacket packet : list) {
                    PcapRecord record = new PcapRecord(packet.getTimeVal(), packet.getData(), packet.getIp4Header(), packet.getProtocolHeader());
                    record.writeToStream(fos);
                }

                fos.close();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }).run();
    }
}