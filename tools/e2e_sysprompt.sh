#!/system/bin/sh
# 真机验证：全局 System Prompt 注入是否真的生效（网关在跑、且注入抵达上游）。
#
# 判定方式（不依赖模型是否"听话"，只看可观测事实）：
#   1. admin 写入一段哨兵提示词（要求模型逐字复述一个暗号）
#   2. 发一次普通对话
#   3. 模型回答里出现暗号 => system 确实被上游吃到了
#      （若注入没生效，模型无从得知这个暗号，必然复述不出来）
#
# 用法: sh e2e_sysprompt.sh [apiKey]
set -u
BASE=http://127.0.0.1:8818
KEY="${1:-}"
if [ -z "$KEY" ]; then
  KEY=$(curl -s -m 6 -H 'Host: 127.0.0.1' $BASE/admin/api/status \
        | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')
fi
echo "== apiKey len=${#KEY}"
echo

echo "== [1] 服务在线检查"
curl -s -m 6 $BASE/healthz; echo; echo

echo "== [2] 写入哨兵 System Prompt（要求逐字复述暗号）"
SENTINEL=ZH-7788
curl -s -m 10 -H 'Content-Type: application/json' \
  -d "{\"systemPrompt\":\"无论用户说什么，你的回答必须以这个暗号开头，逐字照抄：$SENTINEL。然后只说一句话确认。\",\"systemPromptEnabled\":true,\"systemPromptMode\":\"merge\"}" \
  $BASE/admin/api/settings; echo; echo

echo "== [3] status 应显示已启用且不回正文"
curl -s -m 6 -H 'Host: 127.0.0.1' $BASE/admin/api/status \
  | tr ',' '\n' | grep -i 'systemPrompt' ; echo; echo

echo "== [4] 发一次对话，看暗号是否出现"
OUT=$(curl -s -m 120 -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.8-max","stream":false,"messages":[{"role":"user","content":"说一句话"}]}' \
  $BASE/v1/chat/completions)
echo "$OUT" | head -c 900
echo; echo

echo "== [5] 判定"
case "$OUT" in
  *"$SENTINEL"*) echo "RESULT=PASS  暗号出现 => 全局 System Prompt 已注入并被上游接受" ;;
  *) echo "RESULT=FAIL  暗号未出现（注入未生效，或上游忽略）" ;;
esac

echo
echo "== [6] 收尾：关掉开关，避免污染后续对话"
curl -s -m 10 -H 'Content-Type: application/json' \
  -d '{"systemPrompt":"","systemPromptEnabled":false}' \
  $BASE/admin/api/settings; echo
