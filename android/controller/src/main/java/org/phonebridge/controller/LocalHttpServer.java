package org.phonebridge.controller;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Deliberately tiny authenticated IPC listener, never exposed by the tunnel. */
final class LocalHttpServer implements AutoCloseable {
    interface Handler { CompletableFuture<JSONObject> handle(String path, JSONObject body); }
    private final ServerSocket listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    LocalHttpServer(String secret, Handler handler) throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8766), 8);
        worker.execute(() -> {
            while (running) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    process(socket, secret, handler);
                } catch (Exception ignored) { /* Never log headers, text, tokens. */ }
            }
        });
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int last = -1;
        for (int i = 0; i < 4096; i++) {
            int c = in.read();
            if (c == -1) throw new EOFException();
            if (last == '\r' && c == '\n') {
                byte[] bytes = b.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            if (c < 32 && c != '\r') throw new IOException("Bad header");
            b.write(c); last = c;
        }
        throw new IOException("Header too long");
    }
    private void process(Socket socket, String secret, Handler handler) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String[] request = line(in).split(" ");
        if (request.length != 3 || !"POST".equals(request[0]) || !"HTTP/1.1".equals(request[2])) {
            reply(socket, 400, "{}"); return;
        }
        Map<String,String> headers = new HashMap<>();
        boolean complete = false;
        for (int i = 0; i < 32; i++) {
            String h = line(in);
            if (h.isEmpty()) { complete = true; break; }
            int split = h.indexOf(':');
            if (split < 1 || Character.isWhitespace(h.charAt(0))) throw new IOException();
            String key = h.substring(0, split).toLowerCase(Locale.ROOT);
            if (headers.put(key, h.substring(split + 1).trim()) != null) throw new IOException();
        }
        if (!complete || headers.containsKey("origin") || headers.containsKey("transfer-encoding")
                || !headers.getOrDefault("host", "").equals("127.0.0.1:8766")) {
            reply(socket, 403, "{}"); return;
        }
        byte[] supplied = headers.getOrDefault("authorization", "").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(supplied, ("Bearer " + secret).getBytes(StandardCharsets.UTF_8))) {
            reply(socket, 401, "{}"); return;
        }
        if (!headers.getOrDefault("content-type", "").equals("application/json")) {
            reply(socket, 415, "{}"); return;
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
        if (length < 2 || length > 16384) { reply(socket, 413, "{}"); return; }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(body, offset, length - offset);
            if (n == -1) throw new EOFException();
            offset += n;
        }
        JSONObject json;
        try { json = new JSONObject(new String(body, StandardCharsets.UTF_8)); }
        catch (Exception bad) { reply(socket, 400, "{}"); return; }
        try {
            JSONObject result = handler.handle(request[1], json).get(5, TimeUnit.SECONDS);
            reply(socket, 200, result.toString());
        } catch (Exception failure) {
            // Timeout/error is never retried. A timed-out gesture may have executed.
            reply(socket, 503, "{\"error\":\"indeterminate; observe again\"}");
        }
    }
    private static void reply(Socket s, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\n"
            + "Connection: close\r\nCache-Control: no-store\r\nContent-Length: "
            + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes); out.flush();
    }
    @Override public void close() {
        running = false;
        try { listener.close(); } catch (IOException ignored) { }
        worker.shutdownNow();
    }
}
