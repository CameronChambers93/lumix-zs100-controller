# Fake Lumix for testing: fakecam.py <bind-ip> <ok|busy> [port] [also-listen-60606]
#
# Phase 5 behaviour: port 80 answers getstate/accctrl like a camera; "busy" answers 503 and listens on :60606.
# Phase 6 additions (environment variables, all optional):
#   FAKE_PHOTO=photo.jpg   served as /DO<anything>.JPG with X-REC_DATE_TIME and every EXIF date rewritten to the time in
#                          the query (?t=2026-11-06T15:30:00, local time) or "a minute ago"
#   FAKE_FRAMES=dir        *.jpg sent by UDP after mode=startstream (each datagram = 32-byte header + one JPEG)
#   FAKE_UDP=127.0.0.1     where the frames go (the emulator's `redir add udp:49199:49199` listens on host loopback)
#   FAKE_CLOCK=compact     which setsetting&type=clock value format is accepted: iso | compact | slash (default none)
# It also tracks cammode (recmode/playmode), logs shutter/zoom/touch/video commands and counts them at /_stats.
import sys, os, re, glob, time, socket, threading, json
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ip, mode = sys.argv[1], sys.argv[2]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 80
PHOTO = open(os.environ["FAKE_PHOTO"], "rb").read() if os.environ.get("FAKE_PHOTO") else None
FRAMES = [open(f, "rb").read() for f in sorted(glob.glob(os.path.join(os.environ["FAKE_FRAMES"], "*.jpg")))] if os.environ.get("FAKE_FRAMES") else []
UDP_HOST = os.environ.get("FAKE_UDP", "127.0.0.1")
CLOCK = os.environ.get("FAKE_CLOCK", "")
state = {"cammode": "play", "stream": None, "sent": 0, "cmds": []}

def reply(result="ok"):
    return ('<?xml version="1.0"?><camrply><result>%s</result><state><batt>2/3</batt><cammode>%s</cammode></state></camrply>' % (result, state["cammode"])).encode()

def streamer(udp_port, token):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    n = 0
    while state["stream"] == token and FRAMES:
        pkt = b"\x00" * 30 + b"\x01\x02" + FRAMES[n % len(FRAMES)]   # header without FF D8
        s.sendto(pkt, (UDP_HOST, udp_port))
        state["sent"] += 1
        n += 1
        time.sleep(0.04)   # 25 fps: the app should thin this to 15
    s.close()

def photo_for(q):
    t = (q.get("t") or [None])[0]
    when = datetime.strptime(t, "%Y-%m-%dT%H:%M:%S") if t else datetime.now() - timedelta(seconds=60)
    exif = when.strftime("%Y:%m:%d %H:%M:%S").encode() + b"\x00"
    data = re.sub(rb"\d{4}:\d\d:\d\d \d\d:\d\d:\d\d\x00", exif, PHOTO)
    return data, when.strftime("%Y-%m-%dT%H:%M:%S")

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, f, *a): print("fakecam", ip, self.path, flush=True)
    def send(self, body, ctype="text/xml", extra=None):
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body); self.wfile.flush()
        if len(body) > 100000:   # let the client read everything before the socket closes (the emulator's NAT lost the tail otherwise)
            try:
                self.connection.shutdown(socket.SHUT_WR); self.connection.settimeout(3); self.connection.recv(1)
            except Exception: pass
    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        if mode == "busy":
            self.send_response(503); self.send_header("Content-Length", "0"); self.end_headers(); return
        u = urlparse(self.path); q = parse_qs(u.query)
        g = lambda k: (q.get(k) or [""])[0]
        if u.path == "/_stats": return self.send(json.dumps({"cammode": state["cammode"], "sent": state["sent"], "cmds": state["cmds"]}).encode(), "application/json")
        if PHOTO and re.match(r"^/D[OST].*\.JPG$", u.path):
            data, rec = photo_for(q)
            if self.command == "HEAD": return self.send_head_only(len(data), rec)
            rng = re.match(r"bytes=(\d+)-", self.headers.get("Range", ""))
            if rng and int(rng.group(1)) < len(data):
                a = int(rng.group(1)); part = data[a:]
                self.send_response(206); self.send_header("Content-Type", "image/jpeg"); self.send_header("Content-Length", str(len(part)))
                self.send_header("Content-Range", "bytes %d-%d/%d" % (a, len(data) - 1, len(data))); self.send_header("X-REC_DATE_TIME", rec); self.end_headers()
                self.wfile.write(part); self.wfile.flush(); return
            return self.send(data, "image/jpeg", {"X-REC_DATE_TIME": rec})
        if "accctrl" in self.path: return self.send(b"ok,ZS100-FAKE", "text/plain")
        m, v = g("mode"), g("value")
        if m in ("camcmd", "startstream", "stopstream", "camctrl", "setsetting"): state["cmds"].append(self.path.split("?", 1)[-1])
        if m == "camcmd" and v == "recmode": state["cammode"] = "rec"
        elif m == "camcmd" and v == "playmode": state["cammode"] = "play"
        elif m == "startstream":
            tok = object(); state["stream"] = tok
            threading.Thread(target=streamer, args=(int(v), tok), daemon=True).start()
        elif m == "stopstream": state["stream"] = None
        elif m == "setsetting" and g("type") == "clock":
            val = g("value")
            form = "iso" if re.match(r"^\d{4}-\d\d-\d\dT", val) else "compact" if re.match(r"^\d{14}$", val) else "slash" if "/" in val else ""
            if form != CLOCK: return self.send(reply("err_param"))
        elif m == "camcmd" and v in ("capture", "video_recstart", "video_recstop", "tele-normal", "wide-normal", "zoomstop"): pass
        elif m == "camcmd": return self.send(reply("err_non_support"))
        self.send(reply())
    def send_head_only(self, n, rec):
        self.send_response(200); self.send_header("Content-Type", "image/jpeg"); self.send_header("Content-Length", str(n)); self.send_header("X-REC_DATE_TIME", rec); self.end_headers()

if mode == "busy" or (len(sys.argv) > 4):
    def l():
        s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind((ip, 60606)); s.listen(5)
        while True: s.accept()[0].close()
    threading.Thread(target=l, daemon=True).start()
ThreadingHTTPServer((ip, port), H).serve_forever()
