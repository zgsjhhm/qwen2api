#!/bin/sh
# 真机端到端：文生图 + 图生图（经本机网关 8818 打真实上游）
#
# 用法: sh tools/e2e_images.sh [outdir]
# 产物: <outdir>/t2i.png  <outdir>/i2i.png  <outdir>/i2i-src.png
set -u

OUT="${1:-/tmp/qwen2api-imgtest}"
BASE=http://127.0.0.1:8818
mkdir -p "$OUT"

KEY=$(curl -s -m 8 -H 'Host: 127.0.0.1' $BASE/admin/api/status \
      | tr ',' '\n' | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')
if [ -z "$KEY" ]; then
  echo "FAIL: 取不到 apiKey"; exit 2
fi
echo "== apiKey len=${#KEY}"

echo
echo "== [0] 网关健康"
curl -s -m 8 $BASE/healthz; echo

echo
echo "== [1] 文生图自描述 GET /v1/images/generations"
curl -s -m 10 -H "Authorization: Bearer $KEY" $BASE/v1/images/generations \
  | head -c 600; echo

echo
echo "== [2] 文生图 POST /v1/images/generations"
T0=$(date +%s)
R=$(curl -s -m 300 -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"qwen-image-2.0-pro","prompt":"一只在窗台上晒太阳的橘猫，写实摄影","size":"1:1","response_format":"url"}' \
  $BASE/v1/images/generations)
T1=$(date +%s)
echo "耗时 $((T1-T0))s"
echo "$R" | head -c 1200; echo

URL=$(echo "$R" | tr ',' '\n' | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | head -n1)
if [ -z "$URL" ]; then
  echo "RESULT_T2I=FAIL 未拿到图片 URL"
else
  echo "== [2b] 下载产出图"
  curl -sL -m 120 -o "$OUT/t2i.png" "$URL"
  ls -l "$OUT/t2i.png"
  echo -n "magic: "; head -c 8 "$OUT/t2i.png" | od -An -tx1 | tr -d '\n'; echo
  SIZE=$(wc -c < "$OUT/t2i.png")
  if [ "$SIZE" -gt 10000 ]; then echo "RESULT_T2I=PASS ($SIZE bytes)"; else echo "RESULT_T2I=FAIL 文件过小"; fi
fi

echo
echo "== [3] 图生图自描述 GET /v1/images/edits"
curl -s -m 10 -H "Authorization: Bearer $KEY" $BASE/v1/images/edits | head -c 700; echo

echo
echo "== [4] 图生图 POST /v1/images/edits（multipart，源图用上一步产出）"
if [ -f "$OUT/t2i.png" ]; then
  cp "$OUT/t2i.png" "$OUT/i2i-src.png"
  T0=$(date +%s)
  R2=$(curl -s -m 300 -H "Authorization: Bearer $KEY" \
    -F "image=@$OUT/i2i-src.png;type=image/png" \
    -F "prompt=把背景改成夜晚的星空，猫保持不动" \
    -F "model=qwen-image-2.0-pro" \
    -F "size=1:1" \
    $BASE/v1/images/edits)
  T1=$(date +%s)
  echo "耗时 $((T1-T0))s"
  echo "$R2" | head -c 1200; echo
  URL2=$(echo "$R2" | tr ',' '\n' | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | head -n1)
  if [ -z "$URL2" ]; then
    echo "RESULT_I2I=FAIL 未拿到图片 URL"
  else
    curl -sL -m 120 -o "$OUT/i2i.png" "$URL2"
    ls -l "$OUT/i2i.png"
    SIZE2=$(wc -c < "$OUT/i2i.png")
    if [ "$SIZE2" -gt 10000 ]; then echo "RESULT_I2I=PASS ($SIZE2 bytes)"; else echo "RESULT_I2I=FAIL 文件过小"; fi
    # 源图回显检查：产出 URL 不应等于源图 URL
    echo "source_url==$URL"
    echo "edit_url  ==$URL2"
  fi
else
  echo "SKIP: 没有源图"
fi

echo
echo "== [5] 图生图 JSON 形态（base64 内联，等价路径）"
if [ -f "$OUT/t2i.png" ]; then
  B64=$(base64 -w0 < "$OUT/t2i.png" 2>/dev/null || base64 "$OUT/t2i.png" | tr -d '\n')
  printf '{"model":"qwen-image-2.0-pro","prompt":"把这只猫换成蓝色毛","image":"data:image/png;base64,%s"}' "$B64" > "$OUT/req.json"
  R3=$(curl -s -m 300 -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    --data-binary @"$OUT/req.json" $BASE/v1/images/edits)
  echo "$R3" | head -c 900; echo
  case "$R3" in
    *'"url"'*) echo "RESULT_I2I_JSON=PASS" ;;
    *) echo "RESULT_I2I_JSON=FAIL" ;;
  esac
fi

echo
echo "== 产物目录: $OUT"
ls -l "$OUT"
