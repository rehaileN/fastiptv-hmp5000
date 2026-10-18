# FastIPTV (HMP5000/12 Ultra-Lightweight DLNA Server)

A lightweight, zero-lag UPnP/DLNA live IPTV server and HLS-to-MPEG-TS proxy built specifically for legacy hardware media players like the **Philips HMP5000/12** (Realtek RTD1185).

## Features
- **Instant Channel Exit & Switching (0 ms Teardown):** Uses a small `16 KB` TCP socket send buffer (`SO_SNDBUF`) with `TCP_NODELAY` and immediately terminates `ffmpeg` on client disconnect.
- **8 MB Asynchronous Ring-Buffer Queue:** Decouples `ffmpeg` stdout from the TV's TCP socket so that when the media player's hardware decoder pauses reading, `ffmpeg` never blocks and continues fetching HLS `.ts` segments smoothly without falling behind the live edge.
- **Fast Channel Start (~0.5s):** Pre-resolves multi-variant `master.m3u8` playlists in the background to their direct 720p/1080p stream URLs so `ffmpeg` doesn't waste time probing 5–13 quality variants.
- **Resilient Live HLS Playback:** Uses `-live_start_index -3` and automatic HTTP stream reconnection (`-reconnect 1 -reconnect_streamed 1 -reconnect_on_network_error 1`) to absorb CDN micro-hiccups.
- **Flat Root Channel List:** Exposes all 55 Turkish national, news, sports, documentary, and music channels directly at the root DLNA folder (`ObjectID=0`).

## Files
- `FastDLNAServer.java` — Main Java 21 UPnP/DLNA server & async ring-buffered FFmpeg proxy (Port `8899`).
- `fast_dlna_server.py` — Equivalent standalone Python 3 implementation.
- `ulusal50.m3u` — Verified 55-channel Turkish live TV playlist.
- `start_fast_iptv.bat` — Windows one-click compiler and background launcher.
