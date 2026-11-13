package com.esharex.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.*;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class ShareHttpServer extends NanoHTTPD {
    private final Context context;
    private final File uploadsDir, dataDir;
    private final List<JSONObject> messages = new CopyOnWriteArrayList<>();
    private final List<JSONObject> directMessages = new CopyOnWriteArrayList<>();
    private final Map<String, JSONObject> users = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong(0L);
    private final File messagesFile, dmFile, usersFile;

    public ShareHttpServer(Context context, File dataDir) {
        super("0.0.0.0", 8080);
        this.context = context.getApplicationContext();
        this.dataDir = dataDir;
        this.uploadsDir = new File(dataDir, "uploads");
        if (!uploadsDir.exists()) uploadsDir.mkdirs();
        messagesFile = new File(dataDir, "messages.json");
        dmFile = new File(dataDir, "direct-messages.json");
        usersFile = new File(dataDir, "users.json");
        loadArray(messagesFile, messages);
        loadArray(dmFile, directMessages);
        loadUsers();
        migrateUsersToStableUsernameIds();
        long maxSequence = 0L;
        for (JSONObject message : messages) maxSequence = Math.max(maxSequence, message.optLong("eventSeq", 0L));
        for (JSONObject message : directMessages) maxSequence = Math.max(maxSequence, message.optLong("eventSeq", 0L));
        eventSequence.set(maxSequence);
    }

    @Override public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (Method.OPTIONS.equals(method)) { Response r = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""); addCors(r); return r; }
            if ("/api/health".equals(uri)) return json(new JSONObject().put("ok", true).put("app", "eShareX").put("version", "2.7.1"));
            if ("/api/pair/info".equals(uri) && Method.GET.equals(method)) return pairInfo(session);
            if ("/api/server-info".equals(uri)) {
                String ip = getLocalIp();
                String deviceId = context.getSharedPreferences("esharex_identity", Context.MODE_PRIVATE).getString("device_id", "");
                return json(new JSONObject().put("ip", ip).put("port", 8080).put("available", !"127.0.0.1".equals(ip)).put("address", "http://" + ip + ":8080").put("deviceId", deviceId));
            }
            if ("/api/qr".equals(uri) && Method.GET.equals(method)) return qrCode(session);
            if ("/api/register".equals(uri) && Method.POST.equals(method)) return register(session);
            if ("/api/leave".equals(uri) && Method.POST.equals(method)) return leave(session);
            if ("/api/users".equals(uri) && Method.GET.equals(method)) return listUsers(session);
            if ("/api/presence".equals(uri) && Method.POST.equals(method)) return heartbeat(session);
            if ("/api/unread".equals(uri) && Method.GET.equals(method)) return unreadSummary(session);
            if ("/api/read".equals(uri) && Method.POST.equals(method)) return markRead(session);
            if ("/api/notifications".equals(uri) && Method.GET.equals(method)) return notificationEvents(session);
            if ("/api/messages".equals(uri) && Method.GET.equals(method)) return json(arrayCopy(messages));
            if ("/api/chat".equals(uri) && Method.POST.equals(method)) return handleChat(session);
            if ("/api/chat/edit".equals(uri) && Method.POST.equals(method)) return editChatMessage(session);
            if ("/api/dm".equals(uri) && Method.GET.equals(method)) return listDirectMessages(session);
            if ("/api/dm/edit".equals(uri) && Method.POST.equals(method)) return editDirectMessage(session);
            if ("/api/dm".equals(uri) && Method.POST.equals(method)) return sendDirectMessage(session);
            if ("/api/export/chat".equals(uri) && Method.GET.equals(method)) return exportChat();
            if ("/api/export/dm".equals(uri) && Method.GET.equals(method)) return exportDirectMessages(session);
            if ("/api/upload".equals(uri) && Method.POST.equals(method)) return handleUpload(session);
            if (uri.startsWith("/uploads/")) return serveUpload(uri.substring("/uploads/".length()), session);
            if ("/".equals(uri) || "/chat".equals(uri) || "/friends".equals(uri)) return asset("index.html", "text/html; charset=utf-8");
            if (uri.matches("/[A-Za-z0-9._/-]+") && !uri.contains("..")) return asset(uri.substring(1), mimeFor(uri));
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        } catch (Exception e) { return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage()); }
    }


    private Response pairInfo(IHTTPSession session) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("esharex_pairing", Context.MODE_PRIVATE);
        String supplied = session.getParms().getOrDefault("code", "");
        String expected = prefs.getString("pair_code", "");
        long created = prefs.getLong("pair_created", 0L);
        boolean valid = !expected.isEmpty() && expected.equals(supplied) &&
            System.currentTimeMillis() - created <= 30L * 60L * 1000L;
        if (!valid) return jsonError(Response.Status.FORBIDDEN, "Pairing code is invalid or expired");
        String deviceId = context.getSharedPreferences("esharex_identity", Context.MODE_PRIVATE).getString("device_id", "");
        return json(new JSONObject().put("ok", true).put("deviceId", deviceId).put("name", "eShareX host"));
    }

    private JSONObject readBody(IHTTPSession session) throws Exception {
        Map<String,String> files = new HashMap<>(); session.parseBody(files);
        String raw = files.get("postData"); return new JSONObject(raw == null ? "{}" : raw);
    }

    private Response qrCode(IHTTPSession session) throws Exception {
        String value = session.getParms().get("text");
        if (value == null || value.trim().isEmpty()) value = "http://" + getLocalIp() + ":8080";
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++)
            bitmap.setPixel(x, y, matrix.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        Response response = newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(output.toByteArray()), output.size());
        addCors(response);
        return response;
    }

    private Response register(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String requestedId = safeText(in.optString("id", ""), 80);
        String name = safeText(in.optString("name", "Guest"), 32);
        if (requestedId.isEmpty() || name.isEmpty())
            return jsonError(Response.Status.BAD_REQUEST, "Device ID and name are required");

        // Username-only identity: the normalized username maps to one stable account ID.
        // This lets the same browser reconnect after its origin/IP changes. It is NOT secure
        // authentication: anyone on the LAN who chooses the same username shares that identity.
        String normalizedName = normalizeUsername(name);
        if (normalizedName.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "A display name is required");
        String canonicalId = stableUsernameId(normalizedName);

        synchronized (users) {
            JSONObject canonical = users.get(canonicalId);
            ArrayList<String> aliases = new ArrayList<>();
            List<JSONObject> legacyMatches = new ArrayList<>();
            for (JSONObject candidate : new ArrayList<>(users.values())) {
                String candidateId = candidate.optString("id", "");
                if (!candidateId.isEmpty() && !candidateId.equals(canonicalId)
                        && normalizedName.equals(normalizeUsername(candidate.optString("name", "")))) {
                    legacyMatches.add(candidate);
                }
            }

            if (canonical == null && !legacyMatches.isEmpty()) {
                canonical = legacyMatches.get(0);
                for (JSONObject candidate : legacyMatches) {
                    if (candidate.optLong("joinedAt", Long.MAX_VALUE) < canonical.optLong("joinedAt", Long.MAX_VALUE))
                        canonical = candidate;
                }
                // The selected legacy row itself must also be migrated to the stable ID.
                aliases.add(canonical.optString("id", ""));
            }
            if (canonical == null) {
                canonical = new JSONObject().put("joinedAt", System.currentTimeMillis());
            }
            for (JSONObject candidate : legacyMatches) {
                if (candidate != canonical) {
                    mergeReadState(canonical, candidate);
                    aliases.add(candidate.optString("id", ""));
                }
            }
            canonical.put("id", canonicalId);
            long now = System.currentTimeMillis();
            long previousSeen = canonical.optLong("lastSeen", 0L);
            canonical.put("name", name).put("lastSeen", now);
            if (previousSeen == 0L || now - previousSeen > 45000L || !canonical.has("notificationCursorSeq"))
                canonical.put("notificationCursorSeq", currentEventSequence());

            // Store the canonical row before migrating references so read-state migration can
            // find the destination. Old aliases are removed only after their message IDs move.
            users.put(canonicalId, canonical);
            for (String alias : new LinkedHashSet<>(aliases)) {
                if (alias.isEmpty() || alias.equals(canonicalId)) continue;
                migrateUserReferences(alias, canonicalId);
                users.remove(alias);
            }
            saveUsers();
            saveArray(messagesFile, messages);
            saveArray(dmFile, directMessages);
            return json(new JSONObject().put("success", true).put("canonicalId", canonicalId)
                    .put("user", canonical).put("mergedRecords", new LinkedHashSet<>(aliases).size()));
        }
    }

    private String normalizeUsername(String name) {
        if (name == null) return "";
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private String stableUsernameId(String normalizedName) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedName.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder("user-");
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private void mergeReadState(JSONObject target, JSONObject source) throws Exception {
        target.put("generalReadAt", Math.max(target.optLong("generalReadAt", 0), source.optLong("generalReadAt", 0)));
        target.put("notificationCursorSeq", Math.max(target.optLong("notificationCursorSeq", 0), source.optLong("notificationCursorSeq", 0)));
        JSONObject targetDM = target.optJSONObject("dmReadAt");
        if (targetDM == null) targetDM = new JSONObject();
        JSONObject sourceDM = source.optJSONObject("dmReadAt");
        if (sourceDM != null) {
            java.util.Iterator<String> keys = sourceDM.keys();
            while (keys.hasNext()) {
                String peer = keys.next();
                targetDM.put(peer, Math.max(targetDM.optLong(peer, 0), sourceDM.optLong(peer, 0)));
            }
        }
        target.put("dmReadAt", targetDM);
    }

    private void migrateUserReferences(String oldId, String newId) throws Exception {
        for (JSONObject msg : messages) if (oldId.equals(msg.optString("userId"))) msg.put("userId", newId);
        for (JSONObject msg : directMessages) {
            if (oldId.equals(msg.optString("from"))) msg.put("from", newId);
            if (oldId.equals(msg.optString("to"))) msg.put("to", newId);
        }
        for (JSONObject user : users.values()) {
            JSONObject dmReadAt = user.optJSONObject("dmReadAt");
            if (dmReadAt != null && dmReadAt.has(oldId)) {
                dmReadAt.put(newId, Math.max(dmReadAt.optLong(newId, 0), dmReadAt.optLong(oldId, 0)));
                dmReadAt.remove(oldId);
            }
        }
    }
    private Response leave(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String id = safeText(in.optString("id", ""), 80);
        if (id.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Device ID is required");
        JSONObject user = users.get(id);
        if (user != null) {
            user.put("lastSeen", 0);
            user.put("notificationCursor", System.currentTimeMillis());
            saveUsers();
        }
        return json(new JSONObject().put("success", true).put("left", true));
    }

    private Response heartbeat(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session); String id = safeText(in.optString("id", ""), 80);
        JSONObject user = users.get(id);
        if (user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register first");
        user.put("lastSeen", System.currentTimeMillis()); saveUsers();
        return json(new JSONObject().put("success", true).put("online", true));
    }
    private Response listUsers(IHTTPSession session) throws Exception {
        String self = session.getParms().get("id"); JSONArray arr = new JSONArray(); long now = System.currentTimeMillis();
        for (JSONObject u : users.values()) {
            long seen = u.optLong("lastSeen", 0);
            JSONObject item = new JSONObject().put("id", u.optString("id"))
                .put("name", u.optString("name", "Member"))
                .put("joinedAt", u.optLong("joinedAt", 0))
                .put("lastSeen", seen)
                .put("online", seen > 0 && now - seen < 45000)
                .put("self", u.optString("id").equals(self));
            arr.put(item);
        }
        return json(arr);
    }
    private Response unreadSummary(IHTTPSession session) throws Exception {
        String me = safeText(session.getParms().get("id"), 80);
        JSONObject user = users.get(me);
        if (user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register before checking unread messages");
        long generalReadAt = user.optLong("generalReadAt", 0);
        int generalUnread = 0;
        for (JSONObject msg : messages) {
            if (!me.equals(msg.optString("userId")) && msg.optLong("timestamp", 0) > generalReadAt) generalUnread++;
        }
        JSONObject dmReadAt = user.optJSONObject("dmReadAt");
        if (dmReadAt == null) dmReadAt = new JSONObject();
        JSONObject direct = new JSONObject();
        int totalDM = 0;
        for (JSONObject msg : directMessages) {
            if (!me.equals(msg.optString("to"))) continue;
            String peer = msg.optString("from");
            long readAt = dmReadAt.optLong(peer, 0);
            if (msg.optLong("timestamp", 0) > readAt) {
                direct.put(peer, direct.optInt(peer, 0) + 1);
                totalDM++;
            }
        }
        return json(new JSONObject().put("general", generalUnread).put("direct", direct)
            .put("total", generalUnread + totalDM));
    }

    private Response markRead(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String me = safeText(in.optString("id", ""), 80);
        String peer = safeText(in.optString("peer", ""), 80);
        JSONObject user = users.get(me);
        if (user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register before marking messages read");
        // The client acknowledges only the newest message it actually loaded.
        // Using wall-clock time here could mark a message read before it was rendered.
        long through = in.optLong("through", 0L);
        if (peer.isEmpty()) {
            long previous = user.optLong("generalReadAt", 0L);
            user.put("generalReadAt", Math.max(previous, through));
        } else {
            JSONObject readAt = user.optJSONObject("dmReadAt");
            if (readAt == null) readAt = new JSONObject();
            readAt.put(peer, Math.max(readAt.optLong(peer, 0L), through));
            user.put("dmReadAt", readAt);
        }
        saveUsers();
        return json(new JSONObject().put("success", true).put("readAt", through));
    }

    private Response notificationEvents(IHTTPSession session) throws Exception {
        String me = safeText(session.getParms().get("id"), 80);
        JSONObject user = users.get(me);
        if (user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register before checking notifications");
        long cursor = user.optLong("notificationCursorSeq", 0L);
        // Take a stable upper bound before scanning. Messages arriving after this point
        // receive a larger sequence and will be returned by the next poll.
        long upperBound = currentEventSequence();
        JSONArray events = new JSONArray();
        for (JSONObject msg : messages) {
            long seq = msg.optLong("eventSeq", 0L);
            if (seq > cursor && seq <= upperBound && !me.equals(msg.optString("userId"))) {
                events.put(new JSONObject().put("kind", "general").put("id", msg.optString("id"))
                    .put("sender", msg.optString("username", "General chat"))
                    .put("text", msg.optString("text", msg.optString("caption", "New attachment")))
                    .put("timestamp", msg.optLong("timestamp", 0L)).put("eventSeq", seq));
            }
        }
        for (JSONObject msg : directMessages) {
            long seq = msg.optLong("eventSeq", 0L);
            if (seq > cursor && seq <= upperBound && me.equals(msg.optString("to"))) {
                events.put(new JSONObject().put("kind", "direct").put("id", msg.optString("id"))
                    .put("peer", msg.optString("from")).put("sender", msg.optString("fromName", "Private message"))
                    .put("text", msg.optString("text", msg.optString("caption", msg.optString("filename", "New attachment"))))
                    .put("timestamp", msg.optLong("timestamp", 0L)).put("eventSeq", seq));
            }
        }
        // Advance only to the captured upper bound, never to wall-clock time. This avoids
        // dropping a message that arrives while this endpoint is building its response.
        if (upperBound > cursor) {
            user.put("notificationCursorSeq", upperBound);
            saveUsers();
        }
        return json(events);
    }

    private Response handleChat(IHTTPSession session) throws Exception {
        JSONObject input = readBody(session);
        String text = safeMessageText(input.optString("text", ""), 4000);
        if (text.trim().isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Message is empty");
        String id = input.optString("userId", "");
        JSONObject user = users.get(id);
        if (id.isEmpty() || user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register before sending messages");
        String username = user.optString("name", "Guest");
        JSONObject msg = new JSONObject().put("id", UUID.randomUUID().toString()).put("type","text")
            .put("text",text).put("username",username).put("userId",id)
            .put("replyTo", input.opt("replyTo") instanceof JSONObject ? input.opt("replyTo") : JSONObject.NULL)
            .put("timestamp",System.currentTimeMillis());
        addMessage(msg); return json(new JSONObject().put("success",true).put("message",msg));
    }

    private Response editChatMessage(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String id = in.optString("id", "");
        String userId = in.optString("userId", "");
        String text = safeMessageText(in.optString("text", ""), 4000);
        if (id.isEmpty() || userId.isEmpty() || text.trim().isEmpty())
            return jsonError(Response.Status.BAD_REQUEST, "Message ID and text are required");
        for (JSONObject msg : messages) {
            if (id.equals(msg.optString("id")) && userId.equals(msg.optString("userId"))) {
                msg.put("text", text);
                msg.put("edited", true);
                msg.put("editedAt", System.currentTimeMillis());
                saveArray(messagesFile, messages);
                return json(new JSONObject().put("success", true).put("message", msg));
            }
        }
        return jsonError(Response.Status.NOT_FOUND, "Message not found or not yours");
    }
    private Response sendDirectMessage(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String from = in.optString("from",""), to = in.optString("to",""), text = safeMessageText(in.optString("text",""),4000);
        if (from.isEmpty() || to.isEmpty() || from.equals(to) || text.trim().isEmpty()) return jsonError(Response.Status.BAD_REQUEST,"Valid sender, recipient and message are required");
        JSONObject sender = users.get(from), recipient = users.get(to);
        if (sender == null || recipient == null) return jsonError(Response.Status.NOT_FOUND,"User not found");
        JSONObject msg = new JSONObject().put("id",UUID.randomUUID().toString()).put("from",from).put("to",to)
            .put("fromName",sender.optString("name","User")).put("text",text).put("timestamp",System.currentTimeMillis());
        if (in.opt("replyTo") instanceof JSONObject) msg.put("replyTo", in.opt("replyTo"));
        synchronized (eventSequence) {
            stampEventSequence(msg);
            directMessages.add(msg); trim(directMessages,1000); saveArray(dmFile,directMessages);
        }
        return json(new JSONObject().put("success",true).put("message",msg));
    }
    private Response listDirectMessages(IHTTPSession session) throws Exception {
        String me = session.getParms().get("me"), peer = session.getParms().get("peer");
        if (me == null || peer == null) return jsonError(Response.Status.BAD_REQUEST,"me and peer are required");
        if (!users.containsKey(me)) return jsonError(Response.Status.UNAUTHORIZED,"Register before loading private messages");
        JSONArray arr = new JSONArray();
        for (JSONObject msg : directMessages) {
            String from=msg.optString("from"), to=msg.optString("to");
            if ((from.equals(me)&&to.equals(peer))||(from.equals(peer)&&to.equals(me))) arr.put(msg);
        }
        return json(arr);
    }

    private Response editDirectMessage(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String id = in.optString("id", "");
        String from = in.optString("from", "");
        String text = safeMessageText(in.optString("text", ""), 4000);
        if (id.isEmpty() || from.isEmpty() || text.trim().isEmpty())
            return jsonError(Response.Status.BAD_REQUEST, "Message ID and text are required");
        for (JSONObject msg : directMessages) {
            if (id.equals(msg.optString("id")) && from.equals(msg.optString("from"))) {
                msg.put("text", text);
                msg.put("edited", true);
                msg.put("editedAt", System.currentTimeMillis());
                saveArray(dmFile, directMessages);
                return json(new JSONObject().put("success", true).put("message", msg));
            }
        }
        return jsonError(Response.Status.NOT_FOUND, "Message not found or not yours");
    }

    private Response exportChat() throws Exception {
        StringBuilder out = new StringBuilder("eShareX General Chat\n");
        for (JSONObject m : messages) {
            out.append(new java.util.Date(m.optLong("timestamp"))).append("  ")
                .append(m.optString("username")).append(": ")
                .append(m.optString("text", m.optString("caption", "[file]"))).append("\n");
        }
        Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", out.toString());
        r.addHeader("Content-Disposition", "attachment; filename=\"esharex-chat.txt\"");
        addCors(r);
        return r;
    }

    private Response exportDirectMessages(IHTTPSession session) throws Exception {
        String me = session.getParms().get("me");
        String peer = session.getParms().get("peer");
        if (me == null || peer == null) {
            return jsonError(Response.Status.BAD_REQUEST, "me and peer are required");
        }
        StringBuilder out = new StringBuilder("eShareX Private Conversation\n");
        for (JSONObject m : directMessages) {
            String from = m.optString("from");
            String to = m.optString("to");
            if ((from.equals(me) && to.equals(peer)) || (from.equals(peer) && to.equals(me))) {
                out.append(new java.util.Date(m.optLong("timestamp"))).append("  ")
                    .append(m.optString("fromName", "Member")).append(": ")
                    .append(m.optString("text")).append("\n");
            }
        }
        Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", out.toString());
        r.addHeader("Content-Disposition", "attachment; filename=\"esharex-private-chat.txt\"");
        addCors(r);
        return r;
    }

    private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private Response handleUpload(IHTTPSession session) throws Exception {
        String lengthHeader = session.getHeaders().get("content-length");
        if (lengthHeader != null) { try { if (Long.parseLong(lengthHeader) > MAX_UPLOAD_BYTES + 1024L * 1024L) return jsonError(Response.Status.BAD_REQUEST, "File exceeds the 100 MB limit"); } catch (NumberFormatException ignored) {} }
        Map<String,String> files = new HashMap<>(); session.parseBody(files);
        Map<String,List<String>> params=session.getParameters(); String temp=files.get("file");
        if(temp==null) return jsonError(Response.Status.BAD_REQUEST,"No file received");
        File tempFile = new File(temp);
        if (!tempFile.isFile()) return jsonError(Response.Status.BAD_REQUEST,"Uploaded file is unavailable");
        if (tempFile.length() > MAX_UPLOAD_BYTES) return jsonError(Response.Status.BAD_REQUEST,"File exceeds the 100 MB limit");
        String userId = safeText(first(params,"userId"),80);
        JSONObject registeredUser = users.get(userId);
        if (userId.isEmpty() || registeredUser == null) return jsonError(Response.Status.UNAUTHORIZED,"Register before uploading files");
        String to=first(params,"to"), from=first(params,"from");
        if (to != null || from != null) {
            if (to == null || from == null || !from.equals(userId) || !users.containsKey(to)) return jsonError(Response.Status.NOT_FOUND,"Private chat user not found");
        }
        String original=first(params,"file"); if(original==null||original.trim().isEmpty()) original="shared-file";
        original=new File(original.replace('\\','/')).getName().replaceAll("[^A-Za-z0-9._ -]","_");
        String ext=""; int dot=original.lastIndexOf('.'); if(dot>=0&&dot<original.length()-1) ext=original.substring(dot);
        String stored=UUID.randomUUID()+ext; File destination=new File(uploadsDir,stored);
        try(InputStream in=new FileInputStream(temp); FileOutputStream out=new FileOutputStream(destination)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        if (destination.length() > MAX_UPLOAD_BYTES) { destination.delete(); return jsonError(Response.Status.BAD_REQUEST,"File exceeds the 100 MB limit"); }
        String mime=first(params,"mimeType"); if(mime==null||mime.isEmpty())mime=first(params,"file.type"); if(mime==null||mime.isEmpty())mime="application/octet-stream";
        JSONObject msg=new JSONObject().put("id",UUID.randomUUID().toString()).put("type","file").put("url","/uploads/"+stored+"?filename="+java.net.URLEncoder.encode(original,"UTF-8"))
            .put("filename",original).put("mime",mime).put("username",safeText(first(params,"username"),32))
            .put("userId",safeText(first(params,"userId"),80)).put("caption",safeText(first(params,"caption"),500))
            .put("fileSize",destination.length()).put("timestamp",System.currentTimeMillis());
        String reply=first(params,"replyTo"); if(reply!=null)try{msg.put("replyTo",new JSONObject(reply));}catch(Exception ignored){}
        if(to!=null && from!=null) {
            JSONObject sender=users.get(from), recipient=users.get(to);
            msg.put("from",from).put("to",to).put("fromName",sender.optString("name","User"));
            synchronized (eventSequence) {
                stampEventSequence(msg);
                directMessages.add(msg);trim(directMessages,1000);saveArray(dmFile,directMessages);
            }
        } else addMessage(msg);
        return json(new JSONObject().put("success",true).put("message",msg));
    }
    private void addMessage(JSONObject msg){
        synchronized (eventSequence) {
            stampEventSequence(msg);
            messages.add(msg);trim(messages,500);saveArray(messagesFile,messages);
        }
    }
    private void stampEventSequence(JSONObject msg) {
        if (msg == null || msg.has("eventSeq")) return;
        try { msg.put("eventSeq", eventSequence.incrementAndGet()); }
        catch (Exception ignored) {}
    }
    private long currentEventSequence() {
        synchronized (eventSequence) { return eventSequence.get(); }
    }
    private void trim(List<JSONObject> list,int max){while(list.size()>max)list.remove(0);}
    private JSONArray arrayCopy(List<JSONObject> list){JSONArray a=new JSONArray();for(JSONObject o:list)a.put(o);return a;}
    private String first(Map<String,List<String>> p,String k){List<String> v=p.get(k);return v==null||v.isEmpty()?null:v.get(0);}
    private String safeMessageText(String v, int max) {
        if (v == null) return "";
        // Keep intentional line breaks and tabs; remove other control characters.
        String cleaned = v.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private String safeText(String v,int max){if(v==null)return "";String c=v.replaceAll("[\\p{Cntrl}]","").trim();return c.length()>max?c.substring(0,max):c;}
    private void loadArray(File f,List<JSONObject> target){try{if(f.exists()){JSONArray a=new JSONArray(readFile(f));for(int i=0;i<a.length();i++)target.add(a.getJSONObject(i));}}catch(Exception ignored){}}
    private synchronized void saveArray(File f,List<JSONObject> source){File temp=new File(f.getParentFile(),f.getName()+".tmp");try(FileOutputStream o=new FileOutputStream(temp)){o.write(arrayCopy(source).toString().getBytes("UTF-8"));o.getFD().sync();}catch(Exception ignored){temp.delete();return;}if(!temp.renameTo(f)){try(FileInputStream in=new FileInputStream(temp);FileOutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(Exception ignored){}temp.delete();}}
    private void loadUsers(){try{if(usersFile.exists()){JSONArray a=new JSONArray(readFile(usersFile));for(int i=0;i<a.length();i++){JSONObject u=a.getJSONObject(i);users.put(u.getString("id"),u);}}}catch(Exception ignored){}}
    private void migrateUsersToStableUsernameIds() {
        synchronized (users) {
            Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
            Map<String, JSONObject> preserved = new LinkedHashMap<>();
            for (JSONObject user : new ArrayList<>(users.values())) {
                String normalized = normalizeUsername(user.optString("name", ""));
                if (normalized.isEmpty()) {
                    String id = user.optString("id", "");
                    if (!id.isEmpty()) preserved.put(id, user);
                } else {
                    groups.computeIfAbsent(normalized, key -> new ArrayList<>()).add(user);
                }
            }

            Map<String, String> aliases = new LinkedHashMap<>();
            for (Map.Entry<String, List<JSONObject>> entry : groups.entrySet()) {
                String stableId;
                try { stableId = stableUsernameId(entry.getKey()); }
                catch (Exception e) { continue; }
                List<JSONObject> records = entry.getValue();
                JSONObject canonical = records.get(0);
                for (JSONObject candidate : records) {
                    if (candidate.optLong("joinedAt", Long.MAX_VALUE) < canonical.optLong("joinedAt", Long.MAX_VALUE))
                        canonical = candidate;
                }
                long oldestJoin = canonical.optLong("joinedAt", System.currentTimeMillis());
                long newestSeen = canonical.optLong("lastSeen", 0L);
                for (JSONObject candidate : records) {
                    if (candidate != canonical) {
                        try { mergeReadState(canonical, candidate); } catch (Exception ignored) { }
                    }
                    oldestJoin = Math.min(oldestJoin, candidate.optLong("joinedAt", oldestJoin));
                    newestSeen = Math.max(newestSeen, candidate.optLong("lastSeen", 0L));
                }

                // JSONObject.put() declares JSONException on Android. Keep every original
                // record intact if the canonical update unexpectedly fails; never create
                // aliases for records that were not successfully migrated.
                try {
                    canonical.put("id", stableId);
                    canonical.put("joinedAt", oldestJoin);
                    canonical.put("lastSeen", newestSeen);
                } catch (org.json.JSONException migrationError) {
                    for (JSONObject record : records) {
                        String originalId = record.optString("id", "");
                        if (!originalId.isEmpty()) preserved.put(originalId, record);
                    }
                    continue;
                }

                for (JSONObject candidate : records) {
                    String oldId = candidate.optString("id", "");
                    if (!oldId.isEmpty() && !oldId.equals(stableId)) aliases.put(oldId, stableId);
                }
                preserved.put(stableId, canonical);
            }

            users.clear();
            users.putAll(preserved);
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (!alias.getKey().equals(alias.getValue())) {
                    try { migrateUserReferences(alias.getKey(), alias.getValue()); } catch (Exception ignored) { }
                }
            }
            if (!aliases.isEmpty()) {
                saveUsers();
                saveArray(messagesFile, messages);
                saveArray(dmFile, directMessages);
            }
        }
    }
    private synchronized void saveUsers(){JSONArray a=new JSONArray();for(JSONObject u:users.values())a.put(u);File temp=new File(dataDir,"users.json.tmp");try(FileOutputStream o=new FileOutputStream(temp)){o.write(a.toString().getBytes("UTF-8"));o.getFD().sync();}catch(Exception ignored){temp.delete();return;}if(!temp.renameTo(usersFile)){try(FileInputStream in=new FileInputStream(temp);FileOutputStream out=new FileOutputStream(usersFile)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(Exception ignored){}temp.delete();}}
    private String readFile(File f)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[]b=new byte[4096];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}return o.toString("UTF-8");}
    private Response serveUpload(String name, IHTTPSession session) throws Exception {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad filename");
        }
        File f = new File(uploadsDir, name);
        if (!f.isFile()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        String mime = mimeFor(name);
        // Only raster images and MP4 videos are rendered inline. Other uploaded
        // content is forced to download to prevent same-origin HTML/SVG script execution.
        boolean safeInline = mime.startsWith("image/") && !"image/svg+xml".equals(mime) || "video/mp4".equals(mime);
        if (!safeInline) mime = "application/octet-stream";
        Response r = newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(f), f.length());
        String original = safeText(session.getParms().get("filename"), 180);
        if (original.isEmpty()) original = name;
        original = original.replaceAll("[\\\"\\r\\n]", "_");
        String disposition = safeInline ? "inline" : "attachment";
        r.addHeader("Content-Disposition", disposition + "; filename=\"" + original + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(original, "UTF-8").replace("+", "%20"));
        r.addHeader("X-Content-Type-Options", "nosniff");
        addCors(r);
        return r;
    }
    private Response asset(String name,String mime)throws Exception{InputStream in=context.getAssets().open(name);Response r=newChunkedResponse(Response.Status.OK,mime,in);addCors(r);return r;}
    private Response json(Object v){Response r=newFixedLengthResponse(Response.Status.OK,"application/json; charset=utf-8",v.toString());addCors(r);return r;}
    private Response jsonError(Response.Status s,String m){
        try { return error(s,new JSONObject().put("success",false).put("error",m==null?"Request failed":m).toString()); }
        catch (Exception ignored) { return newFixedLengthResponse(s,MIME_PLAINTEXT,"Request failed"); }
    }
    private Response error(Response.Status s,String b){Response r=newFixedLengthResponse(s,"application/json; charset=utf-8",b);addCors(r);return r;}
    private void addCors(Response r){r.addHeader("Access-Control-Allow-Origin","*");r.addHeader("Access-Control-Allow-Methods","GET, POST, OPTIONS");r.addHeader("Access-Control-Allow-Headers","Content-Type");r.addHeader("Cache-Control","no-store");}
    private String getLocalIp() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prefer Wi-Fi, hotspot and Wi-Fi Direct interfaces.
            for (NetworkInterface n : interfaces) {
                String name = n.getName().toLowerCase(Locale.ROOT);
                if (name.contains("wlan") || name.contains("ap") || name.contains("swlan") || name.contains("p2p")) {
                    String ip = ipv4For(n);
                    if (ip != null) return ip;
                }
            }
            // Fall back to a private IPv4 address; never publish loopback or link-local.
            for (NetworkInterface n : interfaces) {
                String ip = ipv4For(n);
                if (ip != null && isPrivateIPv4(ip)) return ip;
            }
            for (NetworkInterface n : interfaces) {
                String ip = ipv4For(n);
                if (ip != null) return ip;
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
    private boolean isPrivateIPv4(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") ||
            ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }
    private String ipv4For(NetworkInterface n){try{if(!n.isUp()||n.isLoopback())return null;for(java.net.InetAddress a:Collections.list(n.getInetAddresses()))if(!a.isLoopbackAddress()&&a instanceof Inet4Address){String ip=a.getHostAddress();if(!ip.startsWith("169.254."))return ip;}}catch(Exception ignored){}return null;}
    private String mimeFor(String p){String x=p.toLowerCase();if(x.endsWith(".html"))return"text/html; charset=utf-8";if(x.endsWith(".webmanifest")||x.endsWith(".json"))return"application/manifest+json";if(x.endsWith(".css"))return"text/css; charset=utf-8";if(x.endsWith(".js"))return"application/javascript";if(x.endsWith(".png"))return"image/png";if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return"image/jpeg";if(x.endsWith(".gif"))return"image/gif";if(x.endsWith(".webp"))return"image/webp";if(x.endsWith(".svg"))return"image/svg+xml";if(x.endsWith(".ico"))return"image/x-icon";if(x.endsWith(".mp4"))return"video/mp4";if(x.endsWith(".pdf"))return"application/pdf";return"application/octet-stream";}
}
