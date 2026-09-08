import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from . import core
from . import analysis

BASE_DIR = Path(__file__).resolve().parent
HTML_PATH = BASE_DIR / "dashboard.html"
COLOR_PRESETS_PATH = BASE_DIR / "color_presets.json"
LOCAL_COLOR_PRESETS_PATH = BASE_DIR / "color_presets.local.json"


def _read_presets(path: Path):
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def _valid_stops(stops):
    if not isinstance(stops, list) or len(stops) < 2:
        return False
    for stop in stops:
        if not isinstance(stop, dict):
            return False
        pos, color = stop.get("pos"), stop.get("color")
        if not isinstance(pos, (int, float)) or not 0 <= pos <= 1:
            return False
        if not isinstance(color, str) or len(color) != 7 or not color.startswith("#"):
            return False
        try:
            int(color[1:], 16)
        except ValueError:
            return False
    return True


def api_color_presets():
    standard = _read_presets(COLOR_PRESETS_PATH)
    local = _read_presets(LOCAL_COLOR_PRESETS_PATH)
    return {"standard": standard, "local": local, "presets": {**standard, **local}}


def save_color_preset(payload):
    action = payload.get("action")
    name = str(payload.get("name", "")).strip()
    if not name:
        raise ValueError("preset name is required")
    local = _read_presets(LOCAL_COLOR_PRESETS_PATH)
    if action == "save":
        stops = payload.get("stops")
        if not _valid_stops(stops):
            raise ValueError("invalid color stops")
        local[name] = [{"pos": float(s["pos"]), "color": s["color"]} for s in stops]
    elif action == "delete":
        local.pop(name, None)
    else:
        raise ValueError("unknown action")
    tmp = LOCAL_COLOR_PRESETS_PATH.with_suffix(".local.json.tmp")
    tmp.write_text(json.dumps(local, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(LOCAL_COLOR_PRESETS_PATH)
    return api_color_presets()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # quiet; the terminal is for run.sh output

    def _send(self, code, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj):
        self._send(200, json.dumps(obj).encode(), "application/json")

    def _read_json_body(self):
        n = int(self.headers.get("Content-Length", "0") or "0")
        return json.loads(self.rfile.read(n).decode("utf-8") or "{}")

    def do_GET(self):
        u = urlparse(self.path)
        qs = parse_qs(u.query)
        try:
            if u.path == "/" or u.path == "/index.html":
                self._send(200, HTML_PATH.read_bytes(), "text/html; charset=utf-8")
            elif u.path == "/api/summary":
                self._json(core.api_summary())
            elif u.path == "/api/series":
                self._json(core.api_series(qs))
            elif u.path == "/api/opinion":
                self._json(core.api_opinion(qs))
            elif u.path == "/api/repost":
                self._json(core.api_repost(qs))
            elif u.path == "/api/post_lifespan":
                self._json(core.api_post_lifespan(qs))
            elif u.path == "/api/network":
                self._json(analysis.api_network(qs))
            elif u.path == "/api/trajectories":
                self._json(analysis.api_trajectories(qs))
            elif u.path == "/api/color-presets":
                self._json(api_color_presets())
            elif u.path == "/api/groups":
                self._json(core.api_groups())
            elif u.path == "/api/activity":
                self._json(core.api_activity())
            elif u.path == "/api/log":
                self._send(200, core.api_log(qs).encode(), "text/plain; charset=utf-8")
            elif u.path == "/favicon.ico":
                self._send(204, b"", "image/x-icon")
            else:
                self._send(404, b"not found", "text/plain")
        except BrokenPipeError:
            pass
        except Exception as e:
            self._send(500, f"{type(e).__name__}: {e}".encode(), "text/plain")

    def do_POST(self):
        u = urlparse(self.path)
        try:
            if u.path == "/api/color-presets":
                self._json(save_color_preset(self._read_json_body()))
            elif u.path == "/api/group":
                self._json(core.set_group(self._read_json_body().get("prefix")))
            else:
                self._send(404, b"not found", "text/plain")
        except BrokenPipeError:
            pass
        except Exception as e:
            self._send(500, f"{type(e).__name__}: {e}".encode(), "text/plain")


def run_server(logdir: Path, only, host: str, port: int):
    core.SERVE_LOGDIR = logdir
    core.SERVE_ONLY = only
    core.SERVE_GROUP = core.pick_default_group(logdir)
    httpd = ThreadingHTTPServer((host, port), Handler)
    url = f"http://{host}:{port}"
    print(f"Serving interactive dashboard at {url}")
    print("Over SSH / VSCode Remote-SSH: check the PORTS tab for an auto-forward "
          "toast, or Cmd/Ctrl+Shift+P -> 'Simple Browser: Show' -> paste the URL above.")
    print("Ctrl-C to stop (does not affect the simulations).")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
