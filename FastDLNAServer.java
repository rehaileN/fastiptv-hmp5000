import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

public class FastDLNAServer {
    private static final String M3U_PATH = "C:\\IPTV\\ulusal50.m3u";
    private static final String LOG_PATH = "C:\\IPTV\\fast_dlna.log";
    private static final String FFMPEG_PATH = Files.exists(Paths.get("C:\\IPTV\\ffmpeg.exe"))
            ? "C:\\IPTV\\ffmpeg.exe"
            : "C:\\Program Files\\Serviio\\lib\\ffmpeg.exe";
    private static final int HTTP_PORT = 8899;
    private static final String SERVER_NAME = "FastIPTV (HMP5000)";
    private static final String DEVICE_UUID = "8f7e6d5c-4b3a-2190-89ab-cdef01234567";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String DLNA_PROTOCOL_INFO =
            "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000";

    // Exact multiple of 188-byte MPEG-TS packet size: 100 * 188 = 18,800 bytes per chunk.
    // 900 chunks * 18,800 bytes = ~16.92 MB lossless blocking queue (never drops bytes, holds 30s+ of 4.7 Mbps TRT HD!).
    private static final int STREAM_CHUNK_SIZE = 188 * 100;
    private static final int MAX_BUFFER_CHUNKS = 900;
    private static final int SOCKET_SEND_BUFFER = 262144; // 256 KB: fits 122 KB TRT keyframes in 1 window without TCP stalls

    static class Channel {
        final int id;
        final String title;
        final String rawTitle;
        final String group;
        final String url;

        Channel(int id, String title, String rawTitle, String group, String url) {
            this.id = id;
            this.title = title;
            this.rawTitle = rawTitle;
            this.group = group;
            this.url = url;
        }
    }

    static class CachedVariant {
        final String url;
        final boolean isHugeDvr;
        final long timestamp;
        CachedVariant(String url, boolean isHugeDvr, long timestamp) {
            this.url = url;
            this.isHugeDvr = isHugeDvr;
            this.timestamp = timestamp;
        }
    }

    static class CachedPlaylist {
        final byte[] data;
        final long timestamp;
        CachedPlaylist(byte[] data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    private static volatile List<Channel> channels = new ArrayList<>();
    private static final Map<String, CachedVariant> variantCache = new ConcurrentHashMap<>();
    private static final Map<Integer, CachedPlaylist> playlistCache = new ConcurrentHashMap<>();
    private static final Set<Process> activeProcs = ConcurrentHashMap.newKeySet();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static synchronized void log(String msg) {
        String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
        System.out.println(line);
        System.out.flush();
        try {
            Files.writeString(Paths.get(LOG_PATH), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static void trustAllSSL() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
    }

    private static synchronized List<Channel> loadChannels() {
        List<Channel> list = new ArrayList<>();
        try {
            Path p = Paths.get(M3U_PATH);
            if (!Files.exists(p)) return list;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            String title = null;
            String group = "Genel";
            int idx = 1;
            Pattern grpPat = Pattern.compile("group-title=\"([^\"]+)\"");
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF:")) {
                    Matcher m = grpPat.matcher(line);
                    group = m.find() ? m.group(1) : "Genel";
                    int comma = line.indexOf(',');
                    title = (comma >= 0) ? line.substring(comma + 1).trim() : ("Channel " + idx);
                } else if (!line.startsWith("#") && title != null) {
                    list.add(new Channel(idx, String.format("%02d. %s", idx, title), title, group, line));
                    idx++;
                    title = null;
                }
            }
            channels = list;
        } catch (Exception e) {
            log("Error reading M3U: " + e.getMessage());
        }
        return list;
    }

    private static HttpURLConnection openGzipConnection(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(urlStr).toURL().openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = conn.getHeaderField("Location");
            if (loc != null) {
                String redir = new URI(urlStr).resolve(loc).toString();
                conn = (HttpURLConnection) new URI(redir).toURL().openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
            }
        }
        return conn;
    }

    private static String readAllText(HttpURLConnection conn, int maxLines) throws Exception {
        InputStream in = conn.getInputStream();
        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
            in = new GZIPInputStream(in);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (maxLines > 0 && ++count >= maxLines) break;
            }
        }
        return sb.toString();
    }

    private static CachedVariant resolveSingleVariant(String masterUrl, int timeoutMs) {
        long now = System.currentTimeMillis();
        CachedVariant cached = variantCache.get(masterUrl);
        if (cached != null && (now - cached.timestamp) < 300_000) {
            return cached;
        }
        try {
            HttpURLConnection conn = openGzipConnection(masterUrl, timeoutMs);
            String finalUrl = conn.getURL().toString();
            String content = readAllText(conn, 250);

            if (!content.contains("#EXT-X-STREAM-INF") ||
                (content.contains("#EXT-X-MEDIA:") && content.contains("TYPE=AUDIO") && content.contains("URI="))) {
                boolean huge = content.split("\n").length >= 200;
                CachedVariant cv = new CachedVariant(finalUrl, huge, now);
                variantCache.put(masterUrl, cv);
                return cv;
            }

            String[] lines = content.split("\n");
            int best720H = -1; long best720Bw = -1; String best720Url = null;
            int best1080H = -1; long best1080Bw = -1; String best1080Url = null;
            long bestAnyBw = -1; String bestAnyUrl = null;

            Pattern bwPat = Pattern.compile("BANDWIDTH=(\\d+)");
            Pattern resPat = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");

            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.startsWith("#EXT-X-STREAM-INF:")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String next = lines[j].trim();
                        if (!next.isEmpty() && !next.startsWith("#")) {
                            String varUrl = new URI(finalUrl).resolve(next).toString();
                            Matcher bm = bwPat.matcher(l);
                            Matcher rm = resPat.matcher(l);
                            long bw = bm.find() ? Long.parseLong(bm.group(1)) : 0;
                            int h = rm.find() ? Integer.parseInt(rm.group(2)) : 0;

                            if (h >= 540 && h <= 720) {
                                if (h > best720H || (h == best720H && bw > best720Bw)) {
                                    best720H = h; best720Bw = bw; best720Url = varUrl;
                                }
                            }
                            if (h > 0 && h <= 1080) {
                                if (h > best1080H || (h == best1080H && bw > best1080Bw)) {
                                    best1080H = h; best1080Bw = bw; best1080Url = varUrl;
                                }
                            }
                            if (bw >= bestAnyBw) {
                                bestAnyBw = bw; bestAnyUrl = varUrl;
                            }
                            break;
                        }
                    }
                }
            }
            String chosen = (best720Url != null) ? best720Url : (best1080Url != null ? best1080Url : (bestAnyUrl != null ? bestAnyUrl : finalUrl));

            // Check if the chosen sub-playlist is a giant 24-hour DVR playlist (like TRT's 30,000-line master_720.m3u8)
            boolean isHugeDvr = chosen.contains(".trt.com.tr/");
            if (!isHugeDvr) {
                try {
                    HttpURLConnection subConn = openGzipConnection(chosen, 1500);
                    String subHead = readAllText(subConn, 180);
                    if (subHead.split("\n").length >= 150) {
                        isHugeDvr = true;
                    }
                } catch (Exception ignored) {}
            }

            CachedVariant cv = new CachedVariant(chosen, isHugeDvr, now);
            variantCache.put(masterUrl, cv);
            return cv;
        } catch (Exception e) {
            return new CachedVariant(masterUrl, false, now);
        }
    }

    /**
     * Trims giant 24-hour DVR playlists (e.g. TRT's 30,246-line / 878 KB playlist)
     * down to the last 12 live segments (~72s window) using a reverse O(1) tail scanner
     * so URI.resolve() is only called 12 times instead of 14,400 times.
     */
    private static void handleTrimmedHlsPlaylist(OutputStream out, int chId) throws IOException {
        Channel target = null;
        for (Channel c : channels) {
            if (c.id == chId) { target = c; break; }
        }
        if (target == null) {
            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long now = System.currentTimeMillis();
        CachedPlaylist cachedPl = playlistCache.get(chId);
        if (cachedPl != null && (now - cachedPl.timestamp) < 1500) {
            byte[] data = cachedPl.data;
            String hdr = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: " + data.length + "\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(hdr.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
            return;
        }
        try {
            CachedVariant cv = resolveSingleVariant(target.url, 2000);
            HttpURLConnection conn = openGzipConnection(cv.url, 3000);
            URI baseUri = conn.getURL().toURI();
            String fullText = readAllText(conn, 0);
            String[] lines = fullText.split("\n");

            List<String> headers = new ArrayList<>(6);
            long mediaSeq = 0;

            int headerLimit = Math.min(lines.length, 30);
            for (int i = 0; i < headerLimit; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION") ||
                    line.startsWith("#EXT-X-TARGETDURATION") || line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")) {
                    headers.add(line);
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        mediaSeq = Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    } catch (Exception ignored) {}
                }
            }

            int keepCount = 12;
            int totalSegs = 0;
            int tailStartLine = 0;
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (!l.isEmpty() && l.charAt(0) != '#') {
                    totalSegs++;
                    if (totalSegs == keepCount) {
                        int k = i;
                        while (k > 0) {
                            String prev = lines[k - 1].trim();
                            if (prev.startsWith("#EXTINF:") || prev.startsWith("#EXT-X-PROGRAM-DATE-TIME:") || prev.startsWith("#EXT-X-KEY:")) {
                                k--;
                            } else {
                                break;
                            }
                        }
                        tailStartLine = k;
                    }
                }
            }

            int startIdx = Math.max(0, totalSegs - keepCount);
            long newMediaSeq = mediaSeq + startIdx;

            StringBuilder m3u8 = new StringBuilder(2048);
            for (String h : headers) {
                m3u8.append(h).append("\n");
            }
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(newMediaSeq).append("\n");
            for (int i = tailStartLine; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") || line.startsWith("#EXT-X-KEY:")) {
                    m3u8.append(line).append("\n");
                } else if (line.charAt(0) != '#') {
                    m3u8.append(baseUri.resolve(line).toString()).append("\n");
                }
            }

            byte[] data = m3u8.toString().getBytes(StandardCharsets.UTF_8);
            playlistCache.put(chId, new CachedPlaylist(data, System.currentTimeMillis()));
            String hdr = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: " + data.length + "\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(hdr.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (Exception e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void killAllActiveStreams() {
        for (Process p : activeProcs) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {}
        }
        activeProcs.clear();
    }

    private static List<InetAddress> getLocalIPs() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.getHostAddress().startsWith("192.168.")) {
                        list.add(addr);
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String extractTag(String xml, String tag) {
        Pattern p = Pattern.compile("<(?:\\w+:)?" + tag + ">([^<]*)</(?:\\w+:)?" + tag + ">");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static void handleClient(Socket sock) {
        try (Socket s = sock) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int state = 0;
            while ((b = in.read()) != -1) {
                headerBuf.write(b);
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') state++;
                else state = 0;
                if (state == 4 || headerBuf.size() > 16384) break;
            }
            if (headerBuf.size() == 0) return;

            String headerStr = headerBuf.toString(StandardCharsets.UTF_8);
            String[] headerLines = headerStr.split("\r\n");
            if (headerLines.length == 0) return;

            String[] reqLine = headerLines[0].split(" ");
            if (reqLine.length < 2) return;
            String method = reqLine[0].trim().toUpperCase();
            String path = reqLine[1].trim();

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < headerLines.length; i++) {
                int colon = headerLines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(headerLines[i].substring(0, colon).trim().toLowerCase(),
                                headerLines[i].substring(colon + 1).trim());
                }
            }

            int contentLen = 0;
            if (headers.containsKey("content-length")) {
                try { contentLen = Integer.parseInt(headers.get("content-length")); } catch (Exception ignored) {}
            }
            byte[] bodyBytes = new byte[contentLen];
            int readTotal = 0;
            while (readTotal < contentLen) {
                int r = in.read(bodyBytes, readTotal, contentLen - readTotal);
                if (r == -1) break;
                readTotal += r;
            }
            String body = new String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8);

            String localIp = s.getLocalAddress().getHostAddress();
            if (headers.containsKey("host")) {
                String h = headers.get("host");
                int c = h.indexOf(':');
                localIp = (c > 0) ? h.substring(0, c) : h;
            }

            if (method.equals("SUBSCRIBE") || method.equals("UNSUBSCRIBE")) {
                String resp = "HTTP/1.1 200 OK\r\n" +
                        "SID: uuid:" + UUID.randomUUID() + "\r\n" +
                        "TIMEOUT: Second-1800\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (method.equals("HEAD")) {
                String resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mpeg\r\n" +
                        "transferMode.dlna.org: Streaming\r\n" +
                        "contentFeatures.dlna.org: DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            if (method.equals("GET")) {
                if (path.equals("/description.xml")) {
                    sendXml(out, getDescriptionXml());
                    return;
                }
                if (path.equals("/cds.xml")) {
                    sendXml(out, CDS_SCPD_XML);
                    return;
                }
                if (path.equals("/cms.xml")) {
                    sendXml(out, CMS_SCPD_XML);
                    return;
                }
                if (path.startsWith("/hls/")) {
                    Matcher hm = Pattern.compile("/hls/(\\d+)\\.m3u8").matcher(path);
                    if (hm.find()) {
                        handleTrimmedHlsPlaylist(out, Integer.parseInt(hm.group(1)));
                        return;
                    }
                }
                if (path.startsWith("/stream/")) {
                    handleStream(s, out, path);
                    return;
                }
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (method.equals("POST")) {
                String soapAction = headers.getOrDefault("soapaction", "");
                if (soapAction.contains("GetProtocolInfo") || body.contains("GetProtocolInfo")) {
                    String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                            "<s:Body><u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">" +
                            "<Source>http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO</Source><Sink></Sink>" +
                            "</u:GetProtocolInfoResponse></s:Body></s:Envelope>";
                    sendXml(out, xml);
                    return;
                }
                if (soapAction.contains("GetSystemUpdateID") || body.contains("GetSystemUpdateID")) {
                    String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                            "<s:Body><u:GetSystemUpdateIDResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                            "<Id>1</Id></u:GetSystemUpdateIDResponse></s:Body></s:Envelope>";
                    sendXml(out, xml);
                    return;
                }
                if (soapAction.contains("GetSortCapabilities") || body.contains("GetSortCapabilities")) {
                    String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                            "<s:Body><u:GetSortCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                            "<SortCaps></SortCaps></u:GetSortCapabilitiesResponse></s:Body></s:Envelope>";
                    sendXml(out, xml);
                    return;
                }
                if (soapAction.contains("GetSearchCapabilities") || body.contains("GetSearchCapabilities")) {
                    String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                            "<s:Body><u:GetSearchCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                            "<SearchCaps></SearchCaps></u:GetSearchCapabilitiesResponse></s:Body></s:Envelope>";
                    sendXml(out, xml);
                    return;
                }
                if (soapAction.contains("Browse") || body.contains("Browse")) {
                    handleBrowse(out, body, localIp);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private static void sendXml(OutputStream out, String xml) throws IOException {
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Server: Windows/10.0 UPnP/1.0 FastIPTV/1.2\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static void handleBrowse(OutputStream out, String body, String localIp) throws IOException {
        String objectId = extractTag(body, "ObjectID");
        if (objectId.isEmpty()) objectId = "0";
        String browseFlag = extractTag(body, "BrowseFlag");
        if (browseFlag.isEmpty()) browseFlag = "BrowseDirectChildren";
        int startIdx = 0;
        int reqCount = 0;
        try { startIdx = Integer.parseInt(extractTag(body, "StartingIndex")); } catch (Exception ignored) {}
        try { reqCount = Integer.parseInt(extractTag(body, "RequestedCount")); } catch (Exception ignored) {}

        List<Channel> chList = channels.isEmpty() ? loadChannels() : channels;
        StringBuilder didl = new StringBuilder();
        didl.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            .append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">");

        int numReturned = 0;
        int totalMatches = 0;

        if ("BrowseMetadata".equals(browseFlag)) {
            if (objectId.startsWith("ch_")) {
                int cid = Integer.parseInt(objectId.substring(3));
                for (Channel ch : chList) {
                    if (ch.id == cid) {
                        String streamUrl = "http://" + localIp + ":" + HTTP_PORT + "/stream/" + ch.id + ".mpeg";
                        didl.append("<item id=\"ch_").append(ch.id).append("\" parentID=\"0\" restricted=\"1\">")
                            .append("<dc:title>").append(xmlEscape(ch.title)).append("</dc:title>")
                            .append("<upnp:class>object.item.videoItem</upnp:class>")
                            .append("<res protocolInfo=\"").append(DLNA_PROTOCOL_INFO).append("\">").append(streamUrl).append("</res>")
                            .append("</item>");
                        numReturned = 1;
                        totalMatches = 1;
                        break;
                    }
                }
            } else {
                didl.append("<container id=\"").append(xmlEscape(objectId)).append("\" parentID=\"-1\" restricted=\"1\" searchable=\"0\" childCount=\"").append(chList.size()).append("\">")
                    .append("<dc:title>").append(xmlEscape(SERVER_NAME)).append("</dc:title>")
                    .append("<upnp:class>object.container.storageFolder</upnp:class>")
                    .append("</container>");
                numReturned = 1;
                totalMatches = 1;
            }
        } else {
            totalMatches = chList.size();
            int endIdx = (reqCount > 0) ? Math.min(startIdx + reqCount, totalMatches) : totalMatches;
            for (int i = startIdx; i < endIdx; i++) {
                Channel ch = chList.get(i);
                String streamUrl = "http://" + localIp + ":" + HTTP_PORT + "/stream/" + ch.id + ".mpeg";
                didl.append("<item id=\"ch_").append(ch.id).append("\" parentID=\"").append(xmlEscape(objectId)).append("\" restricted=\"1\">")
                    .append("<dc:title>").append(xmlEscape(ch.title)).append("</dc:title>")
                    .append("<upnp:class>object.item.videoItem</upnp:class>")
                    .append("<res protocolInfo=\"").append(DLNA_PROTOCOL_INFO).append("\">").append(streamUrl).append("</res>")
                    .append("</item>");
                numReturned++;
            }
        }
        didl.append("</DIDL-Lite>");

        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                "<Result>" + xmlEscape(didl.toString()) + "</Result>" +
                "<NumberReturned>" + numReturned + "</NumberReturned>" +
                "<TotalMatches>" + totalMatches + "</TotalMatches>" +
                "<UpdateID>1</UpdateID>" +
                "</u:BrowseResponse></s:Body></s:Envelope>";
        sendXml(out, soap);
    }

    private static void handleStream(Socket sock, OutputStream out, String path) throws IOException {
        Matcher m = Pattern.compile("/stream/(\\d+)").matcher(path);
        if (!m.find()) {
            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int chId = Integer.parseInt(m.group(1));
        Channel target = null;
        for (Channel c : channels) {
            if (c.id == chId) { target = c; break; }
        }
        if (target == null) {
            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        killAllActiveStreams();

        // 256KB socket send buffer: fits 122KB TRT HD keyframes in 1 TCP window without stalls, while still draining in <0.2s on Stop
        try {
            sock.setTcpNoDelay(true);
            sock.setSendBufferSize(SOCKET_SEND_BUFFER);
            sock.setSoTimeout(0);
        } catch (Exception ignored) {}

        CachedVariant cv = resolveSingleVariant(target.url, 2000);
        String ffmpegInputUrl = cv.isHugeDvr
                ? ("http://127.0.0.1:" + HTTP_PORT + "/hls/" + chId + ".m3u8")
                : cv.url;

        boolean isTrt = target.url.contains(".trt.com.tr/");
        log("START Channel #" + chId + " (" + target.rawTitle + ") -> " + ffmpegInputUrl
                + (cv.isHugeDvr ? " [Trimmed Live DVR]" : "")
                + (isTrt ? " [TRT 720p25 Single-Slice Hardware-Safe Profile]" : ""));

        String hdr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mpeg\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Clean, artifact-free MPEG-TS pipeline matching Realtek RTD1185 (Philips HMP5000) hardware decoder requirements.
        // For TRT channels: converts 4.7Mbps / 50fps / 4-slice / HRD-SEI broadcast streams into the exact 1280x720@25fps,
        // 1-slice, 1.5Mbps High@L4.1 profile used by TV8 HD & Show TV HD (runs at ~11x real-time on Ryzen 7 5800HS).
        List<String> cmd = new ArrayList<>(Arrays.asList(
                FFMPEG_PATH,
                "-hide_banner",
                "-loglevel", "error",
                "-user_agent", USER_AGENT,
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "2"
        ));
        if (cv.isHugeDvr) {
            cmd.add("-live_start_index");
            cmd.add("-5");
        }
        cmd.addAll(Arrays.asList(
                "-thread_queue_size", "4096",
                "-probesize", "2000000",
                "-analyzeduration", "1500000",
                "-fflags", "+genpts+discardcorrupt",
                "-i", ffmpegInputUrl,
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-sn",
                "-dn"
        ));
        if (isTrt) {
            cmd.addAll(Arrays.asList(
                    "-vf", "fps=25",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-profile:v", "high",
                    "-level", "4.1",
                    "-b:v", "1500k",
                    "-maxrate", "1800k",
                    "-bufsize", "3600k",
                    "-pix_fmt", "yuv420p",
                    "-g", "50",
                    "-sc_threshold", "0",
                    "-x264-params", "slices=1:bframes=2:ref=3:nal-hrd=none"
            ));
        } else {
            cmd.addAll(Arrays.asList(
                    "-c:v", "copy",
                    "-bsf:v", "h264_mp4toannexb"
            ));
        }
        cmd.addAll(Arrays.asList(
                "-af", "aresample=async=1:first_pts=0",
                "-c:a", "ac3",
                "-b:a", "384k",
                "-ar", "48000",
                "-avoid_negative_ts", "make_zero",
                "-max_interleave_delta", "0",
                "-f", "mpegts",
                "pipe:1"
        ));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        activeProcs.add(proc);

        // Lossless 16.92 MB Blocking Queue (NEVER drops packets! Blocks cleanly when full, unblocks in 0ms on stop)
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(MAX_BUFFER_CHUNKS);
        AtomicBoolean finished = new AtomicBoolean(false);

        Thread readerThread = new Thread(() -> {
            try (InputStream ffIn = proc.getInputStream()) {
                byte[] buf = new byte[STREAM_CHUNK_SIZE];
                int offset = 0;
                int r;
                while (!finished.get() && (r = ffIn.read(buf, offset, STREAM_CHUNK_SIZE - offset)) != -1) {
                    offset += r;
                    // Flush as soon as we have at least 18,800 bytes (100 MPEG-TS packets)
                    if (offset == STREAM_CHUNK_SIZE) {
                        queue.put(Arrays.copyOf(buf, offset));
                        offset = 0;
                    }
                }
                if (!finished.get() && offset > 0) {
                    queue.put(Arrays.copyOf(buf, offset));
                }
            } catch (InterruptedException | IOException ignored) {
            } finally {
                finished.set(true);
            }
        }, "FFmpegReader-" + chId);
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            while (!finished.get() || !queue.isEmpty()) {
                byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (finished.get() && !proc.isAlive()) break;
                    continue;
                }
                out.write(chunk);
                out.flush();
            }
        } catch (Exception ignored) {
            // Client disconnected (Stop/Return or channel switch)
        } finally {
            finished.set(true);
            readerThread.interrupt();
            queue.clear();
            activeProcs.remove(proc);
            try { proc.destroyForcibly(); } catch (Exception ignored) {}
            log("STOP Channel #" + chId + " (" + target.rawTitle + ") immediately.");
        }
    }

    private static void startSsdpThreads() {
        String[][] ntTypes = new String[][]{
            {"upnp:rootdevice", "uuid:" + DEVICE_UUID + "::upnp:rootdevice"},
            {"uuid:" + DEVICE_UUID, "uuid:" + DEVICE_UUID},
            {"urn:schemas-upnp-org:device:MediaServer:1", "uuid:" + DEVICE_UUID + "::urn:schemas-upnp-org:device:MediaServer:1"},
            {"urn:schemas-upnp-org:service:ContentDirectory:1", "uuid:" + DEVICE_UUID + "::urn:schemas-upnp-org:service:ContentDirectory:1"},
            {"urn:schemas-upnp-org:service:ConnectionManager:1", "uuid:" + DEVICE_UUID + "::urn:schemas-upnp-org:service:ConnectionManager:1"}
        };

        Thread aliveThread = new Thread(() -> {
            while (true) {
                for (InetAddress ip : getLocalIPs()) {
                    try (DatagramSocket ds = new DatagramSocket(new InetSocketAddress(ip, 0))) {
                        String loc = "http://" + ip.getHostAddress() + ":" + HTTP_PORT + "/description.xml";
                        InetAddress group = InetAddress.getByName("239.255.255.250");
                        for (String[] nt : ntTypes) {
                            String msg = "NOTIFY * HTTP/1.1\r\n" +
                                    "HOST: 239.255.255.250:1900\r\n" +
                                    "CACHE-CONTROL: max-age=1800\r\n" +
                                    "LOCATION: " + loc + "\r\n" +
                                    "NT: " + nt[0] + "\r\n" +
                                    "NTS: ssdp:alive\r\n" +
                                    "SERVER: Windows/10.0 UPnP/1.0 FastIPTV/1.2\r\n" +
                                    "USN: " + nt[1] + "\r\n\r\n";
                            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                            ds.send(new DatagramPacket(bytes, bytes.length, group, 1900));
                        }
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
            }
        });
        aliveThread.setDaemon(true);
        aliveThread.start();

        Thread responderThread = new Thread(() -> {
            try (MulticastSocket ms = new MulticastSocket(null)) {
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(1900));
                SocketAddress mcastAddr = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
                for (InetAddress ip : getLocalIPs()) {
                    try {
                        NetworkInterface nic = NetworkInterface.getByInetAddress(ip);
                        if (nic != null) ms.joinGroup(mcastAddr, nic);
                    } catch (Exception ignored) {}
                }
                byte[] buf = new byte[4096];
                while (true) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    ms.receive(pkt);
                    String text = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    if (!text.contains("M-SEARCH")) continue;
                    Matcher stm = Pattern.compile("ST:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(text);
                    if (!stm.find()) continue;
                    String st = stm.group(1).trim();

                    String localIp = "192.168.1.109";
                    try (DatagramSocket probe = new DatagramSocket()) {
                        probe.connect(pkt.getAddress(), pkt.getPort());
                        localIp = probe.getLocalAddress().getHostAddress();
                    } catch (Exception ignored) {}

                    String loc = "http://" + localIp + ":" + HTTP_PORT + "/description.xml";
                    for (String[] nt : ntTypes) {
                        if (st.equalsIgnoreCase("ssdp:all") || st.equalsIgnoreCase(nt[0])) {
                            String resp = "HTTP/1.1 200 OK\r\n" +
                                    "CACHE-CONTROL: max-age=1800\r\n" +
                                    "EXT:\r\n" +
                                    "LOCATION: " + loc + "\r\n" +
                                    "SERVER: Windows/10.0 UPnP/1.0 FastIPTV/1.2\r\n" +
                                    "ST: " + nt[0] + "\r\n" +
                                    "USN: " + nt[1] + "\r\n\r\n";
                            byte[] rBytes = resp.getBytes(StandardCharsets.UTF_8);
                            ms.send(new DatagramPacket(rBytes, rBytes.length, pkt.getAddress(), pkt.getPort()));
                        }
                    }
                }
            } catch (Exception e) {
                log("SSDP Responder note: " + e.getMessage());
            }
        });
        responderThread.setDaemon(true);
        responderThread.start();
    }

    private static String getDescriptionXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<device>" +
                "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>" +
                "<friendlyName>" + xmlEscape(SERVER_NAME) + "</friendlyName>" +
                "<manufacturer>Antigravity</manufacturer>" +
                "<modelName>FastIPTV Live DLNA Proxy</modelName>" +
                "<modelNumber>1.2</modelNumber>" +
                "<UDN>uuid:" + DEVICE_UUID + "</UDN>" +
                "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>" +
                "<serviceList>" +
                "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>" +
                "<SCPDURL>/cds.xml</SCPDURL>" +
                "<controlURL>/cds/control</controlURL>" +
                "<eventSubURL>/cds/event</eventSubURL>" +
                "</service>" +
                "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>" +
                "<SCPDURL>/cms.xml</SCPDURL>" +
                "<controlURL>/cms/control</controlURL>" +
                "<eventSubURL>/cms/event</eventSubURL>" +
                "</service>" +
                "</serviceList>" +
                "</device></root>";
    }

    private static final String CDS_SCPD_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>" +
            "<action><name>Browse</name><argumentList>" +
            "<argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>" +
            "<argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>" +
            "<argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>" +
            "<argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>" +
            "<argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>" +
            "<argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>" +
            "<argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>" +
            "<argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>" +
            "<argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>" +
            "<argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>" +
            "</argumentList></action>" +
            "<action><name>GetSearchCapabilities</name><argumentList><argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument></argumentList></action>" +
            "<action><name>GetSortCapabilities</name><argumentList><argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument></argumentList></action>" +
            "<action><name>GetSystemUpdateID</name><argumentList><argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument></argumentList></action>" +
            "</actionList>" +
            "<serviceStateTable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"yes\"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>" +
            "</serviceStateTable></scpd>";

    private static final String CMS_SCPD_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>" +
            "<action><name>GetProtocolInfo</name><argumentList>" +
            "<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>" +
            "<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>" +
            "</argumentList></action>" +
            "</actionList>" +
            "<serviceStateTable>" +
            "<stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>" +
            "<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>" +
            "</serviceStateTable></scpd>";

    public static void main(String[] args) throws Exception {
        trustAllSSL();
        List<Channel> loaded = loadChannels();
        log("Loaded " + loaded.size() + " channels from " + M3U_PATH);

        // Background warmer: only runs when NO stream is currently playing so it never competes for bandwidth!
        Thread warmer = new Thread(() -> {
            for (Channel ch : channels) {
                if (!activeProcs.isEmpty()) break;
                resolveSingleVariant(ch.url, 2000);
            }
            log("Initial single-variant URL warm-up complete (" + variantCache.size() + " streams).");
            while (true) {
                try { Thread.sleep(300_000); } catch (InterruptedException ignored) {}
                if (!activeProcs.isEmpty()) continue;
                for (Channel ch : channels) {
                    if (!activeProcs.isEmpty()) break;
                    resolveSingleVariant(ch.url, 2000);
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        });
        warmer.setDaemon(true);
        warmer.start();

        startSsdpThreads();

        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
            log("FastIPTV DLNA Server v1.4 (TRT 720p25 Single-Slice Hardware-Safe Profile + 17MB Lossless Buffer) listening on port " + HTTP_PORT);
            while (true) {
                Socket client = server.accept();
                pool.submit(() -> handleClient(client));
            }
        }
    }
}
