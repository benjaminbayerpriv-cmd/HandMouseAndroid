package com.doubleb.handmouse;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalAssetServer {
    public static final int PORT = 8765;
    private final Context context;
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public LocalAssetServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    pool.submit(() -> handle(socket));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "HandMouseAssetServer");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = s.getOutputStream()) {

            String request = reader.readLine();
            if (request == null || !request.startsWith("GET ")) {
                send(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String rawPath = request.split(" ")[1];
            int q = rawPath.indexOf('?');
            if (q >= 0) rawPath = rawPath.substring(0, q);
            String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name());
            if (path.equals("/")) path = "/hand-skeleton.html";
            if (path.contains("..")) {
                send(out, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // Ignore request headers.
            }
            String asset = "hand" + path;
            try (InputStream in = context.getAssets().open(asset)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                send(out, 200, mime(path), bos.toByteArray());
            } catch (IOException notFound) {
                send(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static void send(OutputStream out, int code, String type, byte[] data) throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".mjs") || p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".task")) return "application/octet-stream";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
