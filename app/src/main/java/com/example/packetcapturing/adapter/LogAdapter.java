package com.example.packetcapturing.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.packetcapturing.R;
import com.example.packetcapturing.managers.SnifferLogManager;
import com.example.packetcapturing.model.LogModel;

import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {
    List<LogModel> logList;
    private final Context context;

    public LogAdapter(Context context) {
        this.context = context;
        logList = SnifferLogManager.getInstance().logList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.item_view, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public int getItemCount() {
        return logList.size();
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LogModel model = logList.get(position);
        String address = model.address;
        int port = model.port;
        String time = "" + System.currentTimeMillis();
        String protocol = model.protocol;
        String hostName = model.hostName;
        holder.addressTV.setText(address);
        holder.idTV.setText(Integer.toString(position+1));
        holder.hostnameTV.setText(hostName);
        holder.portTV.setText(Integer.toString(port));
        holder.timeTV.setText(time);
        holder.protocolTV.setText(protocol);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView idTV;
        public TextView addressTV;
        public TextView portTV;
        public TextView timeTV;
        public TextView protocolTV;
        public TextView hostnameTV;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            idTV = itemView.findViewById(R.id.id_tv);
            addressTV = itemView.findViewById(R.id.ip_tv);
            portTV = itemView.findViewById(R.id.port_tv);
            timeTV = itemView.findViewById(R.id.time_tv);
            protocolTV = itemView.findViewById(R.id.protocol_tv);
            hostnameTV = itemView.findViewById(R.id.host_name_tv);
        }
    }
}
