import json
from pathlib import Path
import requests

BASE_URL = "https://chat.xiaoheihe.cn"
CONFIG_FILE = Path("config.json")

# ===== 在这里填写 =====
BOT_TOKEN = "你的机器人Token"
BOT_ID = "你的机器人独立ID"
# =====================

COMMON_PARAMS = {
    "client_type": "heybox_chat",
    "x_client_type": "web",
    "os_type": "web",
    "x_os_type": "bot",
    "x_app": "heybox_chat",
    "chat_os_type": "bot",
    "chat_version": "1.30.0",
}

def api_get(path, params):
    headers = {
        "token": BOT_TOKEN,
        "Content-Type": "application/json;charset=UTF-8",
        "Accept": "application/json",
        "User-Agent": "HeyChatVoiceMonitor/1.0",
    }
    q = dict(COMMON_PARAMS)
    q.update(params)
    r = requests.get(BASE_URL + path, headers=headers, params=q, timeout=15)
    r.raise_for_status()
    data = r.json()
    if data.get("status") != "ok":
        raise RuntimeError(f"API返回错误：{json.dumps(data, ensure_ascii=False)}")
    return data

def get_rooms():
    data = api_get("/chatroom/v2/room/joined", {"offset": "0", "limit": "100"})
    rooms = data.get("result", {}).get("rooms", [])
    if isinstance(rooms, dict):
        rooms = rooms.get("rooms", [])
    return rooms

def get_room(room_id):
    return api_get("/chatroom/v2/room/view", {"room_id": str(room_id)})

def extract_channels(value):
    """递归扫描整个 /room/view result，寻找包含 channel_id 的对象。"""
    found, seen = [], set()

    def walk(x):
        if isinstance(x, dict):
            if x.get("channel_id") is not None:
                cid = str(x["channel_id"])
                if cid not in seen:
                    seen.add(cid)
                    found.append(x)
            for v in x.values():
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)

    walk(value)
    return found

def channel_text(ch):
    name = ch.get("channel_name") or ch.get("name") or "(无名称)"
    return (
        f"{name} | channel_id={ch.get('channel_id')} | "
        f"channel_type={ch.get('channel_type', '')} | "
        f"api_type={ch.get('api_type', '')}"
    )

def main():
    if not BOT_TOKEN or "你的机器人" in BOT_TOKEN:
        raise SystemExit("请先在 setup.py 顶部填写 BOT_TOKEN。")
    if not BOT_ID or "你的机器人" in str(BOT_ID):
        raise SystemExit("请先在 setup.py 顶部填写 BOT_ID。")

    print("=" * 60)
    print("黑盒语音上下线监控 - 配置工具")
    print("=" * 60)
    print("\n正在获取机器人加入的房间...")
    rooms = get_rooms()

    if not rooms:
        raise SystemExit("没有获取到房间，请检查 Token 和机器人权限。")

    for i, room in enumerate(rooms, 1):
        print(f"[{i}] {room.get('room_name', '(无名称)')}")
        print(f"    room_id: {room.get('room_id')}")

    while True:
        try:
            idx = int(input("\n请选择房间编号: "))
            room = rooms[idx - 1]
            break
        except (ValueError, IndexError):
            print("输入无效。")

    room_id = str(room["room_id"])
    print(f"\n正在获取房间「{room.get('room_name', '')}」的信息...")
    response = get_room(room_id)

    result = response.get("result", {})
    print("API result 顶层字段：", ", ".join(result.keys()))

    channels = extract_channels(result)

    if not channels:
        # 保存完整返回，避免靠猜结构
        Path("room_view_debug.json").write_text(
            json.dumps(response, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        raise SystemExit(
            "\n仍然没有找到 channel_id。\n"
            "已保存完整响应到 room_view_debug.json。\n"
            "把这个 JSON 文件发给我，我可以按实际结构继续处理。"
        )

    print("\n找到的频道：")
    for i, ch in enumerate(channels, 1):
        print(f"[{i}] {channel_text(ch)}")

    while True:
        raw = input("\n请选择要监控的频道编号（可多选，例如 1,3）: ").strip()
        try:
            nums = list(dict.fromkeys(int(x.strip()) for x in raw.split(",") if x.strip()))
            if not nums or any(n < 1 or n > len(channels) for n in nums):
                raise ValueError
            selected = [channels[n - 1] for n in nums]
            break
        except ValueError:
            print("输入无效。")

    config = {
        "token": BOT_TOKEN,
        "heybox_id": str(BOT_ID),
        "room_id": room_id,
        "channel_ids": [str(x["channel_id"]) for x in selected],
        "poll_interval": 5,
        "record_initial_online": False
    }

    CONFIG_FILE.write_text(json.dumps(config, ensure_ascii=False, indent=4), encoding="utf-8")

    print("\n配置完成，已生成 config.json")
    print(f"room_id = {room_id}")
    for ch in selected:
        print(f"channel = {ch.get('channel_name')}, channel_id = {ch.get('channel_id')}")
    print("\n运行：python main.py")

if __name__ == "__main__":
    main()
