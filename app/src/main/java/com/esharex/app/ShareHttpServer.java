package com.esharex.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class ShareHttpServer extends NanoHTTPD {
    private final Context context;
    private final File uploadsDir;
    private final List<JSONObject> messages = new CopyOnWriteArrayList<>();

    public ShareHttpServer(Context context, File dataDir) {
        super("0.0.0.0", 8080);
        this.context = context.getApplicationContext();
        this.uploadsDir = new File(dataDir, "uploads");
        if (!uploadsDir.exists()) uploadsDir.mkdirs();
    }

    @Override public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (Method.OPTIONS.equals(method)) {
                Response r = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
                addCors(r); return r;
            }
            if ("/api/health".equals(uri)) return json(new JSONObject().put("ok", true).put("app", "eShareX"));
            if ("/api/server-info".equals(uri)) {
                String ip = getLocalIp();
                return json(new JSONObject().put("ip", ip).put("port", 8080).put("address", "http://" + ip + ":8080"));
            }
            if ("/api/messages".equals(uri) && Method.GET.equals(method)) {
                JSONArray arr = new JSONArray();
                for (JSONObject item : messages) arr.put(item);
                return json(arr);
            }
            if ("/api/chat".equals(uri) && Method.POST.equals(method)) return handleChat(session);
            if ("/api/upload".equals(uri) && Method.POST.equals(method)) return handleUpload(session);
            if (uri.startsWith("/uploads/")) return serveUpload(uri.substring("/uploads/".length()));
            if ("/".equals(uri)) return asset("index.html", "text/html; charset=utf-8");
            if (uri.matches("/[A-Za-z0-9._/-]+") && !uri.contains("..")) {
                String path = uri.substring(1);
                if (path.isEmpty()) path = "index.html";
                return asset(path, mimeFor(path));
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        } catch (Exception e) {
            return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private Response handleChat(IHTTPSession session) throws Exception {
        Map<String, String> files = new java.util.HashMap<>();
        session.parseBody(files);
        String raw = files.get("postData");
        if (raw == null) raw = "";
        JSONObject input = new JSONObject(raw);
        String text = input.optString("text", "").trim();
        if (text.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Message is empty");
        JSONObject msg = new JSONObject();
        msg.put("id", UUID.randomUUID().toString());
        msg.put("type", "text");
        msg.put("text", text);
        msg.put("username", safeText(input.optString("username", "Guest"), 40));
        msg.put("replyTo", input.opt("replyTo") instanceof JSONObject ? input.opt("replyTo") : JSONObject.NULL);
        msg.put("timestamp", System.currentTimeMillis());
        addMessage(msg);
        return json(new JSONObject().put("success", true).put("message", msg));
    }

    private Response handleUpload(IHTTPSession session) throws Exception {
        Map<String, String> files = new java.util.HashMap<>();
        session.parseBody(files);
        Map<String, List<String>> params = session.getParameters();
        String temp = files.get("file");
        if (temp == null) return jsonError(Response.Status.BAD_REQUEST, "No file received");
        String original = first(params, "file");
        if (original == null || original.trim().isEmpty()) original = "shared-file";
        original = new File(original).getName().replaceAll("[^A-Za-z0-9._ -]", "_");
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0 && dot < original.length() - 1) ext = original.substring(dot);
        String stored = UUID.randomUUID().toString() + ext;
        File destination = new File(uploadsDir, stored);
        try (InputStream in = new FileInputStream(temp); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        String mime = first(params, "file.type");
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        JSONObject reply = null;
        String replyRaw = first(params, "replyTo");
        if (replyRaw != null) try { reply = new JSONObject(replyRaw); } catch (Exception ignored) {}
        JSONObject msg = new JSONObject();
        msg.put("id", UUID.randomUUID().toString());
        msg.put("type", "file");
        msg.put("url", "/uploads/" + stored);
        msg.put("filename", original);
        msg.put("mime", mime);
        msg.put("username", safeText(first(params, "username"), 40));
        msg.put("caption", safeText(first(params, "caption"), 500));
        msg.put("replyTo", reply == null ? JSONObject.NULL : reply);
        msg.put("timestamp", System.currentTimeMillis());
        addMessage(msg);
        return json(new JSONObject().put("success", true).put("message", msg));
    }

    private void addMessage(JSONObject message) {
        messages.add(message);
        while (messages.size() > 300) messages.remove(0);
    }

    private String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String safeText(String value, int max) {
        if (value == null) return "Guest";
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private Response serveUpload(String name) throws Exception {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad filename");
        File file = new File(uploadsDir, name);
        if (!file.isFile()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(name), new FileInputStream(file), file.length());
        r.addHeader("Content-Disposition", "inline; filename=\"" + name.replace("\"", "") + "\"");
        addCors(r); return r;
    }

    private Response asset(String name, String mime) throws Exception {
        InputStream in = context.getAssets().open(name);
        Response r = newChunkedResponse(Response.Status.OK, mime, in);
        addCors(r); return r;
    }

    private Response json(Object value) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString());
        addCors(r); return r;
    }

    private Response jsonError(Response.Status status, String message) {
        try { return error(status, new JSONObject().put("success", false).put("error", message == null ? "Unknown error" : message).toString()); }
        catch (Exception e) { return newFixedLengthResponse(status, MIME_PLAINTEXT, "Request failed"); }
    }

    private Response error(Response.Status status, String body) {
        Response r = newFixedLengthResponse(status, "application/json; charset=utf-8", body);
        addCors(r); return r;
    }

    private void addCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        response.addHeader("Cache-Control", "no-store");
    }

    private String getLocalIp() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prefer Wi-Fi / hotspot interfaces so the displayed address is reachable by nearby devices.
            for (NetworkInterface ni : interfaces) {
                String name = ni.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("swlan") || name.contains("p2p")) {
                    String ip = ipv4For(ni);
                    if (ip != null) return ip;
                }
            }
            for (NetworkInterface ni : interfaces) {
                String ip = ipv4For(ni);
                if (ip != null) return ip;
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private String ipv4For(NetworkInterface ni) {
        try {
            if (!ni.isUp() || ni.isLoopback()) return null;
            for (java.net.InetAddress address : Collections.list(ni.getInetAddresses())) {
                if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                    String ip = address.getHostAddress();
                    if (!ip.startsWith("169.254.")) return ip;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
