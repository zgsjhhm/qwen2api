#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenAI 工具调用协议的完整闭环自测（无需外部客户端）。

模拟任意支持 function calling 的客户端会做的事：
  1. 带 tools 发请求
  2. 校验响应是否为标准 tool_calls（finish_reason / 结构 / id 前缀）
  3. 执行本地工具（本地实现 get_weather）
  4. 把 role=tool 的结果回传，验证多轮闭环
  5. 同时覆盖流式与非流式两种模式

用法：
  python3 tools/tool_call_e2e.py <base_url> <api_key> [model]

退出码 0 = 全部通过；非 0 = 有失败项。
"""
import json
import sys
import time
import urllib.request
import urllib.error
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8818/v1"
KEY = sys.argv[2] if len(sys.argv) > 2 else ""
MODEL = sys.argv[3] if len(sys.argv) > 3 else "qwen3.8-max"

PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    mark = "✓" if ok else "✗"
    print(f"  {mark} {name}" + (f"  → {detail}" if detail else ""))
    return ok


def post(path, payload, stream=False, timeout=180):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {KEY}",
            "Accept": "text/event-stream" if stream else "application/json",
            "User-Agent": "tool-call-e2e/1.0",
        },
        method="POST",
    )
    return urllib.request.urlopen(req, timeout=timeout)


TOOLS = [{
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "查询指定城市的当前天气",
        "parameters": {
            "type": "object",
            "properties": {
                "city": {"type": "string", "description": "城市名称，如 北京"}
            },
            "required": ["city"],
        },
    },
}]

# 本地工具实现（模拟客户端侧执行）
WEATHER_DB = {"北京": ("晴", 12, 3), "上海": ("多云", 18, 4), "深圳": ("雷阵雨", 26, 5)}


def run_tool(name, arguments_json):
    if name != "get_weather":
        return json.dumps({"error": f"unknown tool {name}"}, ensure_ascii=False)
    try:
        args = json.loads(arguments_json or "{}")
    except Exception:
        return json.dumps({"error": "bad arguments"}, ensure_ascii=False)
    city = args.get("city", "北京")
    w = WEATHER_DB.get(city, ("未知", "--", "--"))
    return json.dumps(
        {"city": city, "condition": w[0], "temp_c": w[1], "wind_level": w[2]},
        ensure_ascii=False,
    )


# ---------------------------------------------------------------- 非流式
def test_non_stream():
    print("\n[1] 非流式 · 工具调用")
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "北京天气怎么样？请调用工具查询"}],
        "tools": TOOLS,
        "tool_choice": "auto",
        "stream": False,
    }
    with post("/chat/completions", body) as r:
        raw = r.read().decode()
        check("HTTP 200", r.status == 200, f"status={r.status}")

    try:
        resp = json.loads(raw)
    except Exception as e:
        check("响应是合法 JSON", False, str(e)[:120]); print("    RAW:", raw[:400]); return None

    check("无 error 字段", "error" not in resp, str(resp.get("error"))[:120])
    choices = resp.get("choices") or []
    if not check("有 choices", len(choices) > 0):
        print("    RAW:", raw[:400]); return None

    ch = choices[0]
    msg = ch.get("message") or {}
    fr = ch.get("finish_reason")
    tcs = msg.get("tool_calls") or []

    check("finish_reason == tool_calls", fr == "tool_calls", f"实际={fr}")
    if not check("返回 tool_calls 数组", len(tcs) > 0, f"长度={len(tcs)}"):
        print("    message:", json.dumps(msg, ensure_ascii=False)[:400])
        return None

    tc = tcs[0]
    check("tool_call 有 id", bool(tc.get("id")), str(tc.get("id")))
    check("id 以 call_ 开头", str(tc.get("id", "")).startswith("call_"), str(tc.get("id")))
    check("type == function", tc.get("type") == "function", str(tc.get("type")))

    fn = tc.get("function") or {}
    check("函数名正确", fn.get("name") == "get_weather", str(fn.get("name")))
    check("arguments 是字符串", isinstance(fn.get("arguments"), str),
          type(fn.get("arguments")).__name__)

    args_ok = False
    try:
        a = json.loads(fn.get("arguments") or "{}")
        args_ok = "city" in a
        check("arguments 可解析且含 city", args_ok, json.dumps(a, ensure_ascii=False))
    except Exception as e:
        check("arguments 可解析", False, str(e)[:100])

    return resp


def test_multi_turn(prev):
    print("\n[2] 非流式 · 多轮闭环（回传工具结果）")
    if not prev:
        check("依赖上一步结果", False, "上一步失败，跳过"); return
    ch = prev["choices"][0]
    msg = ch["message"]
    tc = msg["tool_calls"][0]
    fn = tc["function"]

    tool_result = run_tool(fn["name"], fn.get("arguments", "{}"))
    print(f"    工具执行结果: {tool_result}")

    body = {
        "model": MODEL,
        "messages": [
            {"role": "user", "content": "北京天气怎么样？请调用工具查询"},
            {"role": "assistant", "content": msg.get("content"), "tool_calls": msg["tool_calls"]},
            {"role": "tool", "tool_call_id": tc["id"], "name": fn["name"], "content": tool_result},
        ],
        "tools": TOOLS,
        "stream": False,
    }
    with post("/chat/completions", body) as r:
        raw = r.read().decode()
        check("HTTP 200", r.status == 200, f"status={r.status}")
    try:
        resp = json.loads(raw)
    except Exception as e:
        check("响应是合法 JSON", False, str(e)[:120]); print("    RAW:", raw[:400]); return

    check("无 error 字段", "error" not in resp, str(resp.get("error"))[:120])
    ch2 = (resp.get("choices") or [{}])[0]
    msg2 = ch2.get("message") or {}
    content = msg2.get("content") or ""
    fr2 = ch2.get("finish_reason")
    print(f"    最终回答: {content[:200]}")

    check("最终 finish_reason == stop（不再继续调工具）", fr2 == "stop", f"实际={fr2}")
    check("最终回答非空", len(content.strip()) > 0, f"长度={len(content)}")
    # 模型应基于工具结果作答
    check("回答中体现了工具结果", any(k in content for k in ["晴", "12", "3 级", "3级", "风"]),
          content[:100])


# ---------------------------------------------------------------- 流式
def test_stream():
    print("\n[3] 流式 · tool_calls 分片拼接")
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "上海天气怎么样？请调用工具查询"}],
        "tools": TOOLS,
        "tool_choice": "auto",
        "stream": True,
    }
    with post("/chat/completions", body, stream=True) as r:
        check("HTTP 200", r.status == 200, f"status={r.status}")
        ctype = r.headers.get("Content-Type", "")
        check("Content-Type 是 SSE", "text/event-stream" in ctype, ctype)

        # 累积 tool_calls 分片（完全按 OpenAI SDK 的做法）
        acc = {}          # index -> {id, name, arguments}
        finish = None
        content_acc = ""
        got_done = False
        n_frames = 0

        for raw_line in r:
            line = raw_line.decode("utf-8", "replace").strip()
            if not line:
                continue
            if line == "data: [DONE]":
                got_done = True
                break
            if not line.startswith("data: "):
                continue
            try:
                j = json.loads(line[6:])
            except Exception:
                continue
            n_frames += 1
            if "error" in j:
                check("流中无 error", False, str(j["error"])[:150]); return
            chs = j.get("choices") or []
            if not chs:
                continue
            ch = chs[0]
            if ch.get("finish_reason"):
                finish = ch["finish_reason"]
            d = ch.get("delta") or {}
            if d.get("content"):
                content_acc += d["content"]
            for tcd in (d.get("tool_calls") or []):
                idx = tcd.get("index", 0)
                slot = acc.setdefault(idx, {"id": "", "name": "", "arguments": ""})
                if tcd.get("id"):
                    slot["id"] = tcd["id"]
                f = tcd.get("function") or {}
                if f.get("name"):
                    slot["name"] = f["name"]
                if f.get("arguments"):
                    slot["arguments"] += f["arguments"]

    check("收到 [DONE]", got_done)
    check("有 SSE 数据帧", n_frames > 0, f"帧数={n_frames}")
    check("finish_reason == tool_calls", finish == "tool_calls", f"实际={finish}")
    check("累积出 tool_calls", len(acc) > 0, f"数量={len(acc)}")

    if not acc:
        print("    正文:", content_acc[:300])
        return

    slot = acc[0]
    check("分片拼出的 id 合法", slot["id"].startswith("call_"), slot["id"])
    check("分片拼出的名字正确", slot["name"] == "get_weather", slot["name"])
    try:
        a = json.loads(slot["arguments"] or "{}")
        check("分片拼出的 arguments 是完整 JSON", "city" in a,
              json.dumps(a, ensure_ascii=False))
    except Exception as e:
        check("分片拼出的 arguments 可解析", False,
              f"{str(e)[:80]}  raw={slot['arguments'][:120]}")

    check("正文里不含工具调用围栏残留", "tool_call" not in content_acc and "```" not in content_acc,
          repr(content_acc[:120]))


# ---------------------------------------------------------------- 无需工具
def test_no_tool_needed():
    print("\n[4] 不需要工具时应正常回答（不误触发）")
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "你好，请用一句话介绍你自己"}],
        "tools": TOOLS,
        "tool_choice": "auto",
        "stream": False,
    }
    with post("/chat/completions", body) as r:
        raw = r.read().decode()
        check("HTTP 200", r.status == 200, f"status={r.status}")
    resp = json.loads(raw)
    ch = (resp.get("choices") or [{}])[0]
    msg = ch.get("message") or {}
    content = msg.get("content") or ""
    fr = ch.get("finish_reason")
    print(f"    回答: {content[:150]}")
    check("finish_reason == stop", fr == "stop", f"实际={fr}")
    check("没有误触发 tool_calls", not (msg.get("tool_calls")), "不应有 tool_calls")
    check("回答非空", len(content.strip()) > 0)
    check("正文无围栏残留", "tool_call" not in content and "```" not in content)


# ---------------------------------------------------------------- tool_choice=none
def test_tool_choice_none():
    print("\n[5] tool_choice=none 应禁用工具")
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "北京天气怎么样？"}],
        "tools": TOOLS,
        "tool_choice": "none",
        "stream": False,
    }
    with post("/chat/completions", body) as r:
        raw = r.read().decode()
        check("HTTP 200", r.status == 200, f"status={r.status}")
    resp = json.loads(raw)
    ch = (resp.get("choices") or [{}])[0]
    msg = ch.get("message") or {}
    check("不应返回 tool_calls", not (msg.get("tool_calls")), "tool_choice=none")
    check("finish_reason == stop", ch.get("finish_reason") == "stop",
          str(ch.get("finish_reason")))


def main():
    print("=" * 72)
    print(f"网关工具调用闭环自测")
    print(f"  Base URL : {BASE}")
    print(f"  Model    : {MODEL}")
    print(f"  Key      : {'已提供' if KEY else '（空）'}")
    print("=" * 72)

    t0 = time.time()
    try:
        p = test_non_stream()
        test_multi_turn(p)
        test_stream()
        test_no_tool_needed()
        test_tool_choice_none()
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:500]
        check("请求未抛 HTTP 错误", False, f"{e.code} {body}")
    except Exception as e:
        check("未发生异常", False, f"{type(e).__name__}: {e}")

    print("\n" + "=" * 72)
    total = len(PASS) + len(FAIL)
    print(f"结果: {len(PASS)}/{total} 通过   (耗时 {time.time()-t0:.1f}s)")
    if FAIL:
        print("失败项:")
        for f in FAIL:
            print("  ✗", f)
        sys.exit(1)
    print("全部通过 ✓")
    sys.exit(0)


if __name__ == "__main__":
    main()
