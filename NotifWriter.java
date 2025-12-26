package com.example.hydro_guard;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

public class NotifWriter {


    public static void pushNotif(String scopeId, String message, String level, String type, String refId) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("notifications")
                .child(scopeId)
                .push(); // PENTING: push() agar history tidak saling menimpa

        Map<String, Object> data = new HashMap<>();
        data.put("message", message);
        data.put("level", level);          // INFO/WARN/ERROR
        data.put("type", type);            // IN / ACC / REJECT
        data.put("refId", refId);          // requestId (opsional)
        data.put("ts", ServerValue.TIMESTAMP); // waktu server (lebih akurat)

        ref.setValue(data);
    }
}
