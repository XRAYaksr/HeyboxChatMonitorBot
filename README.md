# 黑盒语音频道监控（HeyChat Voice Monitor）

一个用于监控[黑盒语音](https://chat.top/)房间语音频道的机器人监控程序。它轮询频道在线成员列表，自动记录每位用户的上线、下线与在线时长，并提供一个带密码保护的网页面板实时查看，同时支持将用户 UID 自动解析为昵称和头像。

## 功能特性

- **频道在线监控**：按配置的间隔轮询指定语音频道，实时检测用户进入/离开，支持同时监控多个频道
- **在线时长记录**：每次进出频道自动生成一条会话记录（进入时间、离开时间、在线时长），持久化到 SQLite 数据库
- **最近在线追踪**：面板展示每位用户最近一次下线的时间（精确到分钟）与距今时长（如“2天3小时前”），仅覆盖最近 3 天
- **自动归档**：下线超过 3 天的会话记录自动移入 `voice_sessions_archive` 归档表，主表只保留最近 3 天数据，`report.py` 仍可查询全部历史
- **用户名解析**：通过「获取房间用户列表」接口自动把 UID 解析为用户昵称和头像，优先显示房间昵称，缓存每小时自动刷新
- **网页监控面板**：内置 Web 面板（无框架依赖），展示当前在线、最近 100 条记录，每 5 秒自动刷新
- **密码访问控制**：面板强制密码登录，支持会话保持、失败锁定，防止公网裸奔
- **多维筛选**：面板支持按用户名 / 用户 ID / 频道 ID 筛选
- **历史记录编辑**：「最近记录」中的每条记录可在线修正上下线时间（精确到分钟或秒），在线时长自动重算，也可删除错误的记录；需输入与面板密码相互独立的编辑密码，带失败锁定，未在配置中设置 `edit_password` 时功能整体禁用
- **命令行报表**：`report.py` 提供终端下的记录查询工具，支持按日期、用户、频道过滤
- **启动时在线用户处理**：程序重启时会自动接续数据库中未关闭的会话——仍在线且无离开迹象的用户沿用原会话，进入时间和在线时长跨重启累计；停机期间离开的用户按最后一次成功轮询时间记下线（时长留空，不虚构）；旧版本遗留、能从补记记录确认真实下线时间的会话按真实时间修复。仅在数据库中找不到进行中的会话时（如首次运行），才在用户离开时只记下线时间，保证时长统计真实

## 环境要求

- Python 3.9+
- 依赖仅一个：`requests`

```bash
pip install -r requirements.txt
```

## 准备工作

你需要一个黑盒语音机器人（在[机器人开发平台](https://bot.xiaoheihe.cn)创建），并拿到：

- **Bot Token**：机器人详情页的 token
- **机器人独立 ID**（heybox_id）

机器人需已加入你要监控的房间，并拥有查看频道的基础权限。

## 快速开始

### 1. 生成配置

编辑 `setup.py` 顶部填入机器人信息，然后运行交互式配置向导：

```bash
python setup.py
```

向导会自动拉取机器人已加入的房间列表，选择房间后列出所有频道，勾选要监控的频道即可生成 `config.json`。

### 2. 设置面板密码

打开 `config.json`，设置 `web_password`（必填，否则程序拒绝启动）：

```json
"web_password": "你的访问密码"
```

### 3. 启动监控

```bash
python main.py
```

启动后：

- 控制台实时打印用户进出日志（含用户名）
- 网页面板：`http://127.0.0.1:8080`（端口可在配置中修改）
- 局域网其他设备可通过 `http://你的IP:8080` 访问
- 按 `Ctrl+C` 停止

## 配置说明（config.json）

| 字段 | 说明 |
|------|------|
| `token` | 机器人 Token |
| `heybox_id` | 机器人独立 ID |
| `room_id` | 房间 ID（setup.py 自动填写） |
| `channel_ids` | 要监控的频道 ID 列表（支持多个） |
| `poll_interval` | 轮询间隔秒数，默认 5 |
| `web_port` | 面板端口，默认 8080 |
| `web_password` | 面板访问密码（必填） |
| `edit_password` | 历史记录编辑的独立密码（可选，不设置则编辑功能禁用） |

## 网页面板

打开面板后先输入访问密码登录，登录成功后：

- **当前在线**：显示在线用户的头像、昵称、UID、所在频道、进入时间和本次在线时长
- **最近在线**：最近 3 天内每个用户最后一次下线的时间（精确到分钟）与距今时长；当前在线的用户不重复列出
- **最近记录**：最近 100 条进出记录，含上线/下线时间与在线时长；点击「编辑」可修正上下线时间，点击「删除」可移除记录，两者均需输入独立编辑密码（5 分钟内错 5 次会锁定），编辑时时长自动重算，进行中的会话不可编辑或删除
- **筛选**：支持按用户名或 UID 搜索（服务端同时匹配两者），按频道 ID 过滤
- **退出登录**：面板右上角入口

### 安全机制

- 密码经 SHA-256 哈希后比对，不参与明文比较
- 登录成功后通过 HttpOnly Cookie 保持 7 天会话
- 5 分钟内连续输错 5 次密码，锁定 5 分钟
- 数据接口（`/api/data`）同样受会话保护，未登录返回 401
- 编辑接口（`/api/edit`）与删除接口（`/api/delete`）在会话之外还要求独立的 `edit_password`（SHA-256 哈希比对，共用失败锁定），且密码不出现在代码与仓库中
- 未设置 `web_password` 时程序直接退出，杜绝无密码暴露

## 命令行报表（report.py）

```bash
python report.py              # 最近 100 条记录（含用户名）
python report.py --today      # 只看今天的记录
python report.py --user 12345678        # 只看指定用户
python report.py --channel 34097...     # 只看指定频道
python report.py --all        # 查看全部记录
python report.py --no-names   # 不解析用户名（离线场景）
```

用户名通过房间用户列表接口实时解析；接口不可用时自动降级为仅显示 UID。

## 数据存储

所有进出记录保存在同目录的 `voice_monitor.db`（SQLite），表结构：

```
voice_sessions(id, user_id, channel_id, join_time, leave_time, duration_seconds)                    -- 最近 3 天
voice_sessions_archive(id, user_id, channel_id, join_time, leave_time, duration_seconds, archived_at) -- 3 天前的归档
monitor_state(key, value)                                                                            -- 轮询状态（last_poll:频道ID）
```

- `join_time` 为空的记录表示"程序启动时已在线"的用户，仅记录了下线时间
- 下线时间超过 3 天的已结束会话由监控程序自动移入归档表（约每 30 分钟执行一次，启动时也会先执行一轮），在线中的会话不会被归档
- 用户名不写入数据库，展示时实时解析，用户改名后自动跟随

## 部署建议

### screen 常驻（简单）

```bash
screen -dmS HEYBOX python3 main.py   # 启动
screen -r HEYBOX                     # 查看日志（Ctrl+A D 退出）
```

### systemd 服务（开机自启，推荐生产使用）

```ini
# /etc/systemd/system/heychat-monitor.service
[Unit]
Description=HeyChat Voice Monitor
After=network.target

[Service]
WorkingDirectory=/opt/heychat_voice_monitor
ExecStart=/usr/bin/python3 main.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl enable --now heychat-monitor
```

部署到公网服务器时，记得在安全组/防火墙放行面板端口。

## 常见问题

**面板打开提示"未找到 config.json"？**
先运行 `python setup.py` 生成配置。

**提示"刷新房间用户列表失败"？**
检查 token 是否过期、机器人是否仍在该房间内。昵称缓存失败不影响监控本身，仅用户名显示为"未知用户"。

**某些用户显示"未知用户"？**
刚加入房间的用户可能尚未进入昵称缓存，最长 1 小时后自动恢复。

**端口被占用？**
修改 `config.json` 中的 `web_port`。

## 免责声明

本项目基于黑盒语音开放接口文档开发，仅供学习与个人房间管理使用。请遵守平台开发者服务协议，勿用于高频请求等滥用行为。

## License

MIT
