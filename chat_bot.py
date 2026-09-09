"""小黑盒语音机器人命令模块。

通过 WebSocket 长连接接收斜杠命令事件（type=50），通过 HTTP 接口回复频道消息，
提供 /fortune（每日运势）、/pick（抽人）、/forcepushepic（Epic 免费游戏推送，
仅管理员）、/init（重新编号并刷新管理员名单，仅管理员）、/help（帮助）。

协议要点（与官方 Python Demo 保持一致）：
- WebSocket：wss://chat.xiaoheihe.cn/chatroom/ws/connect?chat_os_type=bot&...&token=...
- 心跳：每 30 秒发送文本 "PING"，服务端回 "PONG" 文本
- 命令事件：{"type": "50", "data": {command_info, room_base_info, channel_base_info, sender_info, ...}}
- 发消息：POST /chatroom/v2/channel_msg/send，msg_type 4=markdown 10=支持@的markdown
"""

import json
import random
import sqlite3
import threading
import time
import traceback
from collections import deque
from datetime import datetime

import requests

try:
    import websocket  # websocket-client
except ImportError:
    websocket = None

BASE_URL = "https://chat.xiaoheihe.cn"
WS_CONNECT_URL = "wss://chat.xiaoheihe.cn/chatroom/ws/connect"
FORTUNE_API_URL = "https://60s.viki.moe/v2/luck"
EPIC_FREE_API_URL = "https://uapis.cn/api/v1/game/epic-free"
DB_FILE = "voice_monitor.db"

COMMON_PARAMS = {
    "client_type": "heybox_chat",
    "x_client_type": "web",
    "os_type": "web",
    "x_os_type": "bot",
    "x_app": "heybox_chat",
    "chat_os_type": "bot",
    "chat_version": "1.30.0",
}

HEARTBEAT_INTERVAL_SECONDS = 30
EPIC_CHECK_INTERVAL_SECONDS = 60
RECONNECT_MAX_DELAY_SECONDS = 60
EVENT_COMMAND_TYPE = "50"
EVENT_MESSAGE_TYPE = "5"
MSG_TYPE_MD = 4
MSG_TYPE_MD_AT = 10
ROOM_USER_PAGE_LIMIT = 300
DEFAULT_EPIC_PUSH_TIMES = ["00:00"]
EPIC_ENDING_SOON_HOURS = 24


class BotStore:
    """复用 monitor_state 表的 JSON 键值存储：频道编号、管理员名单、推送状态。

    机器人线程与监控主线程使用各自的 SQLite 连接，写入极少且都很小，
    timeout=30 足以避开偶发的锁冲突。
    """

    def __init__(self, db_file=DB_FILE):
        self.conn = sqlite3.connect(str(db_file), check_same_thread=False, timeout=30)
        self.lock = threading.RLock()
        with self.lock:
            self.conn.execute(
                "CREATE TABLE IF NOT EXISTS monitor_state (key TEXT PRIMARY KEY, value TEXT)"
            )
            self.conn.commit()

    def get_json(self, key, default=None):
        with self.lock:
            row = self.conn.execute(
                "SELECT value FROM monitor_state WHERE key = ?", (key,)
            ).fetchone()
        if not row or not row[0]:
            return default
        try:
            return json.loads(row[0])
        except ValueError:
            return default

    def set_json(self, key, value):
        with self.lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO monitor_state (key, value) VALUES (?, ?)",
                (key, json.dumps(value, ensure_ascii=False)),
            )
            self.conn.commit()

    def close(self):
        with self.lock:
            self.conn.close()


def walk_channels(value, found=None, seen=None):
    """递归扫描 room/view 的 result，收集所有带 channel_id 的对象（与 setup.py 一致）。"""
    if found is None:
        found, seen = [], set()
    if isinstance(value, dict):
        channel_id = value.get("channel_id")
        if channel_id is not None:
            cid = str(channel_id)
            if cid not in seen:
                seen.add(cid)
                found.append(value)
        for child in value.values():
            walk_channels(child, found, seen)
    elif isinstance(value, list):
        for child in value:
            walk_channels(child, found, seen)
    return found


def looks_like_voice_channel(channel):
    """判断是否语音频道：文字频道 channel_type=1；语音频道带 rtc 类 api_type 或其他类型。"""
    channel_type = channel.get("channel_type")
    api_type = str(channel.get("api_type") or "").lower()
    if api_type in ("trtc", "volc", "rtc"):
        return True
    if channel_type == 1:
        return False
    return channel_type not in (None, 0)


def parse_push_times(raw):
    """解析 epic_push_times 配置为 [(时, 分), ...]，非法项忽略。"""
    times = []
    for item in raw or []:
        text = str(item).strip()
        try:
            hour, minute = text.split(":")
            hour, minute = int(hour), int(minute)
            if 0 <= hour <= 23 and 0 <= minute <= 59:
                times.append((hour, minute))
        except ValueError:
            continue
    return times or [(0, 0)]


class CommandContext:
    """一次斜杠命令调用的上下文。"""

    __slots__ = ("room_id", "channel_id", "channel_type", "sender_id", "sender_name", "args", "msg_id")

    def __init__(self, room_id, channel_id, channel_type, sender_id, sender_name, args, msg_id):
        self.room_id = room_id
        self.channel_id = channel_id
        self.channel_type = channel_type
        self.sender_id = sender_id
        self.sender_name = sender_name
        self.args = args
        self.msg_id = msg_id

    def has_help_arg(self):
        return any(arg.lower() == "help" for arg in self.args)


HELP = {
    "fortune": {
        "title": "每日运势",
        "usage": "/fortune [help]",
        "detail": "抽取今日运势。同一用户当天重复抽取结果相同，次日自动重置。",
    },
    "pick": {
        "title": "抽人",
        "usage": "/pick [频道编号|频道ID|list] [help]",
        "detail": "不带参数：从房间在线用户中随机抽一人；带编号：从对应语音频道中抽一人"
                  "（首次使用会自动为语音频道编号，也可用 /init 重新编号）；带频道ID：直接指定语音频道；"
                  "带 list：查看当前可用的语音频道编号。",
    },
    "forcepushepic": {
        "title": "Epic免费游戏推送（仅管理员）",
        "usage": "/forcepushepic [help]",
        "detail": "立即抓取 Epic 免费游戏并推送到配置的推送频道（config 中 epic_push_channel_id，"
                  "未配置时发送到当前频道）。仅频道管理员可用。",
    },
    "init": {
        "title": "初始化（仅管理员）",
        "usage": "/init [help]",
        "detail": "重新为语音频道编号，并刷新频道管理员名单（房主、管理员角色、config 中 admins 的并集）。"
                  "仅频道管理员可用。",
    },
    "help": {
        "title": "帮助",
        "usage": "/help [命令名]",
        "detail": "查看全部命令列表，或查看指定命令的详细用法。也可以在任何命令后加 help 参数。",
    },
}


class ChatBot:
    """斜杠命令机器人：WS 收事件，HTTP 发回复。"""

    def __init__(self, config, nicknames=None, store=None):
        self.config = config
        self.nicknames = nicknames
        self.store = store or BotStore()
        self.token = str(config.get("token", "")).strip()
        self.bot_user_id = str(config.get("heybox_id", "")).strip()
        self.room_id = str(config.get("room_id", "")).strip()
        self.epic_push_channel_id = str(config.get("epic_push_channel_id", "")).strip()
        self.epic_push_times = parse_push_times(config.get("epic_push_times"))
        self.admins_config = {str(x).strip() for x in config.get("admins", []) if str(x).strip()}
        self.enabled = bool(config.get("bot_enabled", True))

        self.ws = None
        self.ws_lock = threading.RLock()
        self.stop_event = threading.Event()
        self._reconnect_delay = 1
        self._recent_sequences = deque(maxlen=512)
        self._recent_msg_ids = deque(maxlen=512)
        self._sequence_lock = threading.Lock()
        self._ack_counter = 0
        self._ack_lock = threading.Lock()
        self._fortune_cache = {"date": "", "values": {}}
        self._fortune_lock = threading.Lock()
        self._aliases_lock = threading.Lock()

    # ------------------------------------------------------------------ 启动

    def start_background(self):
        if not self.enabled:
            print("机器人命令功能未启用（bot_enabled=false）。")
            return
        if websocket is None:
            print("未安装 websocket-client，机器人命令功能不可用。请运行：pip install websocket-client")
            return
        if not self.token:
            print("config.json 缺少 token，机器人命令功能未启动。")
            return
        threading.Thread(target=self._ws_loop, name="heybot-ws", daemon=True).start()
        threading.Thread(target=self._heartbeat_loop, name="heybot-ping", daemon=True).start()
        threading.Thread(target=self._epic_loop, name="heybot-epic", daemon=True).start()
        print(f"机器人命令已启动：/fortune /pick /forcepushepic /init /help（Epic 推送时间 "
              f"{'、'.join(f'{h:02d}:{m:02d}' for h, m in self.epic_push_times)}）")

    # ------------------------------------------------------------- WebSocket

    def _ws_url(self):
        return (
            f"{WS_CONNECT_URL}?chat_os_type=bot&client_type=heybox_chat"
            f"&chat_version={COMMON_PARAMS['chat_version']}&token={self.token}"
        )

    def _ws_loop(self):
        while not self.stop_event.is_set():
            ws_app = websocket.WebSocketApp(
                self._ws_url(),
                header={"Accept": "application/json, text/plain, */*"},
                on_open=self._on_ws_open,
                on_message=self._on_ws_message,
                on_error=self._on_ws_error,
                on_close=self._on_ws_close,
            )
            with self.ws_lock:
                self.ws = ws_app
            try:
                # 协议层 ping 保活 + 应用层每 30 秒发文本 "PING"（官方 Demo 行为）
                ws_app.run_forever(ping_interval=20, ping_timeout=10, skip_utf8_validation=True)
            except Exception as exc:
                print(f"[bot] WebSocket 异常退出：{exc}")
            with self.ws_lock:
                self.ws = None
            if self.stop_event.is_set():
                break
            delay = self._reconnect_delay
            print(f"[bot] 连接断开，{delay} 秒后重连...")
            self.stop_event.wait(delay)
            self._reconnect_delay = min(self._reconnect_delay * 2, RECONNECT_MAX_DELAY_SECONDS)

    def _on_ws_open(self, ws_app):
        print("[bot] WebSocket 已连接，等待斜杠命令事件...")
        self._reconnect_delay = 1

    def _on_ws_error(self, ws_app, error):
        print(f"[bot] WebSocket 错误：{error}")

    def _on_ws_close(self, ws_app, code, reason):
        if code not in (1000, 1001):
            print(f"[bot] WebSocket 关闭（code={code}）")

    def _heartbeat_loop(self):
        while not self.stop_event.wait(HEARTBEAT_INTERVAL_SECONDS):
            ws_app = self.ws
            if ws_app is None:
                continue
            try:
                with self.ws_lock:
                    ws_app.send("PING")
            except Exception:
                pass  # 发送失败说明连接已断，交给重连逻辑处理

    def _on_ws_message(self, ws_app, message):
        # skip_utf8_validation=True 时 TEXT 帧以 bytes 送达，必须先解码
        if isinstance(message, bytes):
            message = message.decode("utf-8", errors="replace")
        if not isinstance(message, str):
            return
        text = message.strip()
        if not text or text.upper().startswith("PONG"):
            return
        try:
            event = json.loads(text)
        except ValueError:
            return
        if not isinstance(event, dict):
            return
        event_type = str(event.get("type") or "")
        sequence = event.get("sequence")
        if sequence is not None:
            with self._sequence_lock:
                if sequence in self._recent_sequences:
                    return
                self._recent_sequences.append(sequence)
        data = event.get("data")
        if not isinstance(data, dict):
            return
        if event_type == EVENT_COMMAND_TYPE:
            # 命令菜单触发的正式命令事件
            self._claim_msg_id(data.get("msg_id"))
            payload = data
        elif event_type == EVENT_MESSAGE_TYPE:
            # 手动输入的斜杠文本只以普通消息到达，这里补一层命令识别
            payload = self._plain_command_payload(data)
            if payload is None:
                return
        else:
            return
        # 命令处理含外部 API 调用，放子线程避免阻塞消息接收
        threading.Thread(
            target=self._dispatch_command, args=(payload,), name="heybot-cmd", daemon=True
        ).start()

    def _claim_msg_id(self, msg_id):
        """同一条消息可能同时以 type=50 和 type=5 到达，用 msg_id 去重。"""
        msg_id = str(msg_id or "")
        if not msg_id:
            return True
        with self._sequence_lock:
            if msg_id in self._recent_msg_ids:
                return False
            self._recent_msg_ids.append(msg_id)
            return True

    def _plain_command_payload(self, data):
        """把 type=5 普通消息伪装成 type=50 的命令事件结构；非已知命令返回 None。"""
        msg = str(data.get("msg") or "").strip()
        if not msg.startswith("/"):
            return None
        name = msg.split()[0].lstrip("/").lower()
        if name not in HELP:
            return None
        if not self._claim_msg_id(data.get("msg_id")):
            return None
        return {
            "command_info": {"name": "/" + name, "options": []},
            "msg": msg,
            "msg_id": data.get("msg_id"),
            "sender_info": {
                "user_id": data.get("user_id"),
                "nickname": data.get("nickname"),
                "bot": data.get("bot"),
            },
            "room_base_info": {"room_id": data.get("room_id")},
            "channel_base_info": {
                "channel_id": data.get("channel_id"),
                "channel_type": data.get("channel_type"),
            },
        }

    # --------------------------------------------------------------- 命令分发

    def _dispatch_command(self, data):
        command_info = data.get("command_info") or {}
        name = str(command_info.get("name") or "").strip().lstrip("/").lower()
        args = []

        def collect(options):
            for option in options or []:
                value = option.get("value")
                if value not in (None, ""):
                    args.append(str(value).strip())
                collect(option.get("choices"))

        collect(command_info.get("options"))
        # 自由文本参数不在 options 里，而在原始消息中（如 "/pick list"）
        if not args:
            parts = str(data.get("msg") or "").split()
            if parts and parts[0].lstrip("/").lower() == name:
                args = parts[1:]

        sender = data.get("sender_info") or {}
        sender_id = str(sender.get("user_id") or "")
        if not name or not sender_id or sender.get("bot"):
            return
        room = data.get("room_base_info") or {}
        channel = data.get("channel_base_info") or {}
        room_id = str(room.get("room_id") or "")
        channel_id = str(channel.get("channel_id") or "")
        if not room_id or not channel_id:
            return
        sender_name = (sender.get("room_nickname") or sender.get("nickname")
                       or sender.get("username") or sender_id)
        ctx = CommandContext(
            room_id=room_id,
            channel_id=channel_id,
            channel_type=channel.get("channel_type") or 1,
            sender_id=sender_id,
            sender_name=str(sender_name),
            args=args,
            msg_id=str(data.get("msg_id") or ""),
        )
        handler = getattr(self, "handle_" + name, None)
        if handler is None:
            print(f"[bot] 收到未实现命令：/{name}（来自 {ctx.sender_name}）")
            self.reply_text(ctx, f"暂未支持命令 /{name}，发送 /help 查看可用命令。")
            return
        print(f"[bot] /{name} {args or ''} <- {ctx.sender_name}（{ctx.sender_id}）")
        try:
            handler(ctx)
        except Exception as exc:
            traceback.print_exc()
            try:
                self.reply_text(ctx, f"命令执行出错：{exc}")
            except Exception:
                pass

    # ---------------------------------------------------------------- 发消息

    def _next_ack_id(self):
        with self._ack_lock:
            self._ack_counter = (self._ack_counter + 1) % 1000000
            return f"{int(time.time() * 1000)}-{self._ack_counter}"

    def send_channel_message(self, room_id, channel_id, msg, msg_type=MSG_TYPE_MD,
                             at_user_id="", img="", channel_type=1):
        body = {
            "msg": msg,
            "msg_type": msg_type,
            "heychat_ack_id": self._next_ack_id(),
            "reply_id": "",
            "room_id": str(room_id),
            "addition": "{}",
            "at_user_id": str(at_user_id or ""),
            "at_role_id": "",
            "mention_channel_id": "",
            "channel_id": str(channel_id),
            "channel_type": int(channel_type or 1),
        }
        if img:
            body["img"] = img
        response = requests.post(
            BASE_URL + "/chatroom/v2/channel_msg/send",
            params=COMMON_PARAMS,
            headers={"token": self.token, "Content-Type": "application/json;charset=UTF-8"},
            json=body,
            timeout=15,
        )
        response.raise_for_status()
        payload = response.json()
        if payload.get("status") != "ok":
            raise RuntimeError("发送消息失败：" + json.dumps(payload, ensure_ascii=False))
        return payload

    def reply_text(self, ctx, text):
        self.send_channel_message(ctx.room_id, ctx.channel_id, text, channel_type=ctx.channel_type)

    def reply_mention(self, ctx, user_id, text):
        msg = f"@{{id:{user_id}}} {text}"
        self.send_channel_message(
            ctx.room_id, ctx.channel_id, msg, msg_type=MSG_TYPE_MD_AT,
            at_user_id=str(user_id), channel_type=ctx.channel_type,
        )

    def display_name(self, user_id):
        if self.nicknames:
            try:
                info = self.nicknames.lookup(str(user_id))
                if info.get("name"):
                    return info["name"]
            except Exception:
                pass
        return str(user_id)

    # ------------------------------------------------------- 数据获取（黑盒API）

    def _api_get(self, path, extra_params):
        params = dict(COMMON_PARAMS)
        params.update({"token": self.token})
        params.update(extra_params)
        response = requests.get(BASE_URL + path, params=params,
                                headers={"token": self.token}, timeout=15)
        response.raise_for_status()
        data = response.json()
        if data.get("status") != "ok":
            raise RuntimeError("API返回错误：" + json.dumps(data, ensure_ascii=False))
        return data.get("result") or {}

    def get_channel_online_users(self, channel_id):
        """某频道（语音）当前成员，与 main.py 监控使用同一接口。"""
        result = self._api_get("/chatroom/v2/channel/user/list", {
            "channel_id": str(channel_id),
            "room_id": self.room_id,
        })
        user_ids = result.get("user_ids")
        if not isinstance(user_ids, list):
            return set()
        return {str(x) for x in user_ids}

    def get_room_online_users(self):
        """房间在线用户（online_state != 0，排除机器人）。"""
        users = []
        offset = 0
        while True:
            result = self._api_get("/chatroom/v2/room/users", {
                "heybox_id": self.bot_user_id,
                "room_id": self.room_id,
                "offset": str(offset),
                "limit": str(ROOM_USER_PAGE_LIMIT),
            })
            page = (result.get("room_info") or {}).get("user_info") or []
            users.extend(page)
            offset += ROOM_USER_PAGE_LIMIT
            if len(page) < ROOM_USER_PAGE_LIMIT:
                break
        online = set()
        for info in users:
            if not isinstance(info, dict) or info.get("bot"):
                continue
            state = info.get("online_state")
            if state is None or str(state) != "0":
                uid = str(info.get("user_id") or "").strip()
                if uid:
                    online.add(uid)
        return online

    def get_room_channels(self):
        """房间全部频道（递归扫描 room/view）。"""
        result = self._api_get("/chatroom/v2/room/view", {"room_id": self.room_id})
        return walk_channels(result)

    def get_room_members(self):
        """房间全部成员（分页）。"""
        members = []
        offset = 0
        while True:
            result = self._api_get("/chatroom/v2/room/users", {
                "heybox_id": self.bot_user_id,
                "room_id": self.room_id,
                "offset": str(offset),
                "limit": str(ROOM_USER_PAGE_LIMIT),
            })
            page = (result.get("room_info") or {}).get("user_info") or []
            members.extend(page)
            offset += ROOM_USER_PAGE_LIMIT
            if len(page) < ROOM_USER_PAGE_LIMIT:
                break
        return [m for m in members if isinstance(m, dict)]

    # ------------------------------------------------------ 频道编号与管理员

    def get_channel_aliases(self):
        """频道编号映射 {编号: {channel_id, channel_name}}；为空时自动生成（首次使用免配置）。"""
        with self._aliases_lock:
            aliases = self.store.get_json("bot:channel_aliases")
            if not aliases:
                aliases = self.refresh_channel_aliases()
            return aliases or {}

    def refresh_channel_aliases(self):
        """为语音频道编号：房间内语音频道（含配置监控的频道兜底），按房间列表顺序。"""
        aliases = {}
        try:
            channels = self.get_room_channels()
        except Exception as exc:
            print(f"[bot] 获取房间频道列表失败：{exc}")
            channels = []
        monitored = [str(x) for x in self.config.get("channel_ids", [])]
        ordered, seen = [], set()
        for channel in channels:
            cid = str(channel.get("channel_id") or "").strip()
            if not cid or cid in seen:
                continue
            if looks_like_voice_channel(channel) or cid in monitored:
                seen.add(cid)
                ordered.append({
                    "channel_id": cid,
                    "channel_name": channel.get("channel_name") or channel.get("name") or "",
                })
        for cid in monitored:  # 没出现在房间列表里的监控频道也要可编号
            if cid not in seen:
                seen.add(cid)
                ordered.append({"channel_id": cid, "channel_name": ""})
        aliases = {str(i): ch for i, ch in enumerate(ordered, 1)}
        self.store.set_json("bot:channel_aliases", aliases)
        return aliases

    def aliases_help_text(self, aliases=None):
        try:
            aliases = aliases if aliases is not None else self.get_channel_aliases()
        except Exception:
            aliases = {}
        if not aliases:
            return "（暂无语音频道编号，可尝试 /init 重新生成）"
        lines = ["## 🎙 语音频道编号"]
        for num in sorted(aliases, key=int):
            info = aliases[num] or {}
            name = info.get("channel_name") or "未命名频道"
            lines.append(f"- **{num}**. {name}（{info.get('channel_id')}）")
        return "\n".join(lines)

    def is_admin(self, user_id):
        uid = str(user_id)
        if uid in self.admins_config:
            return True
        stored = self.store.get_json("bot:admins")
        if not stored:
            # 空名单视为未成功刷新过（房间必有房主），立即重刷而不等冷却
            try:
                self.refresh_admins()
                stored = self.store.get_json("bot:admins") or []
            except Exception as exc:
                print(f"[bot] 自动刷新管理员名单失败：{exc}")
                return False
        return uid in {str(x) for x in (stored or [])}

    def refresh_admins(self):
        """刷新管理员名单：config admins ∪ 房主 ∪ 「管理员」角色成员，写入存储并返回报告。"""
        admins = set(self.admins_config)
        report = []
        owner_ids, extra_admins, note = self._scan_room_owner_admins()
        if owner_ids:
            admins |= owner_ids
            report.append(f"房主：{'、'.join(self.display_name(uid) for uid in sorted(owner_ids))}")
        elif note:
            report.append(note)
        if extra_admins:
            admins |= extra_admins
            report.append(f"房间管理员名单：{'、'.join(self.display_name(uid) for uid in sorted(extra_admins))}")

        admin_role_ids, role_names = self._fetch_admin_role_ids()
        if role_names:
            report.append("识别为管理员的身份组：" + "、".join(sorted(role_names)))
        matched_members = set()
        if admin_role_ids:
            for member in self.get_room_members():
                member_roles = {str(r) for r in (member.get("roles") or [])}
                if member_roles & admin_role_ids:
                    uid = str(member.get("user_id") or "").strip()
                    if uid:
                        matched_members.add(uid)
        if matched_members:
            admins |= matched_members
            report.append(
                "身份组管理员："
                + "、".join(self.display_name(uid) for uid in sorted(matched_members))
            )

        self.store.set_json("bot:admins", sorted(admins))
        self.store.set_json("bot:admins_refreshed_at", time.time())
        summary = (
            f"管理员名单共 {len(admins)} 人"
            + (f"（含配置 {len(self.admins_config)} 人）" if self.admins_config else "")
        )
        return admins, summary, report

    def _scan_room_owner_admins(self):
        """从 room/view 返回中尽力提取房主与管理员用户ID（字段名白名单，避免误判）。"""
        owner_keys = ("owner_id", "room_owner_id", "create_user_id", "creator_id", "create_by")
        admin_keys = ("admin_ids", "admin_user_ids", "admin_users", "admins", "admin_list")
        owners, admins = set(), set()
        note = ""

        def add_id(target, value):
            if isinstance(value, bool):
                return
            if isinstance(value, int):
                target.add(str(value))
            elif isinstance(value, str) and value.strip().isdigit():
                target.add(value.strip())
            elif isinstance(value, dict) and value.get("user_id") is not None:
                add_id(target, value.get("user_id"))

        def walk(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    if key in owner_keys:
                        add_id(owners, value)
                    elif key in admin_keys:
                        if isinstance(value, list):
                            for item in value:
                                add_id(admins, item)
                        else:
                            add_id(admins, value)
                    else:
                        walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)

        try:
            walk(self._api_get("/chatroom/v2/room/view", {"room_id": self.room_id}))
        except Exception as exc:
            note = f"（房间信息获取失败，仅用配置名单：{exc}）"
        return owners, admins, note

    def _fetch_admin_role_ids(self):
        """从身份组列表中识别名称含「管理员」/admin 的角色。"""
        role_ids, role_names = set(), []
        try:
            result = self._api_get("/chatroom/v2/room_role/roles", {"room_id": self.room_id})
        except Exception as exc:
            print(f"[bot] 获取身份组列表失败：{exc}")
            return role_ids, role_names
        roles = result.get("roles") if isinstance(result, dict) else result
        for role in roles or []:
            if not isinstance(role, dict):
                continue
            name = str(role.get("name") or "")
            low = name.lower()
            if ("管理" in name or "房主" in name
                    or any(k in low for k in ("admin", "moderator", "operator", "owner"))):
                rid = str(role.get("id") or role.get("role_id") or "").strip()
                if rid:
                    role_ids.add(rid)
                    role_names.append(name)
        return role_ids, role_names

    # -------------------------------------------------------------- /fortune

    def handle_fortune(self, ctx):
        if ctx.has_help_arg():
            self.reply_text(ctx, command_help_text("fortune"))
            return
        desc, rank, tip = self._fetch_daily_fortune(ctx.sender_id)
        rank_text = f"（指数 {rank}）" if rank is not None else ""
        self.reply_text(
            ctx,
            f"🔮 今日运势 —— {ctx.sender_name}\n"
            f"运势：{desc}{rank_text}\n"
            f"提示：{tip}",
        )

    def _fetch_daily_fortune(self, user_id):
        """同一用户当天结果固定：内存缓存一天，隔天自动清空。"""
        today = datetime.now().strftime("%Y-%m-%d")
        with self._fortune_lock:
            if self._fortune_cache["date"] != today:
                self._fortune_cache = {"date": today, "values": {}}
            cached = self._fortune_cache["values"].get(user_id)
            if cached:
                return cached
        response = requests.get(FORTUNE_API_URL, params={"encoding": "json"}, timeout=15)
        response.raise_for_status()
        payload = response.json()
        data = payload.get("data") or {}
        result = (
            str(data.get("luck_desc") or "未知"),
            data.get("luck_rank"),
            str(data.get("luck_tip") or ""),
        )
        with self._fortune_lock:
            self._fortune_cache["values"][user_id] = result
        return result

    # ----------------------------------------------------------------- /pick

    def handle_pick(self, ctx):
        if ctx.has_help_arg():
            self.reply_text(ctx, command_help_text("pick"))
            return
        arg = ctx.args[0] if ctx.args else ""
        aliases = self.get_channel_aliases()
        if arg.lower() == "list":
            self.reply_text(ctx, self.aliases_help_text(aliases))
            return
        if not arg:
            candidates, source = self.get_room_online_users(), "房间在线用户"
            if not candidates:
                candidates, source = self._all_voice_channel_users(aliases), "语音频道成员"
                if candidates:
                    source += "（在线名单为空，已自动改为语音频道成员）"
        elif arg.isdigit() and arg in aliases:
            target = aliases[arg] or {}
            channel_name = target.get("channel_name") or "未命名频道"
            candidates = self.get_channel_online_users(target.get("channel_id"))
            source = f"语音频道「{channel_name}」"
        elif arg.isdigit() and len(arg) >= 10:
            candidates = self.get_channel_online_users(arg)
            source = f"频道 {arg}"
        elif arg.isdigit():
            self.reply_text(ctx, f"没有编号为 {arg} 的语音频道。\n" + self.aliases_help_text(aliases))
            return
        else:
            self.reply_text(ctx, "参数无效。用法：/pick [频道编号|频道ID]\n" + self.aliases_help_text(aliases))
            return

        candidates = {uid for uid in candidates if uid and uid != self.bot_user_id}
        if not candidates:
            self.reply_text(ctx, f"{source}当前没有可抽取的人。")
            return
        winner = random.choice(sorted(candidates))
        print(f"[bot] /pick 抽中 {self.display_name(winner)}（{winner}），来源：{source}，候选 {len(candidates)} 人")
        self.reply_mention(
            ctx, winner,
            f"恭喜！从{source}（{len(candidates)} 人）中抽中了【{self.display_name(winner)}】！🎉",
        )

    def _all_voice_channel_users(self, aliases):
        users = set()
        for info in (aliases or {}).values():
            try:
                users |= self.get_channel_online_users(info.get("channel_id"))
            except Exception as exc:
                print(f"[bot] 获取频道 {info.get('channel_id')} 成员失败：{exc}")
        return users

    # -------------------------------------------------------- /forcepushepic

    def handle_forcepushepic(self, ctx):
        if ctx.has_help_arg():
            self.reply_text(ctx, command_help_text("forcepushepic"))
            return
        if not self.is_admin(ctx.sender_id):
            self.reply_text(ctx, "该命令仅频道管理员可用。")
            return
        target_channel = self.epic_push_channel_id or ctx.channel_id
        try:
            games = self._fetch_epic_free()
            message = self.build_epic_message(games)
            self.send_channel_message(ctx.room_id, target_channel, message)
        except Exception as exc:
            self.reply_text(ctx, f"Epic 推送失败：{exc}")
            return
        if self.epic_push_channel_id:
            self.reply_text(ctx, f"已推送 Epic 免费游戏到推送频道（{target_channel}）。")
        else:
            self.reply_text(
                ctx,
                "已在当前频道测试推送。提示：在 config.json 中设置 epic_push_channel_id 后，"
                "定时推送将发到指定频道。",
            )

    # ------------------------------------------------------------------ /init

    def handle_init(self, ctx):
        if ctx.has_help_arg():
            self.reply_text(ctx, command_help_text("init"))
            return
        if not self.is_admin(ctx.sender_id):
            self.reply_text(ctx, "该命令仅频道管理员可用。")
            return
        lines = ["🛠 机器人初始化完成"]
        aliases = self.refresh_channel_aliases()
        lines.append(f"已为 {len(aliases)} 个语音频道重新编号。")
        lines.append(self.aliases_help_text(aliases))
        _, summary, report = self.refresh_admins()
        lines.append(f"管理员名单已刷新：{summary}。")
        lines.extend(report)
        if not self.epic_push_channel_id:
            lines.append("提示：尚未配置 epic_push_channel_id，Epic 定时推送未启用。")
        self.reply_text(ctx, "\n".join(lines))

    # ------------------------------------------------------------------ /help

    def handle_help(self, ctx):
        arg = next((a for a in ctx.args if a.lower() != "help"), "")
        if arg:
            name = arg.lstrip("/").lower()
            if name in HELP:
                self.reply_text(ctx, command_help_text(name))
            else:
                self.reply_text(ctx, f"未知命令 /{arg}。\n\n{build_help_overview()}")
            return
        self.reply_text(ctx, build_help_overview())

    # ------------------------------------------------------------ Epic 免费游戏

    def _fetch_epic_free(self):
        response = requests.get(
            EPIC_FREE_API_URL,
            headers={"User-Agent": "HeyChatVoiceMonitor/1.0"},
            timeout=20,
        )
        response.raise_for_status()
        payload = response.json()
        games = payload.get("data")
        if not isinstance(games, list):
            raise RuntimeError("Epic 接口返回格式异常：" + json.dumps(payload, ensure_ascii=False)[:200])
        return [g for g in games if isinstance(g, dict)]

    def build_epic_message(self, games, now=None):
        now = now or datetime.now()
        now_ms = int(now.timestamp() * 1000)
        soon_limit = now_ms + EPIC_ENDING_SOON_HOURS * 3600 * 1000
        ending_soon, free_now, upcoming = [], [], []
        for game in games:
            start_at = _to_ms(game.get("free_start_at"))
            end_at = _to_ms(game.get("free_end_at"))
            if game.get("is_free_now") or (start_at and start_at <= now_ms and (not end_at or end_at > now_ms)):
                if end_at and end_at <= soon_limit:
                    ending_soon.append(game)
                else:
                    free_now.append(game)
            elif start_at and start_at > now_ms:
                upcoming.append(game)
        ending_soon.sort(key=lambda g: _to_ms(g.get("free_end_at")) or 0)
        upcoming.sort(key=lambda g: _to_ms(g.get("free_start_at")) or 0)

        lines = ["🎮 Epic 免费游戏速递", ""]
        if ending_soon:
            lines.append(f"🚨【即将结束·{EPIC_ENDING_SOON_HOURS}小时内】")
            for game in ending_soon:
                lines.extend(_epic_game_lines(game, "⏰ 领取截止", now))
            lines.append("")
        lines.append("【现在可领】")
        if free_now:
            for game in free_now:
                lines.extend(_epic_game_lines(game, "⏰ 领取截止", now))
        else:
            lines.append("暂无")
        if upcoming:
            lines.append("")
            lines.append("【即将免费】")
            for game in upcoming[:3]:
                lines.extend(_epic_game_lines(game, "⏰ 开始时间", now))
        # 不内嵌封面图：markdown 图片只认黑盒 CDN，Epic 外链会让整条消息被判「图片链接地址不合法」发送失败
        return "\n".join(lines)

    def push_epic_to_channel(self):
        """定时推送：发送到 epic_push_channel_id 并返回推送内容摘要。"""
        if not self.epic_push_channel_id:
            raise RuntimeError("未配置 epic_push_channel_id")
        games = self._fetch_epic_free()
        message = self.build_epic_message(games)
        self.send_channel_message(self.room_id, self.epic_push_channel_id, message)
        titles = [
            g.get("title") for g in games
            if g.get("is_free_now")
        ]
        return "、".join(titles) if titles else "（当前无免费游戏）"

    def _epic_loop(self):
        while not self.stop_event.wait(EPIC_CHECK_INTERVAL_SECONDS):
            try:
                self._epic_tick()
            except Exception as exc:
                print(f"[bot] Epic 定时推送失败：{exc}"
                      f"（该时间点今天不再重试，可用 /forcepushepic 手动推送）")

    def _epic_tick(self):
        if not self.epic_push_channel_id:
            return
        now = datetime.now()
        today = now.strftime("%Y-%m-%d")
        for hour, minute in self.epic_push_times:
            slot_time = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
            if now < slot_time:
                continue
            key = f"bot:epic:last_push:{hour:02d}:{minute:02d}"
            last = self.store.get_json(key) or {}
            if last.get("date") == today:
                continue
            # 请求前就先登记当天，失败时不再周期性重试，避免持续打接口触发 429
            self.store.set_json(key, {"date": today})
            titles = self.push_epic_to_channel()
            print(f"[bot] Epic 免费游戏已定时推送：{titles}")
            break  # 一个轮询周期只推一次


# ------------------------------------------------------------------ 工具函数

def _to_ms(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _price_text(game):
    desc = str(game.get("original_price_desc") or "").strip()
    if desc:
        return desc
    price = game.get("original_price")
    return f"¥{price}" if price not in (None, "") else "未知"


def _remaining_text(end_ms, now):
    if not end_ms:
        return ""
    seconds = end_ms / 1000 - now.timestamp()
    if seconds <= 0:
        return "已结束"
    days, rem = divmod(int(seconds), 86400)
    hours, _ = divmod(rem, 3600)
    if days:
        return f"还剩{days}天{hours}小时"
    return f"还剩{hours}小时"


def _epic_game_lines(game, time_label, now):
    lines = [f"▫️ {game.get('title') or '未知游戏'}（原价 {_price_text(game)}）"]
    time_text = str(game.get("free_end") if "截止" in time_label else game.get("free_start")) or ""
    stamp = _to_ms(game.get("free_end_at")) if "截止" in time_label else _to_ms(game.get("free_start_at"))
    remaining = _remaining_text(stamp, now)
    suffix = f"（{remaining}）" if remaining else ""
    lines.append(f"   {time_label} {time_text}{suffix}".rstrip())
    if game.get("link"):
        lines.append(f"   🔗 {game['link']}")
    return lines


def _fmt_usage(usage):
    parts = usage.split(" ", 1)
    return f"**{parts[0]}** {parts[1]}" if len(parts) > 1 else f"**{parts[0]}**"


def command_help_text(name):
    info = HELP[name]
    return (
        f"## 📖 {info['title']}\n"
        f"**用法**：{_fmt_usage(info['usage'])}\n"
        f"----------------\n"
        f"{info['detail']}"
    )


def build_help_overview():
    lines = ["## 📖 命令帮助"]
    for name, info in HELP.items():
        lines.append(f"- {_fmt_usage(info['usage'])} —— {info['title']}")
    lines.append("----------------")
    lines.append("> 💡 任何命令后加 help 可查看详细用法，如 /fortune help")
    return "\n".join(lines)
