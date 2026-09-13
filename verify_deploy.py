"""部署后自检：用 config.json 的密码走一遍 App 用到的接口，打印关键字段（不输出任何密码/token）。"""
import json
import urllib.error
import urllib.request
from pathlib import Path

CONFIG = json.loads(Path("config.json").read_text(encoding="utf-8"))
BASE = f"http://127.0.0.1:{CONFIG.get('web_port', 8080)}"
TOKEN = {"value": ""}


def call(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    if TOKEN["value"]:
        req.add_header("X-Session-Token", TOKEN["value"])
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode()
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, {"raw": raw[:120]}


status, body = call("POST", "/api/login", {"password": CONFIG["web_password"]})
TOKEN["value"] = body.get("token", "")
print("login:", status, body.get("ok"))

status, body = call("GET", "/api/room")
room = body.get("room", {})
print("GET /api/room:", status)
print("  room_name :", repr(room.get("room_name")))
print("  room_avatar:", (room.get("room_avatar") or "")[:60])
print("  room_intro:", repr(room.get("room_intro")))
print("  error     :", body.get("error"))
for ch in room.get("channels", []):
    print(f"    - {ch['channel_id']} {ch['channel_name']!r} type={ch['channel_type']} "
          f"voice={ch['is_voice']} monitored={ch['monitored']}")

status, body = call("GET", "/api/status")
print("GET /api/status:", status, body.get("status") or body)

status, body = call("GET", "/api/roles")
print("GET /api/roles:", status, "count=", len(body.get("roles", [])))

status, body = call("GET", "/api/history?limit=3")
print("GET /api/history:", status, "total=", body.get("total"), "items=", len(body.get("items", [])))

status, body = call("GET", "/api/config")
cfg = body.get("config", {})
print("GET /api/config:", status, "keys=", len(cfg), "token_leaked=", bool(cfg.get("token")))
print("  edit_enabled:", body.get("edit_enabled"))
