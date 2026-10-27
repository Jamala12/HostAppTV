# Synthetic Xtream provider

Local test server for the app. Every channel, title and stream is generated; no provider data is involved.
Requires Python 3 and ffmpeg (with libx264).

```sh
python3 tools/mock-xtream/server.py --port 8099
```

In the emulator, sign in with server `http://10.0.2.2:8099`, username `demo`, password `demo`, and allow HTTP.
The log prints `OPEN`/`CLOSE` for every live stream connection with the current and peak concurrent count.
Fault channels 9001–9004 exercise 404, 401, a silent stream and a dropped stream.
Movie/episode ID 9005 delays nonzero byte-range responses by 18 seconds to check seeking on a slow connection.
Generated media is written to `tools/mock-xtream/media/` (ignored by Git).
