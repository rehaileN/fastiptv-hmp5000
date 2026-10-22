# FastIPTV (HMP5000/12 Ultra-Lightweight DLNA Server) — v1.3

A lightweight, zero-lag UPnP/DLNA live IPTV server and HLS-to-MPEG-TS proxy built specifically for legacy hardware media players like the **Philips HMP5000/12** (Realtek RTD1185).

## Features (v1.3)
- **Lossless 188-Byte Aligned Blocking Queue (~16.92 MB):** Decouples `ffmpeg` stdout from the TV's TCP socket using `18,800-byte` chunks (`100 * 188-byte` MPEG-TS packets) and a **lossless blocking `put()`** (`900` chunks = `16.92 MB`, holding 30s+ of high-bitrate 4.7 Mbps TRT HD streams) so **not a single byte or H.264 reference frame is ever dropped**.
- **256 KB Socket Send Buffer & Instant Teardown:** Uses a `256 KB` TCP socket send buffer (`SO_SNDBUF`) with `TCP_NODELAY` so large `122 KB` TRT HD keyframes fit in a single TCP window without Wi-Fi ACK stalls, while still draining in `< 0.2s` on client disconnect.
- **O(1) Tail-Scanner Live DVR Playlist Trimmer (`/hls/<id>.m3u8`):** Broadcasters like TRT serve massive 24-hour DVR playlists (`30,246 lines / 878 KB`) whose Akamai edge nodes occasionally pause 6–8s on cache misses. FastIPTV caches trimmed responses for `1.5s`, scans backwards from the end of the playlist to resolve only the last **12 live segments** (`72s` window, `< 1 ms` CPU time, zero GC pressure), and starts `ffmpeg` at `-live_start_index -5` (`30s` live cushion).
- **TRT 44.1 kHz Audio & SCTE-35 Timestamp Sync:** Strips un-timestamped SCTE-35 splice streams (`-dn`), applies asynchronous audio timestamp drift compensation (`-af aresample=async=1:first_pts=0`) for 44.1 kHz -> 48 kHz AC3 conversion, and enforces zero-based monotonic timestamps (`-fflags +genpts+discardcorrupt -avoid_negative_ts make_zero -max_interleave_delta 0`).
- **Zero Background Bandwidth Contention:** Pauses background playlist warm-up completely whenever any live channel is actively streaming.
- **Flat Root Channel List:** Exposes all 55 Turkish national, news, sports, documentary, and music channels directly at the root DLNA folder (`ObjectID=0`).

## Files
- `FastDLNAServer.java` — Main Java 21 UPnP/DLNA server & lossless MPEG-TS proxy (Port `8899`).
- `fast_dlna_server.py` — Equivalent standalone Python 3 implementation.
- `ulusal50.m3u` — Verified 55-channel Turkish live TV playlist.
- `start_fast_iptv.bat` — Windows one-click compiler and background launcher.
