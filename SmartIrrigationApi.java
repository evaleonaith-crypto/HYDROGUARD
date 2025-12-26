package com.example.hydro_guard;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Smart Irrigation AI API Client
 * FIXED & STABLE
 */
public class SmartIrrigationApi {

    // 🔴 WAJIB GANTI JIKA BERUBAH
    public static final String AI_URL =
            "https://epaa-smart-irrigation-api.hf.space/api/predict";

    public static boolean isConfigured() {
        return AI_URL != null && AI_URL.startsWith("http");
    }

    // ===============================
    // RESULT MODEL
    // ===============================
    public static class PredictResult {
        public int pumpStatus;
        public String pumpLabel;
        public double probabilityOn;
        public int httpCode;
        public String usedUrl;
        public String usedFormat = "json";
    }

    // ===============================
    // CALLBACK
    // ===============================
    public interface PredictCallback {
        void onSuccess(PredictResult result);
        void onError(Exception e);
    }

    // ===============================
    // MAIN FUNCTION
    // ===============================
    public static void predict(
            double humidity,
            int rainfall,
            int sunlight,
            double soilMoisture,
            PredictCallback callback
    ) {

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(AI_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // ==========================
                // REQUEST BODY
                // ==========================
                JSONObject body = new JSONObject();
                body.put("Humidity", humidity);
                body.put("Rainfall", rainfall);
                body.put("Sunlight", sunlight);
                body.put("Soil_Moisture", soilMoisture);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes());
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                JSONObject json = new JSONObject(sb.toString());

                PredictResult result = new PredictResult();
                result.httpCode = code;
                result.usedUrl = AI_URL;

                result.pumpStatus = json.optInt("pump_status", 0);
                result.pumpLabel = json.optString("pump_label", "-");
                result.probabilityOn = json.optDouble("probability_on", 0);

                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onSuccess(result)
                );

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError(e)
                );
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
