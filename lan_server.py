from __future__ import annotations

import cgi
import html
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parent
UPLOADS = ROOT / "uploads"
UPLOADS.mkdir(exist_ok=True)


class LanHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        if self.path not in ("/", "/index.html"):
            return super().do_GET()

        apks = sorted(ROOT.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
        apk_links = "".join(
            f'<li><a href="/{html.escape(p.name)}">{html.escape(p.name)}</a></li>'
            for p in apks
        ) or "<li>No APK available</li>"
        page = f"""<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BC3 Miner LAN Transfer</title>
<style>
body{{font-family:Arial,sans-serif;background:#ff9818;color:#151d28;margin:0;padding:24px}}
.box{{max-width:680px;margin:auto;background:#fff3df;padding:24px;border-radius:18px}}
h1{{margin-top:0}} input,button{{font-size:18px;margin:8px 0;padding:12px}}
button{{background:#151d28;color:white;border:0;border-radius:10px}} a{{color:#8a3d00}}
</style></head><body><div class="box">
<h1>BC3 Miner LAN Transfer</h1>
<h2>Download latest APK</h2><ul>{apk_links}</ul>
<h2>Send a file from this device to the PC</h2>
<form method="post" enctype="multipart/form-data">
<input type="file" name="file" required><br><button type="submit">Upload to PC</button>
</form><p>Uploaded files are saved in the PC's <b>uploads</b> folder.</p>
</div></body></html>"""
        data = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        form = cgi.FieldStorage(
            fp=self.rfile,
            headers=self.headers,
            environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": self.headers.get("Content-Type", "")},
        )
        item = form["file"] if "file" in form else None
        if item is None or not getattr(item, "filename", ""):
            self.send_error(400, "No file selected")
            return
        filename = os.path.basename(item.filename).replace("\x00", "")
        destination = UPLOADS / filename
        stem, suffix, counter = destination.stem, destination.suffix, 1
        while destination.exists():
            destination = UPLOADS / f"{stem}-{counter}{suffix}"
            counter += 1
        with destination.open("wb") as output:
            while chunk := item.file.read(1024 * 1024):
                output.write(chunk)
        message = f"Uploaded successfully: {html.escape(destination.name)}"
        data = f'<html><meta name="viewport" content="width=device-width"><body><h2>{message}</h2><a href="/">Back</a></body></html>'.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    os.chdir(ROOT)
    ThreadingHTTPServer(("0.0.0.0", 8000), LanHandler).serve_forever()
