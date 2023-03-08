package com.example.packetcapturing.managers;

import com.example.packetcapturing.model.LogModel;

import java.util.ArrayList;
import java.util.List;

public class SnifferLogManager {
    public List<LogModel> logList;
    private static SnifferLogManager INSTANCE;

    private SnifferLogManager() {
        logList = new ArrayList<>();
    }

    public static SnifferLogManager getInstance() {
        if (INSTANCE == null)
            INSTANCE = new SnifferLogManager();

        return INSTANCE;
    }

    public void addLog(LogModel log) {
        logList.add(log);
    }

}
