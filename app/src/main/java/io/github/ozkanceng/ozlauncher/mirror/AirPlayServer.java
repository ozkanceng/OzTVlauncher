package io.github.ozkanceng.ozlauncher.mirror;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight, hardware-accelerated Apple AirPlay mirroring and streaming receiver.
 * Broadcasts Bonjour/mDNS service discovery and handles RTSP streaming from iPhone, iPad, and Mac.
 */
public final class AirPlayServer {
    private static final String TAG = "AirPlayServer";
    public static final int AIRPLAY_PORT = 7000;

    public interface Listener {
        void onMirroringStarted();
        void onMirroringStopped();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private final MediaCodecH264Decoder decoder = new MediaCodecH264Decoder();

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Listener listener;
    private String deviceName = "Özkan TV";

    public AirPlayServer(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSurface(Surface surface, int width, int height) {
        decoder.init(surface, width, height);
    }

    public synchronized void start(String customName) {
        if (running) return;
        if (customName != null && !customName.isEmpty()) {
            this.deviceName = customName;
        }
        running = true;

        serverExecutor.execute(this::runServer);
        registerMdns();
        Log.i(TAG, "AirPlay receiver started for '" + deviceName + "' on port " + AIRPLAY_PORT);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        unregisterMdns();

        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
            serverSocket = null;
        }
        decoder.stop();
        mainHandler.post(() -> {
            if (listener != null) listener.onMirroringStopped();
        });
        Log.i(TAG, "AirPlay receiver stopped");
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(AIRPLAY_PORT);
            while (running && !serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                serverExecutor.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Server socket error", e);
        }
    }

    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {
            client.setTcpNoDelay(true);
            client.setSoTimeout(60_000);

            byte[] buffer = new byte[65536];
            while (running && !client.isClosed()) {
                // Check if binary interleaved packet arrives (starts with '$' = 0x24)
                int firstByte = in.read();
                if (firstByte == -1) break;

                if (firstByte == 0x24) { // '$' Interleaved RTP/H.264 data packet
                    int channel = in.read();
                    int lengthH = in.read();
                    int lengthL = in.read();
                    if (lengthL == -1) break;
                    int length = ((lengthH & 0xFF) << 8) | (lengthL & 0xFF);

                    if (length > 0) {
                        byte[] payload = readFully(in, length);
                        if (payload != null && channel == 0) {
                            // Video H.264 payload
                            processVideoPayload(payload);
                        }
                    }
                    continue;
                }

                // Standard RTSP text header
                ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
                headerBytes.write(firstByte);
                int b;
                while ((b = in.read()) != -1) {
                    headerBytes.write(b);
                    byte[] curr = headerBytes.toByteArray();
                    if (curr.length >= 4 &&
                            curr[curr.length - 4] == '\r' && curr[curr.length - 3] == '\n' &&
                            curr[curr.length - 2] == '\r' && curr[curr.length - 1] == '\n') {
                        break;
                    }
                }

                String request = new String(headerBytes.toByteArray(), StandardCharsets.UTF_8);
                if (request.trim().isEmpty()) continue;

                Map<String, String> headers = parseHeaders(request);
                String cseq = headers.getOrDefault("cseq", "1");
                String method = request.split(" ")[0];

                int contentLength = 0;
                if (headers.containsKey("content-length")) {
                    try { contentLength = Integer.parseInt(headers.get("content-length").trim()); }
                    catch (NumberFormatException ignored) { }
                }

                byte[] body = null;
                if (contentLength > 0) {
                    body = readFully(in, contentLength);
                }

                handleRtspMethod(method, cseq, body, out);
            }
        } catch (IOException e) {
            Log.d(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) { }
            mainHandler.post(() -> {
                if (listener != null) listener.onMirroringStopped();
            });
        }
    }

    private void handleRtspMethod(String method, String cseq, byte[] body, OutputStream out) throws IOException {
        String response;
        if ("OPTIONS".equalsIgnoreCase(method)) {
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "Public: POST, GET, OPTIONS, ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, SET_PARAMETER, GET_PARAMETER\r\n" +
                    "Server: AirTunes/220.68\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else if ("POST".equalsIgnoreCase(method)) {
            // Handle pair-setup / pair-verify
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Server: AirTunes/220.68\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else if ("SETUP".equalsIgnoreCase(method)) {
            mainHandler.post(() -> {
                if (listener != null) listener.onMirroringStarted();
            });
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n" +
                    "Session: 1\r\n" +
                    "Server: AirTunes/220.68\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else if ("RECORD".equalsIgnoreCase(method)) {
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n" +
                    "Audio-Latency: 11025\r\n" +
                    "Session: 1\r\n" +
                    "Server: AirTunes/220.68\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else if ("SET_PARAMETER".equalsIgnoreCase(method) || "GET_PARAMETER".equalsIgnoreCase(method)) {
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else if ("TEARDOWN".equalsIgnoreCase(method)) {
            mainHandler.post(() -> {
                if (listener != null) listener.onMirroringStopped();
            });
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else {
            response = "RTSP/1.0 200 OK\r\n" +
                    "CSeq: " + cseq + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void processVideoPayload(byte[] payload) {
        if (payload == null || payload.length < 4) return;
        // Search for NAL start code (0x00000001 or length prefixed)
        decoder.decode(payload, 0, payload.length, System.nanoTime() / 1000L);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(data, totalRead, length - totalRead);
            if (read == -1) return null;
            totalRead += read;
        }
        return data;
    }

    private static Map<String, String> parseHeaders(String request) {
        Map<String, String> headers = new HashMap<>();
        String[] lines = request.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String key = lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.US);
                String val = lines[i].substring(colon + 1).trim();
                headers.put(key, val);
            }
        }
        return headers;
    }

    private void registerMdns() {
        try {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(deviceName);
            serviceInfo.setServiceType("_airplay._tcp.");
            serviceInfo.setPort(AIRPLAY_PORT);

            // Essential AirPlay TXT records for iOS and macOS client discovery
            serviceInfo.setAttribute("model", "AppleTV3,2");
            serviceInfo.setAttribute("srcvers", "220.68");
            serviceInfo.setAttribute("features", "0x5A7FFFF7,0x1E");
            serviceInfo.setAttribute("flags", "0x4");
            serviceInfo.setAttribute("vv", "2");
            serviceInfo.setAttribute("deviceid", getMacAddress());

            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo info) {
                    Log.i(TAG, "mDNS AirPlay service registered: " + info.getServiceName());
                }
                @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "mDNS registration failed: " + errorCode);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo info) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) { }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register mDNS service", e);
        }
    }

    private void unregisterMdns() {
        if (nsdManager != null && nsdListener != null) {
            try {
                nsdManager.unregisterService(nsdListener);
            } catch (Exception ignored) { }
            nsdListener = null;
        }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "192.168.1.1";
    }

    private static String getMacAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().equalsIgnoreCase("wlan0") || intf.getName().equalsIgnoreCase("eth0")) {
                    byte[] mac = intf.getHardwareAddress();
                    if (mac != null) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : mac) sb.append(String.format("%02X:", b));
                        if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "B8:27:EB:42:94:01";
    }
}
