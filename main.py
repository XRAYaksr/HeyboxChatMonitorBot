import hashlib
import hmac
import json
import secrets
import sqlite3
import threading
import time
from datetime import datetime, timedelta
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import requests

BASE_URL = "https://chat.xiaoheihe.cn"
CONFIG_FILE = Path("config.json")
DB_FILE = Path("voice_monitor.db")

BOT_TOKEN = ""
BOT_ID = ""

WEB_HOST = "0.0.0.0"
WEB_PORT = 8080

SESSION_COOKIE_NAME = "heybox_session"
SESSION_TTL_SECONDS = 7 * 24 * 3600
LOGIN_MAX_ATTEMPTS = 5
LOGIN_WINDOW_SECONDS = 300

COMMON_PARAMS = {
    "client_type": "heybox_chat",
    "x_client_type": "web",
    "os_type": "web",
    "x_os_type": "bot",
    "x_app": "heybox_chat",
    "chat_os_type": "bot",
    "chat_version": "1.30.0",
}

ROOM_USER_PAGE_LIMIT = 300
NICKNAME_FAIL_RETRY_SECONDS = 300
NICKNAME_EXPIRE_SECONDS = 3600


def load_config():
    if not CONFIG_FILE.exists():
        raise SystemExit("找不到 config.json，请先运行：python setup.py")
    config = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    if BOT_TOKEN.strip():
        config["token"] = BOT_TOKEN.strip()
    if BOT_ID.strip():
        config["heybox_id"] = str(BOT_ID).strip()
    return config


def fmt(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S")


class NicknameCache:
    """通过「获取房间用户列表」接口建立 UID → 昵称/头像 的缓存。"""

    def __init__(self, config):
        self.config = config
        self.lock = threading.RLock()
        self.entries = {}
        self.last_full_fetch = None
        self.fetching = False

    def _request(self, offset, limit):
        headers = {"token": self.config["token"]}
        params = dict(COMMON_PARAMS)
        params.update({
            "heybox_id": str(self.config["heybox_id"]),
            "room_id": str(self.config["room_id"]),
            "offset": str(offset),
            "limit": str(limit),
        })
        response = requests.get(
            BASE_URL + "/chatroom/v2/room/users",
            params=params,
            headers=headers,
            timeout=15,
        )
        response.raise_for_status()
        data = response.json()
        if data.get("status") != "ok":
            raise RuntimeError("API返回错误：" + json.dumps(data, ensure_ascii=False))
        room_info = data.get("result", {}).get("room_info", {})
        user_count = room_info.get("user_count")
        return room_info.get("user_info") or [], user_count

    @staticmethod
    def _pick_name(info):
        return info.get("room_nickname") or info.get("nickname") or info.get("username") or ""

    def _store(self, infos):
        now = datetime.now()
        for info in infos:
            user_id = str(info.get("user_id", "")).strip()
            if not user_id:
                continue
            self.entries[user_id] = {
                "name": self._pick_name(info),
                "avatar": info.get("avatar") or "",
                "fetched_at": now,
            }

    def refresh_all(self):
        """全量拉取房间用户列表（分页）。失败时保持旧缓存，返回错误信息。"""
        with self.lock:
            if self.fetching:
                return None
            self.fetching = True
        try:
            users = []
            offset = 0
            while True:
                page, user_count = self._request(offset, ROOM_USER_PAGE_LIMIT)
                users.extend(page)
                offset += ROOM_USER_PAGE_LIMIT
                if len(page) < ROOM_USER_PAGE_LIMIT:
                    break
                if isinstance(user_count, int) and offset >= user_count:
                    break
            with self.lock:
                self._store(users)
                self.last_full_fetch = datetime.now()
            print(f"昵称缓存已刷新：共 {len(users)} 名房间用户")
            return None
        except Exception as exc:
            print(f"刷新房间用户列表失败：{exc}")
            return str(exc)
        finally:
            with self.lock:
                self.fetching = False

    def ensure_fresh(self):
        """缓存过期时在后台线程中刷新，不阻塞监控轮询。"""
        with self.lock:
            if self.fetching:
                return
            if self.last_full_fetch is not None:
                if datetime.now() - self.last_full_fetch < timedelta(seconds=NICKNAME_EXPIRE_SECONDS):
                    return
        threading.Thread(target=self.refresh_all, daemon=True).start()

    def lookup(self, user_id):
        user_id = str(user_id)
        with self.lock:
            self.ensure_fresh()
            entry = self.entries.get(user_id)
            if entry is None:
                if not self.fetching and (self.last_full_fetch is None or
                        datetime.now() - self.last_full_fetch > timedelta(seconds=NICKNAME_FAIL_RETRY_SECONDS)):
                    threading.Thread(target=self.refresh_all, daemon=True).start()
                return {"name": "", "avatar": ""}
            if not self.fetching and datetime.now() - entry["fetched_at"] > timedelta(seconds=NICKNAME_EXPIRE_SECONDS):
                threading.Thread(target=self.refresh_all, daemon=True).start()
            return {"name": entry["name"], "avatar": entry["avatar"]}

    def display(self, user_id):
        info = self.lookup(user_id)
        return info["name"] or user_id


class AuthManager:
    """基于密码哈希的访问控制：登录换取会话令牌，令牌有效期 7 天。"""

    def __init__(self, config):
        self.lock = threading.RLock()
        self.sessions = {}
        self.failures = []
        password = str(config.get("web_password", "")).strip()
        if not password:
            raise SystemExit(
                "config.json 中缺少 web_password。\n"
                "请设置面板访问密码，例如：\"web_password\": \"你的密码\""
            )
        self.password_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()

    def _locked(self, now):
        cutoff = now - timedelta(seconds=LOGIN_WINDOW_SECONDS)
        self.failures = [t for t in self.failures if t > cutoff]
        return len(self.failures) >= LOGIN_MAX_ATTEMPTS

    def attempt(self, password):
        """校验密码。返回 (是否成功, 错误信息)。"""
        now = datetime.now()
        with self.lock:
            if self._locked(now):
                return False, f"尝试次数过多，请 {LOGIN_WINDOW_SECONDS // 60} 分钟后再试"
            candidate = hashlib.sha256(password.encode("utf-8")).hexdigest()
            if hmac.compare_digest(candidate, self.password_hash):
                token = secrets.token_hex(32)
                self.sessions[token] = now + timedelta(seconds=SESSION_TTL_SECONDS)
                self.failures = []
                return True, token
            self.failures.append(now)
            remaining = LOGIN_MAX_ATTEMPTS - len(self.failures)
            return False, f"密码错误（{LOGIN_WINDOW_SECONDS // 60} 分钟内剩余 {max(remaining, 0)} 次尝试机会）"

    def verify(self, token):
        if not token:
            return False
        now = datetime.now()
        with self.lock:
            expires = self.sessions.get(token)
            if expires is None:
                return False
            if expires < now:
                self.sessions.pop(token, None)
                return False
            return True

    def logout(self, token):
        with self.lock:
            self.sessions.pop(token, None)


def duration_text(seconds):
    if seconds is None:
        return "-"
    seconds = max(0, int(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{h}小时{m}分{s}秒"
    if m:
        return f"{m}分{s}秒"
    return f"{s}秒"


def init_db():
    # check_same_thread=False：连接由主线程创建，但 Web 服务线程也需要查询。
    # 所有读写都通过 Monitor.lock 串行化，跨线程使用是安全的。
    conn = sqlite3.connect(DB_FILE, check_same_thread=False)
    columns = conn.execute("PRAGMA table_info(voice_sessions)").fetchall()
    if not columns:
        conn.execute("""
            CREATE TABLE voice_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                join_time TEXT,
                leave_time TEXT,
                duration_seconds INTEGER
            )
        """)
    else:
        join_col = next((row for row in columns if row[1] == "join_time"), None)
        if join_col and join_col[3] == 1:
            conn.execute("ALTER TABLE voice_sessions RENAME TO voice_sessions_old")
            conn.execute("""
                CREATE TABLE voice_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    channel_id TEXT NOT NULL,
                    join_time TEXT,
                    leave_time TEXT,
                    duration_seconds INTEGER
                )
            """)
            conn.execute("""
                INSERT INTO voice_sessions
                (id, user_id, channel_id, join_time, leave_time, duration_seconds)
                SELECT id, user_id, channel_id, join_time, leave_time, duration_seconds
                FROM voice_sessions_old
            """)
            conn.execute("DROP TABLE voice_sessions_old")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_voice_user ON voice_sessions(user_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_voice_channel ON voice_sessions(channel_id)")
    conn.commit()
    return conn


def get_user_ids(config, channel_id):
    headers = {"token": config["token"]}
    params = dict(COMMON_PARAMS)
    params.update({
        "channel_id": str(channel_id),
        "room_id": str(config["room_id"]),
        "heybox_id": str(config["heybox_id"]),
    })
    response = requests.get(
        BASE_URL + "/chatroom/v2/channel/user/list",
        params=params,
        headers=headers,
        timeout=15,
    )
    response.raise_for_status()
    data = response.json()
    if data.get("status") != "ok":
        raise RuntimeError("API返回错误：" + json.dumps(data, ensure_ascii=False))
    user_ids = data.get("result", {}).get("user_ids")
    if user_ids is None:
        return set()
    if not isinstance(user_ids, list):
        raise RuntimeError("result.user_ids 格式异常：" + json.dumps(data, ensure_ascii=False))
    return {str(x) for x in user_ids}


class Monitor:
    def __init__(self, config, conn, nicknames=None):
        self.config = config
        self.conn = conn
        self.lock = threading.RLock()
        self.channel_ids = [str(x) for x in config.get("channel_ids", [])]
        self.interval = max(1, int(config.get("poll_interval", 5)))
        self.online = {}
        self.last_update = None
        self.last_errors = {}
        self.nicknames = nicknames
        self.web_port = WEB_PORT

    def display_user(self, user_id):
        if self.nicknames is None:
            return user_id
        return self.nicknames.display(user_id)

    def open_session(self, user_id, channel_id, when):
        cur = self.conn.execute(
            "INSERT INTO voice_sessions (user_id, channel_id, join_time) VALUES (?, ?, ?)",
            (user_id, channel_id, fmt(when)),
        )
        self.conn.commit()
        return cur.lastrowid

    def record_startup_user_left(self, user_id, channel_id, when):
        self.conn.execute(
            """INSERT INTO voice_sessions
               (user_id, channel_id, join_time, leave_time, duration_seconds)
               VALUES (?, ?, NULL, ?, NULL)""",
            (user_id, channel_id, fmt(when)),
        )
        self.conn.commit()

    def close_session(self, session_id, when):
        row = self.conn.execute(
            "SELECT join_time FROM voice_sessions WHERE id = ?", (session_id,)
        ).fetchone()
        if not row:
            return None
        joined = datetime.strptime(row[0], "%Y-%m-%d %H:%M:%S")
        seconds = max(0, int((when - joined).total_seconds()))
        self.conn.execute(
            "UPDATE voice_sessions SET leave_time = ?, duration_seconds = ? WHERE id = ?",
            (fmt(when), seconds, session_id),
        )
        self.conn.commit()
        return seconds

    def initialize(self):
        print("正在获取初始在线状态...")
        if not self.channel_ids:
            raise SystemExit("config.json 中没有 channel_ids，请重新运行 python setup.py")
        for channel_id in self.channel_ids:
            try:
                users = get_user_ids(self.config, channel_id)
                self.last_errors.pop(channel_id, None)
            except Exception as exc:
                self.last_errors[channel_id] = str(exc)
                print(f"频道 {channel_id} 初始化失败：{exc}")
                continue
            self.online[channel_id] = {user_id: None for user_id in users}
            print(f"频道 {channel_id}: 当前 {len(users)} 人在线")
        self.last_update = datetime.now()
        print("初始化完成。\n")

    def check_once(self):
        current_time = datetime.now()
        for channel_id in self.channel_ids:
            try:
                current = get_user_ids(self.config, channel_id)
                self.last_errors.pop(channel_id, None)
            except Exception as exc:
                self.last_errors[channel_id] = str(exc)
                print(f"[{fmt(current_time)}] 频道 {channel_id} 请求失败：{exc}")
                continue
            with self.lock:
                previous_map = self.online.setdefault(channel_id, {})
                previous = set(previous_map)
                joined = current - previous
                left = previous - current

                for user_id in sorted(joined):
                    session_id = self.open_session(user_id, channel_id, current_time)
                    previous_map[user_id] = session_id
                    print(f"[{fmt(current_time)}] + 用户 {self.display_user(user_id)}（{user_id}）进入频道 {channel_id}")

                for user_id in sorted(left):
                    session_id = previous_map.pop(user_id, None)
                    if session_id is None:
                        self.record_startup_user_left(user_id, channel_id, current_time)
                        print(f"[{fmt(current_time)}] - 用户 {self.display_user(user_id)}（{user_id}）离开频道 {channel_id}（程序启动时已在线，仅记录下线时间）")
                    else:
                        seconds = self.close_session(session_id, current_time)
                        print(f"[{fmt(current_time)}] - 用户 {self.display_user(user_id)}（{user_id}）离开频道 {channel_id}，在线 {duration_text(seconds)}")
                self.last_update = current_time

    def run(self):
        self.initialize()
        print(f"开始监控 {len(self.channel_ids)} 个频道，轮询间隔 {self.interval} 秒。")
        print(f"网页地址：http://127.0.0.1:{self.web_port}")
        print("按 Ctrl+C 停止。\n")
        while True:
            self.check_once()
            time.sleep(self.interval)


def dashboard(monitor, channel_filter="", user_filter=""):
    with monitor.lock:
        now = datetime.now()
        online_rows = []
        for channel_id, users in monitor.online.items():
            if channel_filter and channel_id != channel_filter:
                continue
            for user_id, session_id in users.items():
                user_info = monitor.nicknames.lookup(user_id) if monitor.nicknames else {"name": "", "avatar": ""}
                if user_filter and user_filter not in user_id and user_filter not in (user_info["name"] or ""):
                    continue
                join_time = None
                if session_id is not None:
                    row = monitor.conn.execute(
                        "SELECT join_time FROM voice_sessions WHERE id = ?", (session_id,)
                    ).fetchone()
                    if row:
                        join_time = row[0]
                current_seconds = None
                if join_time:
                    current_seconds = int((now - datetime.strptime(join_time, "%Y-%m-%d %H:%M:%S")).total_seconds())
                online_rows.append({
                    "user_id": user_id,
                    "username": user_info["name"],
                    "avatar": user_info["avatar"],
                    "channel_id": channel_id,
                    "join_time": join_time,
                    "current_seconds": current_seconds,
                })

        base_sql = """SELECT id, user_id, channel_id, join_time, leave_time, duration_seconds
                      FROM voice_sessions"""
        clauses, params = [], []
        if channel_filter:
            clauses.append("channel_id = ?")
            params.append(channel_filter)
        where_sql = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        order_sql = " ORDER BY COALESCE(leave_time, join_time) DESC"

        if user_filter:
            # UID 匹配：交给 SQL 在全量历史中检索
            id_where = where_sql + (" AND" if clauses else " WHERE") + " user_id LIKE ?"
            rows = monitor.conn.execute(
                base_sql + id_where + order_sql + " LIMIT 100",
                params + [f"%{user_filter}%"],
            ).fetchall()
            # 用户名匹配：名字不在数据库中，对最近记录做内存筛选后合并
            rows_recent = monitor.conn.execute(
                base_sql + where_sql + order_sql + " LIMIT 100", params
            ).fetchall()
            seen = {r[0] for r in rows}
            rows.extend(r for r in rows_recent if r[0] not in seen)
            rows.sort(key=lambda r: r[4] or r[3] or "", reverse=True)
        else:
            rows = monitor.conn.execute(
                base_sql + where_sql + order_sql + " LIMIT 100", params
            ).fetchall()

        history = []
        for r in rows:
            user_id = r[1]
            user_info = monitor.nicknames.lookup(user_id) if monitor.nicknames else {"name": "", "avatar": ""}
            if user_filter and user_filter not in user_id and user_filter not in (user_info["name"] or ""):
                continue
            history.append({
                "user_id": user_id,
                "username": user_info["name"],
                "avatar": user_info["avatar"],
                "channel_id": r[2],
                "join_time": r[3],
                "leave_time": r[4],
                "duration": duration_text(r[5]),
            })

        return {
            "online": online_rows,
            "history": history,
            "last_update": fmt(monitor.last_update) if monitor.last_update else "-",
            "errors": dict(monitor.last_errors),
            "channels": monitor.channel_ids,
        }


HTML = r"""<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>黑盒语音监控</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
.wrap{max-width:1200px;margin:auto;padding:20px}h1{margin:0 0 18px;font-size:26px}
.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:16px}.card,.panel{background:white;border:1px solid #e5e7eb;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.04)}
.card{padding:16px}.label{font-size:13px;color:#6b7280}.value{font-size:28px;font-weight:700;margin-top:5px}.panel{padding:16px;margin-bottom:16px}
.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}input{padding:9px 11px;border:1px solid #d1d5db;border-radius:8px;min-width:190px}
button{padding:9px 14px;border:0;border-radius:8px;cursor:pointer;background:#111827;color:white}
table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:10px 8px;border-bottom:1px solid #eef0f3}th{color:#6b7280;font-weight:600}
.online{font-weight:600}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#16a34a;margin-right:6px}.muted{color:#6b7280}
.avatar{width:28px;height:28px;border-radius:50%;vertical-align:middle;margin-right:8px;object-fit:cover;background:#e5e7eb}
.uname{font-weight:600}.uid{color:#6b7280;font-size:12px}
.err{padding:10px;border-radius:8px;background:#fff7ed;color:#9a3412;margin-bottom:12px}
.logout{background:#6b7280}
@media(max-width:700px){.wrap{padding:12px}.cards{grid-template-columns:1fr 1fr}.card:last-child{grid-column:1/-1}table{font-size:12px}th,td{padding:8px 5px}}
</style></head><body><div class="wrap">
<h1>黑盒语音频道监控</h1>
<div class="cards">
<div class="card"><div class="label">当前在线人数</div><div class="value" id="onlineCount">-</div></div>
<div class="card"><div class="label">监控频道数</div><div class="value" id="channelCount">-</div></div>
<div class="card"><div class="label">最后更新</div><div class="value" style="font-size:18px" id="lastUpdate">-</div><a href="/logout" style="font-size:13px;color:#6b7280">退出登录</a></div>
</div>
<div id="errors"></div>
<div class="panel"><h2>当前在线</h2>
<div class="toolbar"><input id="userFilter" placeholder="按用户名或用户ID筛选"><input id="channelFilter" placeholder="按频道ID筛选"><button onclick="loadData()">刷新</button></div>
<table><thead><tr><th>状态</th><th>用户</th><th>频道ID</th><th>进入时间</th><th>本次在线</th></tr></thead><tbody id="onlineBody"></tbody></table></div>
<div class="panel"><h2>最近记录</h2>
<table><thead><tr><th>用户</th><th>频道ID</th><th>上线时间</th><th>下线时间</th><th>在线时长</th></tr></thead><tbody id="historyBody"></tbody></table></div>
</div>
<script>
function esc(s){return String(s??"-").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]))}
function duration(sec){if(sec===null||sec===undefined)return "-";sec=Math.max(0,Math.floor(sec));let h=Math.floor(sec/3600);sec%=3600;let m=Math.floor(sec/60),s=sec%60;return h?`${h}小时${m}分${s}秒`:m?`${m}分${s}秒`:`${s}秒`}
function userCell(x){
 const avatar=x.avatar?`<img class="avatar" src="${esc(x.avatar)}" alt="" onerror="this.style.display='none'">`:`<span class="avatar"></span>`;
 const name=x.username?esc(x.username):`<span class="muted">未知用户</span>`;
 return `${avatar}<span class="uname">${name}</span> <span class="uid">${esc(x.user_id)}</span>`;
}
async function loadData(){
 const q=new URLSearchParams(),user=document.getElementById("userFilter").value.trim(),channel=document.getElementById("channelFilter").value.trim();
 if(user)q.set("user",user);if(channel)q.set("channel",channel);
 const resp=await fetch("/api/data?"+q);
 if(resp.status===401||resp.redirected&&resp.url.includes("/login")){location.href="/login";return}
 const d=await resp.json();
 if(d.error){document.getElementById("errors").innerHTML=`<div class="err">${esc(d.error)}</div>`;return}
 document.getElementById("onlineCount").textContent=d.online.length;
 document.getElementById("channelCount").textContent=d.channels.length;
 document.getElementById("lastUpdate").textContent=d.last_update;
 const errors=Object.entries(d.errors);
 document.getElementById("errors").innerHTML=errors.length?errors.map(([c,e])=>`<div class="err">频道 ${esc(c)}：${esc(e)}</div>`).join(""):"";
 document.getElementById("onlineBody").innerHTML=d.online.length?d.online.map(x=>`<tr><td class="online"><span class="dot"></span>在线</td><td>${userCell(x)}</td><td>${esc(x.channel_id)}</td><td>${esc(x.join_time||"程序启动时已在线")}</td><td>${duration(x.current_seconds)}</td></tr>`).join(""):`<tr><td colspan="5" class="muted">当前没有在线用户</td></tr>`;
 document.getElementById("historyBody").innerHTML=d.history.length?d.history.map(x=>`<tr><td>${userCell(x)}</td><td>${esc(x.channel_id)}</td><td>${esc(x.join_time||"程序启动时已在线")}</td><td>${esc(x.leave_time||"在线中")}</td><td>${esc(x.duration)}</td></tr>`).join(""):`<tr><td colspan="5" class="muted">暂无记录</td></tr>`;
}
loadData();setInterval(loadData,5000);
for(const id of ["userFilter","channelFilter"])document.getElementById(id).addEventListener("keydown",e=>{if(e.key==="Enter")loadData()});
</script></body></html>"""


def escape_html(text):
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
    )


LOGIN_HTML = r"""<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>登录 - 黑盒语音监控</title>
<style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f5f7fb;color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
.box{width:min(90vw,360px);background:white;border:1px solid #e5e7eb;border-radius:14px;box-shadow:0 4px 16px rgba(0,0,0,.06);padding:28px}
h1{font-size:20px;margin:0 0 4px}p.sub{margin:0 0 18px;font-size:13px;color:#6b7280}
input{width:100%;padding:11px 12px;border:1px solid #d1d5db;border-radius:8px;font-size:15px}
button{width:100%;margin-top:12px;padding:11px;border:0;border-radius:8px;background:#111827;color:white;font-size:15px;cursor:pointer}
.err{padding:10px;border-radius:8px;background:#fef2f2;color:#b91c1c;font-size:13px;margin-bottom:12px}
</style></head><body>
<div class="box">
<h1>黑盒语音监控</h1>
<p class="sub">请输入访问密码</p>
__ERROR__
<form method="post" action="/login">
<input type="password" name="password" placeholder="访问密码" autofocus>
<button type="submit">登录</button>
</form>
</div>
</body></html>"""


class WebHandler(BaseHTTPRequestHandler):
    monitor = None
    auth = None

    def log_message(self, fmt, *args):
        return

    def get_cookie(self, name):
        cookie_header = self.headers.get("Cookie")
        if not cookie_header:
            return ""
        cookie = SimpleCookie()
        try:
            cookie.load(cookie_header)
        except Exception:
            return ""
        morsel = cookie.get(name)
        return morsel.value if morsel else ""

    def send_text(self, text, content_type="text/html; charset=utf-8", status=200, set_cookie=""):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if set_cookie:
            self.send_header("Set-Cookie", set_cookie)
        self.end_headers()
        self.wfile.write(data)

    def authenticated(self):
        if self.auth is None:
            return True
        return self.auth.verify(self.get_cookie(SESSION_COOKIE_NAME))

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/login":
            if self.authenticated():
                self.redirect("/")
                return
            self.send_text(LOGIN_HTML.replace("__ERROR__", ""))
            return
        if parsed.path == "/logout":
            token = self.get_cookie(SESSION_COOKIE_NAME)
            if self.auth is not None:
                self.auth.logout(token)
            self.redirect("/login", clear_cookie=True)
            return
        if not self.authenticated():
            if parsed.path == "/api/data":
                self.send_text(
                    json.dumps({"error": "未登录"}, ensure_ascii=False),
                    "application/json; charset=utf-8", 401,
                )
            else:
                self.redirect("/login")
            return
        if parsed.path == "/":
            self.send_text(HTML)
        elif parsed.path == "/api/data":
            q = parse_qs(parsed.query)
            try:
                data = dashboard(self.monitor, q.get("channel", [""])[0], q.get("user", [""])[0])
                self.send_text(json.dumps(data, ensure_ascii=False), "application/json; charset=utf-8")
            except Exception as exc:
                self.send_text(json.dumps({"error": str(exc)}, ensure_ascii=False), "application/json; charset=utf-8", 500)
        else:
            self.send_text("404 Not Found", "text/plain; charset=utf-8", 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != "/login" or self.auth is None:
            self.send_text("404 Not Found", "text/plain; charset=utf-8", 404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(min(length, 4096)).decode("utf-8", errors="replace")
        form = parse_qs(body)
        password = form.get("password", [""])[0]
        ok, result = self.auth.attempt(password)
        if ok:
            cookie = (
                f"{SESSION_COOKIE_NAME}={result}; Path=/; HttpOnly; SameSite=Strict; "
                f"Max-Age={SESSION_TTL_SECONDS}"
            )
            self.redirect("/", set_cookie=cookie)
        else:
            error_html = f'<div class="err">{escape_html(result)}</div>'
            self.send_text(LOGIN_HTML.replace("__ERROR__", error_html))

    def redirect(self, location, set_cookie="", clear_cookie=False):
        self.send_response(303)
        self.send_header("Location", location)
        if clear_cookie:
            self.send_header("Set-Cookie", f"{SESSION_COOKIE_NAME}=; Path=/; HttpOnly; Max-Age=0")
        elif set_cookie:
            self.send_header("Set-Cookie", set_cookie)
        self.send_header("Content-Length", "0")
        self.end_headers()


def start_web(monitor, port, auth):
    WebHandler.monitor = monitor
    WebHandler.auth = auth
    try:
        server = ThreadingHTTPServer((WEB_HOST, port), WebHandler)
    except OSError as exc:
        print(f"Web 页面启动失败（端口 {port} 被占用）：{exc}")
        print("可在 config.json 中设置 \"web_port\" 更换端口。")
        return
    print(f"Web 页面已启动：http://127.0.0.1:{port}（需要密码登录）")
    server.serve_forever()


def main():
    config = load_config()
    conn = init_db()
    nicknames = NicknameCache(config)
    nicknames.refresh_all()
    monitor = Monitor(config, conn, nicknames)
    auth = AuthManager(config)
    web_port = int(config.get("web_port", WEB_PORT))
    monitor.web_port = web_port
    threading.Thread(target=start_web, args=(monitor, web_port, auth), daemon=True).start()
    try:
        monitor.run()
    except KeyboardInterrupt:
        print("\n已停止监控。")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
