import argparse
import json
import sqlite3
from pathlib import Path

import requests

DB_FILE = Path("voice_monitor.db")
CONFIG_FILE = Path("config.json")
BASE_URL = "https://chat.xiaoheihe.cn"
PAGE_LIMIT = 300

COMMON_PARAMS = {
    "client_type": "heybox_chat",
    "x_client_type": "web",
    "os_type": "web",
    "x_os_type": "bot",
    "x_app": "heybox_chat",
    "chat_os_type": "bot",
    "chat_version": "1.30.0",
}


def load_username_map():
    """尽力从黑盒接口拉取房间用户列表，建立 UID → 昵称 映射。失败时返回空映射。"""
    if not CONFIG_FILE.exists():
        print("（未找到 config.json，仅显示用户ID）")
        return {}
    try:
        config = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"（config.json 读取失败，仅显示用户ID：{exc}）")
        return {}

    name_map = {}
    try:
        offset = 0
        while True:
            params = dict(COMMON_PARAMS)
            params.update({
                "heybox_id": str(config.get("heybox_id", "")),
                "room_id": str(config.get("room_id", "")),
                "offset": str(offset),
                "limit": str(PAGE_LIMIT),
            })
            response = requests.get(
                BASE_URL + "/chatroom/v2/room/users",
                params=params,
                headers={"token": config.get("token", "")},
                timeout=15,
            )
            response.raise_for_status()
            data = response.json()
            if data.get("status") != "ok":
                raise RuntimeError("API返回错误：" + json.dumps(data, ensure_ascii=False))
            room_info = data.get("result", {}).get("room_info", {})
            page = room_info.get("user_info") or []
            for info in page:
                uid = str(info.get("user_id", "")).strip()
                if uid:
                    name_map[uid] = (
                        info.get("room_nickname")
                        or info.get("nickname")
                        or info.get("username")
                        or ""
                    )
            offset += PAGE_LIMIT
            if len(page) < PAGE_LIMIT:
                break
    except Exception as exc:
        print(f"（获取用户名单失败，仅显示用户ID：{exc}）")
        return {}
    return name_map


def fmt_seconds(value):
    if value is None:
        return "-"
    value = int(value)
    h, rem = divmod(value, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{h}小时{m}分{s}秒"
    if m:
        return f"{m}分{s}秒"
    return f"{s}秒"


def main():
    parser = argparse.ArgumentParser(
        description="黑盒语音上下线记录查看工具"
    )
    parser.add_argument(
        "--today",
        action="store_true",
        help="只显示今天的记录",
    )
    parser.add_argument(
        "--user",
        help="只显示指定用户ID",
    )
    parser.add_argument(
        "--channel",
        help="只显示指定频道ID",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="显示全部记录（默认最近100条）",
    )
    parser.add_argument(
        "--no-names",
        action="store_true",
        help="不解析用户名，只显示UID",
    )
    args = parser.parse_args()

    if not DB_FILE.exists():
        print("找不到 voice_monitor.db")
        print("请先运行 python main.py")
        return

    name_map = {}
    if not args.no_names:
        name_map = load_username_map()

    conn = sqlite3.connect(DB_FILE)

    where = []
    params = []

    if args.today:
        where.append(
            "(join_time LIKE ? OR leave_time LIKE ?)"
        )
        today = __import__("datetime").datetime.now().strftime(
            "%Y-%m-%d"
        )
        params.extend([today + "%", today + "%"])

    if args.user:
        where.append("user_id = ?")
        params.append(str(args.user))

    if args.channel:
        where.append("channel_id = ?")
        params.append(str(args.channel))

    sql = """
        SELECT
            user_id,
            channel_id,
            join_time,
            leave_time,
            duration_seconds
        FROM voice_sessions
    """

    if where:
        sql += " WHERE " + " AND ".join(where)

    sql += " ORDER BY COALESCE(leave_time, join_time) DESC"

    if not args.all:
        sql += " LIMIT 100"

    rows = conn.execute(sql, params).fetchall()
    conn.close()

    if not rows:
        print("没有找到记录。")
        return

    print()
    print("=" * 125)
    print("黑盒语音上下线记录")
    print("=" * 125)
    print(
        f"{'用户':<20}"
        f"{'用户ID':<14}"
        f"{'频道ID':<24}"
        f"{'上线时间':<20}"
        f"{'下线时间':<20}"
        f"{'在线时长':<15}"
    )
    print("-" * 125)

    for user_id, channel_id, join_time, leave_time, duration in rows:
        join_display = join_time or "程序启动时已在线"
        leave_display = leave_time or "在线中"
        username = name_map.get(str(user_id), "") or "-"

        print(
            f"{username:<20}"
            f"{user_id:<14}"
            f"{channel_id:<24}"
            f"{join_display:<20}"
            f"{leave_display:<20}"
            f"{fmt_seconds(duration):<15}"
        )

    print("=" * 125)
    print(f"共 {len(rows)} 条记录")
    print()


if __name__ == "__main__":
    main()
