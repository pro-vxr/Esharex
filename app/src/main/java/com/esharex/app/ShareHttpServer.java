package com.esharex.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ShareHttpServer extends NanoHTTPD {
    private final Context context;
    private final File uploadsDir, dataDir;
    private final List<JSONObject> messages = new CopyOnWriteArrayList<>();
    private final List<JSONObject> directMessages = new CopyOnWriteArrayList<>();
    private final Map<String, JSONObject> users = new java.util.concurrent.ConcurrentHashMap<>();
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
    }

    @Override public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (Method.OPTIONS.equals(method)) { Response r = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""); addCors(r); return r; }
            if ("/api/health".equals(uri)) return json(new JSONObject().put("ok", true).put("app", "eShareX").put("version", "2.0"));
            if ("/api/server-info".equals(uri)) {
                String ip = getLocalIp();
                return json(new JSONObject().put("ip", ip).put("port", 8080).put("address", "http://" + ip + ":8080"));
            }
            if ("/api/register".equals(uri) && Method.POST.equals(method)) return register(session);
            if ("/api/users".equals(uri) && Method.GET.equals(method)) return listUsers(session);
            if ("/api/presence".equals(uri) && Method.POST.equals(method)) return heartbeat(session);
            if ("/api/messages".equals(uri) && Method.GET.equals(method)) return json(arrayCopy(messages));
            if ("/api/chat".equals(uri) && Method.POST.equals(method)) return handleChat(session);
            if ("/api/dm".equals(uri) && Method.GET.equals(method)) return listDirectMessages(session);
            if ("/api/dm".equals(uri) && Method.POST.equals(method)) return sendDirectMessage(session);
            if ("/api/export/chat".equals(uri) && Method.GET.equals(method)) return exportChat();
            if ("/api/export/dm".equals(uri) && Method.GET.equals(method)) return exportDirectMessages(session);
            if ("/api/upload".equals(uri) && Method.POST.equals(method)) return handleUpload(session);
            if (uri.startsWith("/uploads/")) return serveUpload(uri.substring("/uploads/".length()));
            if ("/".equals(uri) || "/chat".equals(uri) || "/friends".equals(uri)) return asset("index.html", "text/html; charset=utf-8");
            if (uri.matches("/[A-Za-z0-9._/-]+") && !uri.contains("..")) return asset(uri.substring(1), mimeFor(uri));
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        } catch (Exception e) { return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage()); }
    }

    private JSONObject readBody(IHTTPSession session) throws Exception {
        Map<String,String> files = new HashMap<>(); session.parseBody(files);
        String raw = files.get("postData"); return new JSONObject(raw == null ? "{}" : raw);
    }
    private Response register(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String id = safeText(in.optString("id", ""), 80);
        String name = safeText(in.optString("name", "Guest"), 32);
        if (id.isEmpty() || name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Device ID and name are required");
        JSONObject user = users.get(id);
        if (user == null) user = new JSONObject().put("id", id).put("joinedAt", System.currentTimeMillis());
        user.put("name", name).put("lastSeen", System.currentTimeMillis());
        users.put(id, user); saveUsers();
        return json(new JSONObject().put("success", true).put("user", user));
    }
    private Response heartbeat(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session); String id = in.optString("id", "");
        JSONObject user = users.get(id);
        if (user == null) return jsonError(Response.Status.UNAUTHORIZED, "Register first");
        user.put("lastSeen", System.currentTimeMillis()); saveUsers();
        return json(new JSONObject().put("success", true).put("online", true));
    }
    private Response listUsers(IHTTPSession session) throws Exception {
        String self = session.getParms().get("id"); JSONArray arr = new JSONArray(); long now = System.currentTimeMillis();
        for (JSONObject u : users.values()) {
            JSONObject item = new JSONObject(u.toString());
            long seen = item.optLong("lastSeen", 0);
            item.put("online", now - seen < 45000);
            item.put("self", item.optString("id").equals(self));
            arr.put(item);
        }
        return json(arr);
    }
    private Response handleChat(IHTTPSession session) throws Exception {
        JSONObject input = readBody(session);
        String text = safeText(input.optString("text", ""), 4000);
        if (text.trim().isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "Message is empty");
        String id = input.optString("userId", "");
        JSONObject user = users.get(id);
        String username = user == null ? safeText(input.optString("username", "Guest"), 32) : user.optString("name", "Guest");
        JSONObject msg = new JSONObject().put("id", UUID.randomUUID().toString()).put("type","text")
            .put("text",text).put("username",username).put("userId",id)
            .put("replyTo", input.opt("replyTo") instanceof JSONObject ? input.opt("replyTo") : JSONObject.NULL)
            .put("timestamp",System.currentTimeMillis());
        addMessage(msg); return json(new JSONObject().put("success",true).put("message",msg));
    }
    private Response sendDirectMessage(IHTTPSession session) throws Exception {
        JSONObject in = readBody(session);
        String from = in.optString("from",""), to = in.optString("to",""), text = safeText(in.optString("text",""),4000);
        if (from.isEmpty() || to.isEmpty() || from.equals(to) || text.trim().isEmpty()) return jsonError(Response.Status.BAD_REQUEST,"Valid sender, recipient and message are required");
        JSONObject sender = users.get(from), recipient = users.get(to);
        if (sender == null || recipient == null) return jsonError(Response.Status.NOT_FOUND,"User not found");
        JSONObject msg = new JSONObject().put("id",UUID.randomUUID().toString()).put("from",from).put("to",to)
            .put("fromName",sender.optString("name","User")).put("text",text).put("timestamp",System.currentTimeMillis());
        directMessages.add(msg); trim(directMessages,1000); saveArray(dmFile,directMessages);
        return json(new JSONObject().put("success",true).put("message",msg));
    }
    private Response listDirectMessages(IHTTPSession session) throws Exception {
        String me = session.getParms().get("me"), peer = session.getParms().get("peer");
        if (me == null || peer == null) return jsonError(Response.Status.BAD_REQUEST,"me and peer are required");
        JSONArray arr = new JSONArray();
        for (JSONObject msg : directMessages) {
            String from=msg.optString("from"), to=msg.optString("to");
            if ((from.equals(me)&&to.equals(peer))||(from.equals(peer)&&to.equals(me))) arr.put(msg);
        }
        return json(arr);
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

    private Response handleUpload(IHTTPSession session) throws Exception {
        Map<String,String> files = new HashMap<>(); session.parseBody(files);
        Map<String,List<String>> params=session.getParameters(); String temp=files.get("file");
        if(temp==null) return jsonError(Response.Status.BAD_REQUEST,"No file received");
        String original=first(params,"file"); if(original==null||original.trim().isEmpty()) original="shared-file";
        original=new File(original).getName().replaceAll("[^A-Za-z0-9._ -]","_");
        String ext=""; int dot=original.lastIndexOf('.'); if(dot>=0&&dot<original.length()-1) ext=original.substring(dot);
        String stored=UUID.randomUUID()+ext; File destination=new File(uploadsDir,stored);
        try(InputStream in=new FileInputStream(temp); FileOutputStream out=new FileOutputStream(destination)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        String mime=first(params,"file.type"); if(mime==null)mime="application/octet-stream";
        JSONObject msg=new JSONObject().put("id",UUID.randomUUID().toString()).put("type","file").put("url","/uploads/"+stored)
            .put("filename",original).put("mime",mime).put("username",safeText(first(params,"username"),32))
            .put("userId",safeText(first(params,"userId"),80)).put("caption",safeText(first(params,"caption"),500))
            .put("timestamp",System.currentTimeMillis());
        String reply=first(params,"replyTo"); if(reply!=null)try{msg.put("replyTo",new JSONObject(reply));}catch(Exception ignored){}
        addMessage(msg); return json(new JSONObject().put("success",true).put("message",msg));
    }
    private void addMessage(JSONObject msg){messages.add(msg);trim(messages,500);saveArray(messagesFile,messages);}
    private void trim(List<JSONObject> list,int max){while(list.size()>max)list.remove(0);}
    private JSONArray arrayCopy(List<JSONObject> list){JSONArray a=new JSONArray();for(JSONObject o:list)a.put(o);return a;}
    private String first(Map<String,List<String>> p,String k){List<String> v=p.get(k);return v==null||v.isEmpty()?null:v.get(0);}
    private String safeText(String v,int max){if(v==null)return "";String c=v.replaceAll("[\\p{Cntrl}]","").trim();return c.length()>max?c.substring(0,max):c;}
    private void loadArray(File f,List<JSONObject> target){try{if(f.exists()){JSONArray a=new JSONArray(readFile(f));for(int i=0;i<a.length();i++)target.add(a.getJSONObject(i));}}catch(Exception ignored){}}
    private void saveArray(File f,List<JSONObject> source){try(FileOutputStream o=new FileOutputStream(f)){o.write(arrayCopy(source).toString().getBytes("UTF-8"));}catch(Exception ignored){}}
    private void loadUsers(){try{if(usersFile.exists()){JSONArray a=new JSONArray(readFile(usersFile));for(int i=0;i<a.length();i++){JSONObject u=a.getJSONObject(i);users.put(u.getString("id"),u);}}}catch(Exception ignored){}}
    private void saveUsers(){JSONArray a=new JSONArray();for(JSONObject u:users.values())a.put(u);try(FileOutputStream o=new FileOutputStream(usersFile)){o.write(a.toString().getBytes("UTF-8"));}catch(Exception ignored){}}
    private String readFile(File f)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[]b=new byte[4096];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}return o.toString("UTF-8");}
    private Response serveUpload(String name) throws Exception {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad filename");
        }
        File f = new File(uploadsDir, name);
        if (!f.isFile()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
        Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(name), new FileInputStream(f), f.length());
        r.addHeader("Content-Disposition", "inline; filename=\"" + name.replace("\"", "") + "\"");
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
    private String getLocalIp(){try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces())){String nm=n.getName().toLowerCase();if(nm.contains("wlan")||nm.contains("ap")||nm.contains("swlan")||nm.contains("p2p")){String ip=ipv4For(n);if(ip!=null)return ip;}}for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces())){String ip=ipv4For(n);if(ip!=null)return ip;}}catch(Exception ignored){}return "127.0.0.1";}
    private String ipv4For(NetworkInterface n){try{if(!n.isUp()||n.isLoopback())return null;for(java.net.InetAddress a:Collections.list(n.getInetAddresses()))if(!a.isLoopbackAddress()&&a instanceof Inet4Address){String ip=a.getHostAddress();if(!ip.startsWith("169.254."))return ip;}}catch(Exception ignored){}return null;}
    private String mimeFor(String p){String x=p.toLowerCase();if(x.endsWith(".html"))return"text/html; charset=utf-8";if(x.endsWith(".webmanifest")||x.endsWith(".json"))return"application/manifest+json";if(x.endsWith(".css"))return"text/css; charset=utf-8";if(x.endsWith(".js"))return"application/javascript";if(x.endsWith(".png"))return"image/png";if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return"image/jpeg";if(x.endsWith(".gif"))return"image/gif";if(x.endsWith(".webp"))return"image/webp";if(x.endsWith(".svg"))return"image/svg+xml";if(x.endsWith(".ico"))return"image/x-icon";if(x.endsWith(".mp4"))return"video/mp4";if(x.endsWith(".pdf"))return"application/pdf";return"application/octet-stream";}
}
