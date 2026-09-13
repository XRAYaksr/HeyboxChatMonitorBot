"""后端 App 接口的冒烟测试：桩掉黑盒平台调用，验证路由/鉴权/校验行为。

用法：python smoke_test.py   （在临时目录中运行，不污染仓库）
"""

import json
import os
import shutil
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta

REPO = os.path.dirname(os.path.abspath(__file__))
WORK = tempfile.mkdtemp(prefix="heychat_smoke_")
os.chdir(WORK)
sys.path.insert(0, REPO)

CONFIG = {
    "token": "FAKE_TOKEN_1234567890abcdef",
    "heybox_id": "111111",
    "room_id": "222222",
    "channel_ids": ["333333", "444444"],
    "poll_interval": 5,
    "web_port": 18099,
    "web_password": "panel-pass",
    "edit_password": "edit-pass",
    "bot_enabled": True,
    "admins": ["999"],
    "epic_push_channel_id": "555555",
    "epic_push_times": ["12:00"],
}
with open("config.json", "w", encoding="utf-8") as fh:
    json.dump(CONFIG, fh, ensure_ascii=False)

import main  # noqa: E402


class StubRequests:
    """替换 main.requests：平台只读接口返回固定房间视图。"""

    @staticmethod
    def get(url, params=None, headers=None, timeout=None):
        class R:
            status_code = 200

            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                if url.endswith("/room/view"):
                    return {
                        "status": "ok",
                        "result": {
                            "room_info": {
                                "room_id": "222222",
                                "room": {
                                    "room_name": "测试房间",
                                    "room_avatar": "https://example.com/a.png",
                                    "introduction": "这是一间测试房间",
                                },
                                "channels": [
                                    {"channel_id": "700001", "channel_name": "文字区域",
                                     "channel_type": 3, "api_type": "volc",
                                     "channel_list": [
                                         {"channel_id": "555555", "channel_name": "文字频道",
                                          "channel_type": 1, "api_type": "volc"},
                                     ]},
                                    {"channel_id": "700002", "channel_name": "通讯区域",
                                     "channel_type": 3, "api_type": "volc",
                                     "channel_list": [
                                         {"channel_id": "333333", "channel_name": "语音一号",
                                          "channel_type": 0, "api_type": "volc"},
                                         {"channel_id": "444444", "channel_name": "语音二号",
                                          "channel_type": 0, "api_type": "volc"},
                                     ]},
                                ],
                                "room_bind_team": {"room_id": "0", "channel_id": "0"},
                            },
                        },
                    }
                return {"status": "ok", "result": {"room_info": {"user_info": [], "user_count": 0}}}
        return R()

    @staticmethod
    def post(url, params=None, headers=None, json=None, timeout=None):
        class R:
            status_code = 200

            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                return {"status": "ok", "result": {"heychat_ack_id": "1"}}
        return R()


main.requests = StubRequests
main.get_user_ids = lambda config, channel_id: {"1001", "1002"} if channel_id == "333333" else set()


class FakeBot:
    enabled = True
    room_id = "222222"

    def __init__(self):
        self.sent = []
        self.roles_written = []

    def is_connected(self):
        return True

    def list_roles(self):
        return [{"id": "r1", "name": "管理员", "color": ""}, {"id": "r2", "name": "游客", "color": ""}]

    def roles_of(self, user_id):
        return {"r2"}

    def grant_role(self, user_id, role_id):
        self.roles_written.append(("grant", user_id, role_id))

    def revoke_role(self, user_id, role_id):
        self.roles_written.append(("revoke", user_id, role_id))

    def send_channel_message(self, room_id, channel_id, msg, msg_type=4, at_user_id="",
                             img="", channel_type=1):
        self.sent.append((channel_id, msg, msg_type, at_user_id, channel_type))


conn = main.init_db()
now = datetime.now()
conn.execute(
    "INSERT INTO voice_sessions (user_id, channel_id, join_time, leave_time, duration_seconds) "
    "VALUES (?, ?, ?, ?, ?)",
    ("1001", "333333", (now - timedelta(hours=2)).strftime("%Y-%m-%d %H:%M:%S"),
     (now - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S"), 3600),
)
conn.execute(
    "INSERT INTO voice_sessions_archive (user_id, channel_id, join_time, leave_time, "
    "duration_seconds, archived_at) VALUES (?, ?, ?, ?, ?, ?)",
    ("1002", "333333", (now - timedelta(days=9)).strftime("%Y-%m-%d %H:%M:%S"),
     (now - timedelta(days=9, hours=-3)).strftime("%Y-%m-%d %H:%M:%S"), 10800,
     (now - timedelta(days=6)).strftime("%Y-%m-%d %H:%M:%S")),
)
conn.commit()

nicknames = main.NicknameCache(CONFIG)
monitor = main.Monitor(CONFIG, conn, nicknames)
monitor.edit_enabled = True
auth = main.AuthManager(CONFIG)
edit_guard = main.EditGuard(CONFIG)
room_cache = main.RoomCache(CONFIG)
bot = FakeBot()

threading.Thread(
    target=main.start_web,
    args=(monitor, CONFIG["web_port"], auth, edit_guard, bot, room_cache),
    daemon=True,
).start()
time.sleep(1.2)
monitor.check_once()   # 产生在线快照与进行中的会话，供后续断言

BASE = f"http://127.0.0.1:{CONFIG['web_port']}"
TOKEN = {"value": ""}
failures = []


def call(method, path, body=None, token=None, expect=200, label=""):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token or TOKEN["value"]:
        req.add_header("X-Session-Token", token or TOKEN["value"])
    status = None
    payload = None
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status, payload = resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        status, payload = exc.code, json.loads(exc.read().decode("utf-8"))
    ok = status == expect
    failures.append(label) if not ok else None
    print(f"{'PASS' if ok else 'FAIL'} {label}: {method} {path} -> {status} "
          f"{'' if ok else f'(expected {expect}) {json.dumps(payload, ensure_ascii=False)[:160]}'}")
    return payload


checks = []
try:
    p = call("GET", "/api/data", expect=401, token="bad-token", label="未认证访问 /api/data 返回 401")
    checks.append(("401 文案", p.get("error") == "未登录"))

    p = call("POST", "/api/login", {"password": "wrong"}, expect=401, token="", label="错误密码登录被拒")
    checks.append(("错误密码有提示", "密码错误" in p.get("error", "")))

    p = call("POST", "/api/login", {"password": "panel-pass"}, expect=200, token="", label="正确密码登录成功")
    checks.append(("返回令牌", bool(p.get("token"))))
    TOKEN["value"] = p.get("token", "")

    p = call("GET", "/api/session", label="会话校验返回运行状态")
    st = p.get("status", {})
    checks.append(("运行时长格式", "天" in st.get("uptime_text", "") and "时" in st.get("uptime_text", "")))
    checks.append(("机器人已连接", st.get("bot_connected") is True))
    checks.append(("编辑密码已启用", st.get("edit_enabled") is True))
    checks.append(("监控频道", st.get("channels") == ["333333", "444444"]))

    p = call("GET", "/api/room", label="房间信息")
    room = p.get("room", {})
    checks.append(("房间名称", room.get("room_name") == "测试房间"))
    checks.append(("房间头像", room.get("room_avatar", "").startswith("https://")))
    checks.append(("房间简介", room.get("room_intro") == "这是一间测试房间"))
    checks.append(("频道列表", len(room.get("channels", [])) == 3))
    checks.append(("监控标记", {c["channel_id"]: c["monitored"] for c in room["channels"]}["555555"] is False))
    checks.append(("语音判定", {c["channel_id"]: c["is_voice"] for c in room["channels"]}["555555"] is False))
    checks.append(("语音频道判定", {c["channel_id"]: c["is_voice"] for c in room["channels"]}["333333"] is True))

    p = call("GET", "/api/data", label="面板数据接口")
    checks.append(("在线用户来自轮询", len(p.get("online", [])) == 2))
    checks.append(("历史含未关闭会话", any(h["leave_time"] is None for h in p.get("history", []))))

    p = call("GET", "/api/history", label="历史查询跨归档表")
    ids = {i["user_id"] for i in p.get("items", [])}
    checks.append(("包含归档用户", "1002" in ids))
    checks.append(("归档标记", any(i["archived"] for i in p["items"])))
    checks.append(("按用户汇总时长", sum(s["total_seconds"] for s in p["summary"]) >= 14400))
    checks.append(("进行中会话标记", any(i["open"] for i in p["items"])))

    p = call("GET", "/api/history?archived=0&user=1001", label="历史筛选排除归档")
    checks.append(("筛选后只剩主表", all(not i["archived"] for i in p["items"])))

    p = call("GET", "/api/config", label="读取配置")
    cfg = p.get("config", {})
    checks.append(("令牌已掩码", "*" in cfg.get("token_masked", "") and
                   CONFIG["token"] not in json.dumps(cfg)))
    checks.append(("不回传密码", "web_password" not in cfg and "edit_password" not in cfg))
    checks.append(("密码已设置标记", cfg.get("has_edit_password") is True))

    p = call("POST", "/api/role", {"action": "grant", "user_id": "1001", "role_id": "r1",
                                   "password": "wrong-edit"}, expect=403, label="身份组写操作要求二级密码")
    p = call("POST", "/api/role", {"action": "grant", "user_id": "1001", "role_id": "r1",
                                   "password": "edit-pass"}, label="二级密码通过后可授予身份组")
    checks.append(("授予已调用平台", ("grant", "1001", "r1") in bot.roles_written))

    p = call("POST", "/api/role", {"action": "grant", "user_id": "abc", "role_id": "r1",
                                   "password": "edit-pass"}, expect=400, label="非法 user_id 被拒")

    p = call("GET", "/api/roles?user_id=1001", label="身份组列表")
    checks.append(("角色可读", [r["name"] for r in p["roles"]] == ["管理员", "游客"]))
    checks.append(("成员角色可读", p.get("user_roles") == ["r2"]))

    p = call("POST", "/api/send", {"channel_id": "555555", "msg": "大家好"}, label="发送频道消息")
    checks.append(("消息已投递", ("555555", "大家好", 4, "", 1) in bot.sent))

    p = call("POST", "/api/send", {"channel_id": "333333", "msg": "注意", "at_user_id": "1001"},
             label="@成员发送")
    checks.append(("at 时使用 markdown@类型", ("333333", "注意", 10, "1001", 0) in bot.sent))

    p = call("POST", "/api/send", {"channel_id": "", "msg": "x"}, expect=400, label="缺少频道被拒")
    p = call("POST", "/api/send", {"channel_id": "333333", "msg": "   "}, expect=400, label="空消息被拒")
    p = call("POST", "/api/send", {"channel_id": "333333", "msg": "x" * 3000}, expect=400,
             label="超长消息被拒")

    p = call("POST", "/api/config", {"config": {"poll_interval": 30}, "password": "edit-pass"},
             label="保存轮询间隔")
    checks.append(("配置写盘", json.load(open("config.json", encoding="utf-8"))["poll_interval"] == 30))
    checks.append(("轮询间隔热生效", monitor.interval == 30))
    checks.append(("无需重启字段", p.get("restart_needed") == []))

    p = call("POST", "/api/config", {"config": {"web_port": 19000}, "password": "edit-pass"},
             label="保存端口")
    checks.append(("端口标记需重启", p.get("restart_needed") == ["web_port"]))

    p = call("POST", "/api/config", {"config": {"poll_interval": 0}, "password": "edit-pass"},
             expect=400, label="非法轮询间隔被拒")
    p = call("POST", "/api/config", {"config": {"epic_push_times": ["25:99"]}, "password": "edit-pass"},
             expect=400, label="非法推送时间被拒")
    p = call("POST", "/api/config", {"config": {"channel_ids": []}, "password": "edit-pass"},
             expect=400, label="空频道列表被拒")
    p = call("POST", "/api/config", {"config": {"web_password": "x"}, "password": "edit-pass"},
             expect=400, label="拒绝经接口改面板密码")
    p = call("POST", "/api/config", {"config": {"nope": 1}, "password": "edit-pass"},
             expect=400, label="未知配置项被拒")
    saved = json.load(open("config.json", encoding="utf-8"))
    checks.append(("被拒的配置项未落盘", saved["poll_interval"] == 30 and
                   saved["epic_push_times"] == ["12:00"] and
                   saved["channel_ids"] == ["333333", "444444"]))

    p = call("POST", "/api/config", {"config": {"edit_password": "new-pass"}}, expect=403,
             label="改二级密码仍需旧密码")
    p = call("POST", "/api/config",
             {"config": {"edit_password": "new-pass"}, "password": "edit-pass"}, label="更换二级密码")
    p = call("POST", "/api/role", {"action": "revoke", "user_id": "1001", "role_id": "r2",
                                   "password": "new-pass"}, label="新二级密码立即生效")
    checks.append(("撤销身份组已调用", ("revoke", "1001", "r2") in bot.roles_written))

    p = call("POST", "/api/edit", {"id": 999999, "join_time": "", "leave_time": "",
                                   "password": "new-pass"}, expect=400, label="编辑不存在的记录报错")

    p = call("GET", "/api/session", token="revoked", label="无效令牌被拒", expect=401)

    p = call("POST", "/api/logout", label="登出")
except Exception as exc:
    failures.append(f"exception: {exc}")
    import traceback
    traceback.print_exc()

print("\n=== 断言明细 ===")
bad = 0
for name, ok in checks:
    if not ok:
        bad += 1
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"\n路由用例失败: {failures}")
print(f"断言失败: {bad} / {len(checks)}")
print("RESULT:", "ALL PASS" if not failures and bad == 0 else "HAS FAILURES")

os.chdir(REPO)
shutil.rmtree(WORK, ignore_errors=True)
sys.exit(0 if not failures and bad == 0 else 1)
