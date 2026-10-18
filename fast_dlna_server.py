import os
import re
import sys
import time
import uuid
import queue
import socket
import threading
import subprocess
import urllib.parse
import urllib.request
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape as xml_escape

# Configuration
M3U_PATH = r"C:\IPTV\ulusal50.m3u"
FFMPEG_PATH = r"C:\Program Files\Serviio\lib\ffmpeg.exe"
HTTP_PORT = 8899
SERVER_NAME = "FastIPTV (HMP5000)"
DEVICE_UUID = "8f7e6d5c-4b3a-2190-89ab-cdef01234567"

# 256 chunks * 32 KB = 8 MB RAM ring buffer (~15 seconds of HD broadcast cushion)
STREAM_CHUNK_SIZE = 32768
MAX_BUFFER_CHUNKS = 256

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

CHANNELS = []
CHANNELS_LOCK = threading.Lock()
VARIANT_CACHE = {}  # url -> (resolved_url, timestamp)
ACTIVE_PROCS = {}   # proc_id -> subprocess.Popen
ACTIVE_PROCS_LOCK = threading.Lock()


def parse_m3u(path):
    channels = []
    if not os.path.exists(path):
        return channels
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        lines = [line.strip() for line in f if line.strip()]
    title = None
    group = "Genel"
    idx = 1
    for line in lines:
        if line.startswith("#EXTINF:"):
            g_match = re.search(r'group-title="([^"]+)"', line)
            group = g_match.group(1) if g_match else "Genel"
            if "," in line:
                title = line.split(",", 1)[1].strip()
            else:
                title = f"Channel {idx}"
        elif not line.startswith("#") and title:
            channels.append({
                "id": idx,
                "title": f"{idx:02d}. {title}",
                "raw_title": title,
                "group": group,
                "url": line
            })
            idx += 1
            title = None
    return channels


def resolve_single_variant(master_url, timeout=2.0):
    now = time.time()
    cached = VARIANT_CACHE.get(master_url)
    if cached and (now - cached[1]) < 60:
        return cached[0]

    try:
        req = urllib.request.Request(master_url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
            final_url = resp.geturl()
            content = resp.read(65536).decode("utf-8", errors="ignore")

        if "#EXT-X-STREAM-INF" not in content or (
            "#EXT-X-MEDIA:" in content and "TYPE=AUDIO" in content and "URI=" in content
        ):
            VARIANT_CACHE[master_url] = (final_url, now)
            return final_url

        lines = [l.strip() for l in content.splitlines() if l.strip()]
        variants = []
        for i, line in enumerate(lines):
            if line.startswith("#EXT-X-STREAM-INF:"):
                for j in range(i + 1, len(lines)):
                    if not lines[j].startswith("#"):
                        var_url = urllib.parse.urljoin(final_url, lines[j])
                        bw_m = re.search(r"BANDWIDTH=(\d+)", line)
                        res_m = re.search(r"RESOLUTION=(\d+)x(\d+)", line)
                        bw = int(bw_m.group(1)) if bw_m else 0
                        height = int(res_m.group(2)) if res_m else 0
                        variants.append((height, bw, var_url))
                        break

        if not variants:
            VARIANT_CACHE[master_url] = (final_url, now)
            return final_url

        preferred_720 = [v for v in variants if 540 <= v[0] <= 720]
        preferred_1080 = [v for v in variants if 0 < v[0] <= 1080]
        if preferred_720:
            chosen = sorted(preferred_720, key=lambda x: (x[0], x[1]))[-1][2]
        elif preferred_1080:
            chosen = sorted(preferred_1080, key=lambda x: (x[0], x[1]))[-1][2]
        else:
            chosen = sorted(variants, key=lambda x: x[1])[-1][2]

        VARIANT_CACHE[master_url] = (chosen, now)
        return chosen
    except Exception:
        return master_url


def background_variant_warmer():
    while True:
        with CHANNELS_LOCK:
            ch_list = list(CHANNELS)
        for ch in ch_list:
            resolve_single_variant(ch["url"], timeout=2.0)
            time.sleep(0.04)
        time.sleep(45)


def kill_all_active_streams():
    with ACTIVE_PROCS_LOCK:
        procs = list(ACTIVE_PROCS.values())
        ACTIVE_PROCS.clear()
    for p in procs:
        try:
            p.kill()
        except Exception:
            pass


def get_local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    if not ips:
        ips.append("127.0.0.1")
    return ips


CDS_SCPD_XML = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>Browse</name>
      <argumentList>
        <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
        <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetSearchCapabilities</name>
      <argumentList>
        <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetSortCapabilities</name>
      <argumentList>
        <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetSystemUpdateID</name>
      <argumentList>
        <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SearchCriteria</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

CMS_SCPD_XML = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""


class DLNAHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write(f"[{time.strftime('%H:%M:%S')}] {self.client_address[0]} - {fmt % args}\n")
        sys.stdout.flush()

    def get_host_ip(self):
        host_hdr = self.headers.get("Host", "")
        if ":" in host_hdr:
            return host_hdr.split(":")[0]
        try:
            return self.connection.getsockname()[0]
        except Exception:
            return get_local_ips()[0]

    def send_xml(self, xml_str, status=200):
        data = xml_str.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def do_SUBSCRIBE(self):
        self.send_response(200)
        self.send_header("SID", f"uuid:{uuid.uuid4()}")
        self.send_header("TIMEOUT", "Second-1800")
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_UNSUBSCRIBE(self):
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_HEAD(self):
        self.send_response(200)
        if self.path.startswith("/stream/"):
            self.send_header("Content-Type", "video/mpeg")
            self.send_header("transferMode.dlna.org", "Streaming")
            self.send_header("contentFeatures.dlna.org", "DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_GET(self):
        if self.path == "/description.xml":
            desc = f"""<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>{xml_escape(SERVER_NAME)}</friendlyName>
    <manufacturer>Antigravity</manufacturer>
    <modelName>FastIPTV Live DLNA Proxy</modelName>
    <modelNumber>1.1</modelNumber>
    <UDN>uuid:{DEVICE_UUID}</UDN>
    <dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMS-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/cds.xml</SCPDURL>
        <controlURL>/cds/control</controlURL>
        <eventSubURL>/cds/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/cms.xml</SCPDURL>
        <controlURL>/cms/control</controlURL>
        <eventSubURL>/cms/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
            return self.send_xml(desc)

        if self.path == "/cds.xml":
            return self.send_xml(CDS_SCPD_XML)

        if self.path == "/cms.xml":
            return self.send_xml(CMS_SCPD_XML)

        if self.path.startswith("/stream/"):
            return self.handle_stream()

        self.send_response(404)
        self.send_header("Connection", "close")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="ignore")
        soap_action = self.headers.get("SOAPACTION", "").strip('"')

        if "GetProtocolInfo" in soap_action or "GetProtocolInfo" in body:
            resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source>http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO</Source>
      <Sink></Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>"""
            return self.send_xml(resp)

        if "GetSystemUpdateID" in soap_action or "GetSystemUpdateID" in body:
            resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetSystemUpdateIDResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Id>1</Id>
    </u:GetSystemUpdateIDResponse>
  </s:Body>
</s:Envelope>"""
            return self.send_xml(resp)

        if "GetSortCapabilities" in soap_action or "GetSortCapabilities" in body:
            resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetSortCapabilitiesResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <SortCaps></SortCaps>
    </u:GetSortCapabilitiesResponse>
  </s:Body>
</s:Envelope>"""
            return self.send_xml(resp)

        if "GetSearchCapabilities" in soap_action or "GetSearchCapabilities" in body:
            resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetSearchCapabilitiesResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <SearchCaps></SearchCaps>
    </u:GetSearchCapabilitiesResponse>
  </s:Body>
</s:Envelope>"""
            return self.send_xml(resp)

        if "Browse" in soap_action or "Browse" in body:
            return self.handle_browse(body)

        self.send_response(500)
        self.send_header("Connection", "close")
        self.end_headers()

    def handle_browse(self, body):
        obj_m = re.search(r"<(?:\w+:)?ObjectID>([^<]+)</(?:\w+:)?ObjectID>", body)
        flag_m = re.search(r"<(?:\w+:)?BrowseFlag>([^<]+)</(?:\w+:)?BrowseFlag>", body)
        start_m = re.search(r"<(?:\w+:)?StartingIndex>(\d+)</(?:\w+:)?StartingIndex>", body)
        count_m = re.search(r"<(?:\w+:)?RequestedCount>(\d+)</(?:\w+:)?RequestedCount>", body)

        object_id = obj_m.group(1).strip() if obj_m else "0"
        browse_flag = flag_m.group(1).strip() if flag_m else "BrowseDirectChildren"
        start_idx = int(start_m.group(1)) if start_m else 0
        req_count = int(count_m.group(1)) if count_m else 0

        global CHANNELS
        with CHANNELS_LOCK:
            CHANNELS = parse_m3u(M3U_PATH)
            channels = list(CHANNELS)

        host_ip = self.get_host_ip()
        protocol_info = "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000"

        didl_items = []
        total_matches = 0

        if browse_flag == "BrowseMetadata":
            if object_id.startswith("ch_"):
                ch_id = int(object_id.split("_")[1])
                ch = next((c for c in channels if c["id"] == ch_id), None)
                if ch:
                    url = f"http://{host_ip}:{HTTP_PORT}/stream/{ch['id']}.mpeg"
                    didl_items.append(
                        f'<item id="ch_{ch["id"]}" parentID="0" restricted="1">'
                        f'<dc:title>{xml_escape(ch["title"])}</dc:title>'
                        f'<upnp:class>object.item.videoItem</upnp:class>'
                        f'<res protocolInfo="{protocol_info}">{url}</res>'
                        f'</item>'
                    )
                    total_matches = 1
            else:
                didl_items.append(
                    f'<container id="{xml_escape(object_id)}" parentID="-1" restricted="1" searchable="0" childCount="{len(channels)}">'
                    f'<dc:title>{xml_escape(SERVER_NAME)}</dc:title>'
                    f'<upnp:class>object.container.storageFolder</upnp:class>'
                    f'</container>'
                )
                total_matches = 1
        else:
            all_entries = []
            for ch in channels:
                url = f"http://{host_ip}:{HTTP_PORT}/stream/{ch['id']}.mpeg"
                all_entries.append(
                    f'<item id="ch_{ch["id"]}" parentID="{xml_escape(object_id)}" restricted="1">'
                    f'<dc:title>{xml_escape(ch["title"])}</dc:title>'
                    f'<upnp:class>object.item.videoItem</upnp:class>'
                    f'<res protocolInfo="{protocol_info}">{url}</res>'
                    f'</item>'
                )

            total_matches = len(all_entries)
            if req_count > 0:
                didl_items = all_entries[start_idx : start_idx + req_count]
            else:
                didl_items = all_entries[start_idx:]

        didl_xml = (
            '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
            'xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">'
            + "".join(didl_items)
            + "</DIDL-Lite>"
        )

        soap_resp = f"""<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Result>{xml_escape(didl_xml)}</Result>
      <NumberReturned>{len(didl_items)}</NumberReturned>
      <TotalMatches>{total_matches}</TotalMatches>
      <UpdateID>1</UpdateID>
    </u:BrowseResponse>
  </s:Body>
</s:Envelope>"""
        return self.send_xml(soap_resp)

    def handle_stream(self):
        m = re.search(r"/stream/(\d+)", self.path)
        if not m:
            self.send_response(404)
            self.end_headers()
            return

        ch_id = int(m.group(1))
        with CHANNELS_LOCK:
            ch = next((c for c in CHANNELS if c["id"] == ch_id), None)

        if not ch:
            self.send_response(404)
            self.end_headers()
            return

        kill_all_active_streams()

        try:
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 16384)
        except Exception:
            pass

        stream_url = resolve_single_variant(ch["url"], timeout=1.8)
        sys.stdout.write(f"[{time.strftime('%H:%M:%S')}] Starting channel #{ch_id} ({ch['raw_title']}) -> {stream_url}\n")
        sys.stdout.flush()

        self.send_response(200)
        self.send_header("Content-Type", "video/mpeg")
        self.send_header("transferMode.dlna.org", "Streaming")
        self.send_header("contentFeatures.dlna.org", "DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=8D700000000000000000000000000000")
        self.send_header("Connection", "close")
        self.end_headers()

        cmd = [
            FFMPEG_PATH,
            "-hide_banner",
            "-loglevel", "error",
            "-user_agent", USER_AGENT,
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_on_network_error", "1",
            "-reconnect_delay_max", "2",
            "-rw_timeout", "8000000",
            "-live_start_index", "-3",
            "-thread_queue_size", "4096",
            "-probesize", "3000000",
            "-analyzeduration", "2000000",
            "-i", stream_url,
            "-map", "0:v:0",
            "-map", "0:a:0?",
            "-sn",
            "-c:v", "copy",
            "-c:a", "ac3",
            "-b:a", "256k",
            "-f", "mpegts",
            "pipe:1"
        ]

        proc_id = uuid.uuid4().hex
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            bufsize=0,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        )
        with ACTIVE_PROCS_LOCK:
            ACTIVE_PROCS[proc_id] = proc

        ring_q = queue.Queue(maxsize=MAX_BUFFER_CHUNKS)
        stop_evt = threading.Event()

        def reader_worker():
            try:
                while not stop_evt.is_set():
                    chunk = proc.stdout.read(STREAM_CHUNK_SIZE)
                    if not chunk:
                        break
                    while not stop_evt.is_set():
                        try:
                            ring_q.put_nowait(chunk)
                            break
                        except queue.Full:
                            try:
                                ring_q.get_nowait()
                            except queue.Empty:
                                pass
            except Exception:
                pass
            finally:
                stop_evt.set()

        t = threading.Thread(target=reader_worker, daemon=True)
        t.start()

        try:
            while not stop_evt.is_set() or not ring_q.empty():
                try:
                    chunk = ring_q.get(timeout=0.5)
                except queue.Empty:
                    if stop_evt.is_set() and proc.poll() is not None:
                        break
                    continue
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            stop_evt.set()
            with ACTIVE_PROCS_LOCK:
                ACTIVE_PROCS.pop(proc_id, None)
            try:
                proc.kill()
            except Exception:
                pass
            sys.stdout.write(f"[{time.strftime('%H:%M:%S')}] Stopped channel #{ch_id} ({ch['raw_title']}) immediately.\n")
            sys.stdout.flush()


def send_ssdp_alive_loop():
    nt_types = [
        ("upnp:rootdevice", f"uuid:{DEVICE_UUID}::upnp:rootdevice"),
        (f"uuid:{DEVICE_UUID}", f"uuid:{DEVICE_UUID}"),
        ("urn:schemas-upnp-org:device:MediaServer:1", f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:device:MediaServer:1"),
        ("urn:schemas-upnp-org:service:ContentDirectory:1", f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:service:ContentDirectory:1"),
        ("urn:schemas-upnp-org:service:ConnectionManager:1", f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:service:ConnectionManager:1"),
    ]
    while True:
        for ip in get_local_ips():
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
                location = f"http://{ip}:{HTTP_PORT}/description.xml"
                for nt, usn in nt_types:
                    msg = (
                        "NOTIFY * HTTP/1.1\r\n"
                        "HOST: 239.255.255.250:1900\r\n"
                        "CACHE-CONTROL: max-age=1800\r\n"
                        f"LOCATION: {location}\r\n"
                        f"NT: {nt}\r\n"
                        "NTS: ssdp:alive\r\n"
                        "SERVER: Windows/10.0 UPnP/1.0 FastIPTV/1.1\r\n"
                        f"USN: {usn}\r\n\r\n"
                    ).encode("utf-8")
                    s.sendto(msg, ("239.255.255.250", 1900))
                s.close()
            except Exception:
                pass
        time.sleep(8)


def ssdp_responder_loop():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", 1900))
        for ip in get_local_ips():
            try:
                mreq = socket.inet_aton("239.255.255.250") + socket.inet_aton(ip)
                sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            except Exception:
                pass
    except Exception as e:
        sys.stdout.write(f"SSDP bind error: {e}\n")
        return

    targets = {
        "upnp:rootdevice": f"uuid:{DEVICE_UUID}::upnp:rootdevice",
        f"uuid:{DEVICE_UUID}": f"uuid:{DEVICE_UUID}",
        "urn:schemas-upnp-org:device:MediaServer:1": f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1": f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:service:ContentDirectory:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1": f"uuid:{DEVICE_UUID}::urn:schemas-upnp-org:service:ConnectionManager:1",
    }

    while True:
        try:
            data, addr = sock.recvfrom(4096)
            text = data.decode("utf-8", errors="ignore")
            if "M-SEARCH" not in text:
                continue
            st_m = re.search(r"ST:\s*([^\r\n]+)", text, re.IGNORECASE)
            if not st_m:
                continue
            st = st_m.group(1).strip()

            local_ip = get_local_ips()[0]
            try:
                probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                probe.connect((addr[0], addr[1]))
                local_ip = probe.getsockname()[0]
                probe.close()
            except Exception:
                pass

            location = f"http://{local_ip}:{HTTP_PORT}/description.xml"
            reply_items = list(targets.items()) if st == "ssdp:all" else ([(st, targets[st])] if st in targets else [])

            for st_val, usn_val in reply_items:
                resp = (
                    "HTTP/1.1 200 OK\r\n"
                    "CACHE-CONTROL: max-age=1800\r\n"
                    "EXT:\r\n"
                    f"LOCATION: {location}\r\n"
                    "SERVER: Windows/10.0 UPnP/1.0 FastIPTV/1.1\r\n"
                    f"ST: {st_val}\r\n"
                    f"USN: {usn_val}\r\n\r\n"
                ).encode("utf-8")
                sock.sendto(resp, addr)
        except Exception:
            pass


def main():
    global CHANNELS
    CHANNELS = parse_m3u(M3U_PATH)
    print(f"Loaded {len(CHANNELS)} channels from {M3U_PATH}")
    print(f"Starting {SERVER_NAME} v1.1 (8MB Ring-Buffer) on port {HTTP_PORT}")

    threading.Thread(target=background_variant_warmer, daemon=True).start()
    threading.Thread(target=send_ssdp_alive_loop, daemon=True).start()
    threading.Thread(target=ssdp_responder_loop, daemon=True).start()

    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), DLNAHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
