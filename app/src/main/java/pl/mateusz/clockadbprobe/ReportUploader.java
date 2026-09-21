package pl.mateusz.clockadbprobe;

import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One-shot manual upload of the full report as JSON to the user's recon
 * endpoint. Only invoked from the SEND REPORT button after a confirmation
 * dialog; nothing else in the app performs network writes.
 */
public final class ReportUploader {

    public static final String ENDPOINT = "https://recon.robot.org.pl/";

    public static String upload(String reportText, String appVersion) throws Exception {
        JSONObject device = new JSONObject();
        device.put("manufacturer", str(Build.MANUFACTURER));
        device.put("brand", str(Build.BRAND));
        device.put("model", str(Build.MODEL));
        device.put("device", str(Build.DEVICE));
        device.put("product", str(Build.PRODUCT));
        device.put("hardware", str(Build.HARDWARE));
        device.put("board", str(Build.BOARD));
        device.put("fingerprint", str(Build.FINGERPRINT));
        device.put("androidRelease", str(Build.VERSION.RELEASE));
        device.put("androidSdk", Build.VERSION.SDK_INT);

        JSONObject json = new JSONObject();
        json.put("app", "Smart Clock 2 Tools");
        json.put("appVersion", appVersion);
        json.put("sentAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.ROOT).format(new java.util.Date()));
        json.put("ip", NetProbe.wifiIp != null ? NetProbe.wifiIp : JSONObject.NULL);
        json.put("deviceName", str(Build.MODEL));
        json.put("device", device);
        json.put("report", reportText);

        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "ClockAdbProbe/1.0");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (resp.length() > 500) resp = resp.substring(0, 500) + "…";
            return "HTTP " + code + (resp.isEmpty() ? " (empty body)" : " body: " + resp);
        } finally {
            c.disconnect();
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String readAll(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString().trim();
    }
}
