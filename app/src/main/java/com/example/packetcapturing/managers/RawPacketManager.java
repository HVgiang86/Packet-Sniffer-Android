package com.example.packetcapturing.managers;

import com.example.packetcapturing.model.RawPacket;

import java.util.ArrayList;
import java.util.List;

public class RawPacketManager {
    public List<RawPacket> packetList;
    private static RawPacketManager INSTANCE = null;
    private RawPacketManager(){
        packetList = new ArrayList<>();
    }

    public static RawPacketManager getInstance() {
        if (INSTANCE == null)
            INSTANCE = new RawPacketManager();

        return INSTANCE;
    }

    private boolean allowAdd = true;

    public void disableAdd() {
        allowAdd = false;
    }

    public void addRawPacket(RawPacket p) {
        if (allowAdd)
            packetList.add(p);
    }

}
