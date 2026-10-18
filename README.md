# FastIPTV (HMP5000/12 Ultra-Lightweight DLNA Server) — v1.2

A lightweight, zero-lag UPnP/DLNA live IPTV server and HLS-to-MPEG-TS proxy built specifically for legacy hardware media players like the **Philips HMP5000/12** (Realtek RTD1185).

## Features (v1.2)
- **Lossless 188-Byte Aligned Blocking Queue (~8.46 MB):** Decouples `ffmpeg` stdout from the TV's TCP socket using `18,800-byte` chunks (exact multiple of `188-byte` MPEG-TS packets) and a **lossless blocking `put()`** so **not a single byte or H.264 reference frame is ever dropped**, eliminating visual macroblocking and stuttering.
- **Instant Channel Exit & Switching (0 ms Teardown):** Uses a `32 KB` TCP socket send buffer (`SO_SNDBUF`) with `TCP_NODELAY` and immediately interrupts the queue and terminates `ffmpeg` on client disconnect.
- **GZIP Live-Window DVR Playlist Trimmer (`/hls/<id>.m3u8`):** Some broadcasters (like TRT) serve massive 24-hour DVR playlists (`30,246 lines / 878 KB`) on every 6-second refresh. FastIPTV downloads them with GZIP in ~25 ms and trims them to the last **6 live segments** (~25 lines) before feeding `ffmpeg`.
- **Hardware Decoder Annex-B & Timestamp Compliance:** Remuxes with `-fflags +genpts`, `-bsf:v h264_mp4toannexb`, and `-c:a ac3 -b:a 384k -ar 48000` for glitch-free playback on Realtek RTD1185 hardware decoders.
- **Zero Background Bandwidth Contention:** Pauses background playlist warm-up completely whenever any live channel is actively streaming.
- **Flat Root Channel List:** Exposes all 55 Turkish national, news, sports, documentary, and music channels directly at the root DLNA folder (`ObjectID=0`).

## Files
- `FastDLNAServer.java` — Main Java 21 UPnP/DLNA server & lossless MPEG-TS proxy (Port `8899`).
- `fast_dlna_server.py` — Equivalent standalone Python 3 implementation.
- `ulusal50.m3u` — Verified 55-channel Turkish live TV playlist.
- `start_fast_iptv.bat` — Windows one-click compiler and background launcher.
