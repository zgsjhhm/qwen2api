#!/bin/sh
# 常驻：维持 adb 连接并转发 Chrome CDP 通道
# 由 process 工具托管（不能让 adb daemon 因会话结束而退出）
set -u
ADB=/opt/taixu/bin/adb
PORT="${1:-34921}"

echo "[tunnel] 启动 adb server"
$ADB start-server 2>&1 || true

while true; do
  echo "[tunnel] connect 127.0.0.1:$PORT"
  $ADB connect "127.0.0.1:$PORT" 2>&1 || true

  # 转发 Chrome devtools 到本地 9222
  $ADB forward --remove tcp:9222 2>/dev/null || true
  $ADB forward tcp:9222 localabstract:chrome_devtools_remote 2>&1 || true

  echo "[tunnel] 保活中..."
  # 保持 adb server 存活；若断开则重连
  sleep 15
  if ! $ADB devices 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
    echo "[tunnel] 设备掉线，重连"
    continue
  fi
done
