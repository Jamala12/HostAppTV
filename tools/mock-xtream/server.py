#!/usr/bin/env python3
"""
Synthetic Xtream Codes provider for local testing. Contains no real provider data.

  python3 server.py [--port 8099] [--channels 40] [--movies 120]

Account: username "demo", password "demo". The Android emulator reaches the host at http://10.0.2.2:8099.
Live streams are generated on demand by ffmpeg (test pattern + tone) as continuous MPEG-TS, so the
log shows exactly how many stream connections the app holds open at any moment.

Fault channels: 9001 -> HTTP 404, 9002 -> HTTP 401, 9003 -> accepts but never sends data,
9004 -> streams 8 s then drops the connection.
"""
import argparse, base64, json, os, subprocess, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
USER, PASS = "demo", "demo"
ART = os.environ.get("MOCK_ART_BASE", "http://10.0.2.2:8099")
active_streams = 0
lock = threading.Lock()
peak_streams = 0

def log(msg):
    sys.stdout.write(time.strftime("%H:%M:%S ") + msg + "\n"); sys.stdout.flush()

def catalogue(n_channels, n_movies):
    live_cats = [{"category_id": "1", "category_name": "News", "parent_id": 0},
                 {"category_id": "2", "category_name": "Sport", "parent_id": 0},
                 {"category_id": "3", "category_name": "العربية", "parent_id": 0},
                 {"category_id": "9", "category_name": "Fault injection", "parent_id": 0}]
    live = []
    for i in range(1, n_channels + 1):
        cat = str((i % 3) + 1)
        name = {"1": f"Synthetic News {i}", "2": f"Test Sport {i}", "3": f"قناة تجريبية {i}"}[cat]
        live.append({"num": i, "name": name, "stream_type": "live", "stream_id": i, "stream_icon": "",
                     "epg_channel_id": f"ch{i}" if i % 4 else None, "added": str(1700000000 + i), "category_id": cat, "tv_archive": 0})
    for sid, label in [(9001, "Fault: 404 removed"), (9002, "Fault: 401 refused"), (9003, "Fault: silent stream"), (9004, "Fault: drops after 8s")]:
        live.append({"num": sid, "name": label, "stream_id": sid, "stream_icon": None, "category_id": "9", "tv_archive": "0"})

    vod_cats = [{"category_id": "10", "category_name": "Drama"}, {"category_id": "11", "category_name": "Documentary"}, {"category_id": "12", "category_name": "Family"}]
    vod = []
    sid = 5000
    for i in range(n_movies):
        sid += 1
        tmdb = 100000 + i if i % 5 else ""  # every 5th has no TMDB id -> stays separate
        year = 1990 + (i % 34)
        title = f"Synthetic Feature {i:03d}"
        if i == 3: title = "A Deliberately Long Synthetic Title That Must Wrap To Two Lines On The Card"
        if i == 4: title = "فيلم اختبار عربي"
        if i == 6: title = "Café Société: Ünïcödé"
        vod.append({"num": i, "name": f"EN - {title} ({year})", "stream_id": sid, "stream_icon": f"{ART}/art/poster/{sid}.png" if i % 7 else "",
                    "rating": str(round(5 + (i % 50) / 10, 1)), "added": str(1700000000 + i * 3600), "category_id": vod_cats[i % 3]["category_id"],
                    "container_extension": "mp4", "tmdb": str(tmdb), "year": str(year)})
        if i % 10 == 1 and tmdb:  # extra editions sharing the TMDB id
            sid += 1
            vod.append({"num": i, "name": f"FR - {title} [4K]", "stream_id": sid, "stream_icon": f"{ART}/art/poster/{sid}.png", "rating": "7", "added": str(1700000000 + i * 3600),
                        "category_id": vod_cats[i % 3]["category_id"], "container_extension": "mkv", "tmdb": str(tmdb)})
    series_cats = [{"category_id": "20", "category_name": "Series Drama"}]
    series = [{"num": 1, "name": "EN - Synthetic Serial (2021)", "series_id": 700, "cover": f"{ART}/art/poster/700.png", "plot": "A synthetic test series.",
               "category_id": "20", "rating": "8.1", "last_modified": "1710000000", "tmdb": "555"},
              {"num": 2, "name": "AR - مسلسل تجريبي", "series_id": 701, "cover": "", "plot": None, "category_id": "20", "rating": 0, "last_modified": 1700000000}]
    return live_cats, live, vod_cats, vod, series_cats, series

def series_info(series_id):
    eps = {}
    eid = series_id * 100
    for season in (1, 2):
        lst = []
        for e in range(1, 7):
            eid += 1
            lst.append({"id": str(eid), "episode_num": e, "title": f"S{season}E{e} Synthetic episode", "container_extension": "mp4",
                        "season": season, "info": {"duration_secs": 60, "plot": f"Episode {e} of season {season}."}})
        eps[str(season)] = lst
    return {"seasons": [{"season_number": 1}, {"season_number": 2}], "info": {"plot": "A synthetic test series.", "genre": "Drama", "releaseDate": "2021-01-01"}, "episodes": eps}

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a): pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = [p for p in u.path.split("/") if p]
        if parts[:1] == ["player_api.php"]:
            return self.api(q)
        if parts[:1] == ["art"]:
            return self.art(parts)
        if len(parts) == 4 and parts[0] in ("live", "movie", "series"):
            kind, user, pw, file = parts
            if (user, pw) != (USER, PASS):
                return self.send_json({"error": "auth"}, 401)
            sid = int(file.split(".")[0])
            if kind == "live": return self.live(sid)
            return self.vod(file, slow_seek=sid == 9005)
        self.send_json({"error": "not found"}, 404)

    def api(self, q):
        if (q.get("username"), q.get("password")) != (USER, PASS):
            return self.send_json({"user_info": {"auth": 0}})
        a = q.get("action")
        lc, live, vc, vod, sc, series = self.server.cat
        if a is None:
            return self.send_json({"user_info": {"username": USER, "auth": 1, "status": "Active", "exp_date": str(int(time.time()) + 86400 * 30),
                                                 "max_connections": "1", "active_cons": "0", "allowed_output_formats": ["m3u8", "ts"]},
                                   "server_info": {"url": "10.0.2.2", "port": "8099", "server_protocol": "http"}})
        if a == "get_live_categories": return self.send_json(lc)
        if a == "get_vod_categories": return self.send_json(vc)
        if a == "get_series_categories": return self.send_json(sc)
        if a == "get_live_streams": return self.send_json(live)
        if a == "get_vod_streams": return self.send_json(vod)
        if a == "get_series": return self.send_json(series)
        if a == "get_series_info": return self.send_json(series_info(int(q.get("series_id", "0"))))
        if a == "get_vod_info":
            return self.send_json({"info": {"plot": "A synthetic feature generated for testing playback, editions and resume.", "genre": "Test", "duration_secs": 120,
                                            "releasedate": "2020-01-01", "cast": "Nobody Real"}, "movie_data": {"stream_id": q.get("vod_id"), "container_extension": "mp4"}})
        if a == "get_short_epg":
            sid = int(q.get("stream_id", "0"))
            if sid % 4 == 0: return self.send_json({"epg_listings": []})
            now = int(time.time()) // 1800 * 1800
            b = lambda s: base64.b64encode(s.encode()).decode()
            return self.send_json({"epg_listings": [
                {"title": b(f"Synthetic programme {sid}"), "description": b("Test description"), "start_timestamp": str(now), "stop_timestamp": str(now + 1800)},
                {"title": b("Up next: test card"), "description": b(""), "start_timestamp": str(now + 1800), "stop_timestamp": str(now + 3600)}]})
        self.send_json([])

    def art(self, parts):
        path = os.path.join(MEDIA, "poster.png")
        if not os.path.exists(path): return self.send_json({}, 404)
        data = open(path, "rb").read()
        self.send_response(200); self.send_header("Content-Type", "image/png"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def live(self, sid):
        global active_streams, peak_streams
        if sid == 9001: return self.send_json({"error": "gone"}, 404)
        if sid == 9002: return self.send_json({"error": "refused"}, 401)
        self.send_response(200); self.send_header("Content-Type", "video/mp2t"); self.send_header("Connection", "close"); self.end_headers()
        with lock:
            active_streams += 1; peak_streams = max(peak_streams, active_streams)
            log(f"OPEN live {sid}  active={active_streams} peak={peak_streams}")
        proc = None
        start = time.time()
        try:
            if sid == 9003:
                # MPEG-TS null packets only: keeps the socket observable without delivering any media.
                null = b"\x47\x1f\xff\x10" + b"\xff" * 184
                while time.time() - start < 120:
                    self.wfile.write(null); self.wfile.flush(); time.sleep(0.5)
                return
            proc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-re", "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate=25",
                                     "-f", "lavfi", "-i", f"sine=frequency={300 + sid * 10}:sample_rate=48000",
                                     "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "25", "-b:v", "2500k",
                                     "-c:a", "aac", "-b:a", "128k", "-f", "mpegts", "pipe:1"], stdout=subprocess.PIPE)
            while True:
                chunk = proc.stdout.read(188 * 64)
                if not chunk: break
                self.wfile.write(chunk)
                if sid == 9004 and time.time() - start > 8: break
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            if proc: proc.kill(); proc.wait()
            with lock:
                active_streams -= 1
                log(f"CLOSE live {sid} after {time.time() - start:.1f}s  active={active_streams}")
            self.close_connection = True

    def vod(self, file, slow_seek=False):
        ext = file.rsplit(".", 1)[-1]
        path = os.path.join(MEDIA, "feature.mkv" if ext == "mkv" else "feature.mp4")
        if not os.path.exists(path): return self.send_json({}, 404)
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng and rng.startswith("bytes="):
            s, _, e = rng[6:].partition("-")
            start = int(s) if s else 0
            end = int(e) if e else size - 1
            self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Accept-Ranges", "bytes"); self.send_header("Content-Type", "video/x-matroska" if ext == "mkv" else "video/mp4")
        self.send_header("Content-Length", str(end - start + 1)); self.end_headers()
        log(f"VOD {file} range={start}-{end}")
        try:
            if slow_seek and start > 0:
                # Out-of-buffer seeks take longer than the live-only 15-second deadline.
                time.sleep(18)
            with open(path, "rb") as f:
                f.seek(start); left = end - start + 1
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk: break
                    self.wfile.write(chunk); left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

def make_media():
    os.makedirs(MEDIA, exist_ok=True)
    mp4 = os.path.join(MEDIA, "feature.mp4"); mkv = os.path.join(MEDIA, "feature.mkv"); srt = os.path.join(MEDIA, "subs.srt")
    if not os.path.exists(srt):
        open(srt, "w").write("".join(f"{i+1}\n00:00:{i*5:02d},000 --> 00:00:{i*5+4:02d},000\nSynthetic subtitle {i+1}\n\n" for i in range(11)))
    if not os.path.exists(mp4):
        log("generating feature.mp4 (120 s, 1080p H.264, two AAC tracks)")
        subprocess.check_call(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=25:duration=120",
                               "-f", "lavfi", "-i", "sine=frequency=440:duration=120", "-f", "lavfi", "-i", "sine=frequency=660:duration=120",
                               "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-preset", "veryfast", "-b:v", "4000k", "-g", "50",
                               "-c:a", "aac", "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=fra", "-movflags", "+faststart", mp4])
    if not os.path.exists(mkv):
        log("generating feature.mkv (adds SRT subtitles)")
        subprocess.check_call(["ffmpeg", "-loglevel", "error", "-y", "-i", mp4, "-i", srt, "-map", "0", "-map", "1", "-c", "copy", "-c:s", "srt",
                               "-metadata:s:s:0", "language=eng", mkv])

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099); ap.add_argument("--channels", type=int, default=40); ap.add_argument("--movies", type=int, default=120)
    args = ap.parse_args()
    make_media()
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    srv.daemon_threads = True
    srv.cat = catalogue(args.channels, args.movies)
    log(f"mock Xtream provider on :{args.port} (demo/demo)")
    srv.serve_forever()
