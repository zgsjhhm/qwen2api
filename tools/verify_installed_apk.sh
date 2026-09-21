#!/system/bin/sh
# 核对"构建产物"是否真的装进了手机。
#
# ## 为什么需要这个脚本
# 2026-09-20 排查 OSS `403 SignatureDoesNotMatch` 时被坑过一次：
#   1. 代码已改（canonical 分隔符 `=` -> `:`），assembleDebug 也真出了新包；
#   2. 但手机上的 pm install 静默失败，跑的仍是改动前的旧 APK；
#   3. "改完再测还是 403" 被读成"这个改动无效"，于是写了"分隔符不是根因"的结论；
#   4. 该结论又差点让正确代码被改回错误形态。
# 真凶是**参照系错了**，不是代码错了。
#
# 构建日志说 BUILD SUCCESSFUL、甚至说"已直装到手机"，都不等于手机上真的换了包。
# 唯一可靠的判据是拿手机上 base.apk 的 SHA-256 跟构建产物比。
#
# 用法（从宿主执行，需要 pm/root）：
#   sh tools/verify_installed_apk.sh com.qwen2api.tx
#   sh tools/verify_installed_apk.sh com.qwen2api.tx app/build/outputs/apk/debug/app-debug.apk
#
# 退出码：0 = 一致；1 = 不一致（装机未生效）；2 = 用法/环境问题
set -u

PKG="${1:-com.qwen2api.tx}"
APK="${2:-}"

if [ -z "$APK" ]; then
    # 默认取工作区里最新的 debug 产物
    for cand in \
        /data/user/0/top.wanxiang.app/files/linux-runtime/workspace/qwen2api-src/app/build/outputs/apk/debug/app-debug.apk \
        app/build/outputs/apk/debug/app-debug.apk
    do
        [ -f "$cand" ] && APK="$cand" && break
    done
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "FAIL: 找不到构建产物 APK（可显式作为第 2 个参数传入）" >&2
    exit 2
fi

INSTALLED=$(pm path "$PKG" 2>/dev/null | sed 's/^package://' | head -n 1)
if [ -z "$INSTALLED" ]; then
    echo "FAIL: $PKG 未安装" >&2
    exit 2
fi

ART_SHA=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1)
INS_SHA=$(sha256sum "$INSTALLED" 2>/dev/null | cut -d' ' -f1)

ART_SIZE=$(stat -c %s "$APK" 2>/dev/null || echo '?')
INS_SIZE=$(stat -c %s "$INSTALLED" 2>/dev/null || echo '?')

# 注：Android 的 `stat -c` 与 GNU 不一致，这里退化为用 wc -c 兜底
case "$ART_SIZE" in
    ''|'?') ART_SIZE=$(wc -c < "$APK" 2>/dev/null || echo '?') ;;
esac
case "$INS_SIZE" in
    ''|'?') INS_SIZE=$(wc -c < "$INSTALLED" 2>/dev/null || echo '?') ;;
esac

echo "构建产物 : $APK"
echo "           sha256=$ART_SHA size=$ART_SIZE"
echo "手机已装 : $INSTALLED"
echo "           sha256=$INS_SHA size=$INS_SIZE"

if [ -n "$ART_SHA" ] && [ "$ART_SHA" = "$INS_SHA" ]; then
    echo "RESULT=OK 手机上跑的就是这个产物，测出来的结果可信"
    exit 0
fi

echo "RESULT=STALE 手机上的包不是这个产物 —— 你现在测的是旧代码！"
echo "  处置：pm install -r -d <apk> 然后重新核对；"
echo "        比构建日志更该信这个指纹。"
exit 1
