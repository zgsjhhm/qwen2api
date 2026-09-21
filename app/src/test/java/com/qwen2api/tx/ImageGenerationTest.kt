package com.qwen2api.tx

import com.qwen2api.tx.core.GeneratedImage
import com.qwen2api.tx.core.ImageRequest
import com.qwen2api.tx.core.QwenImageClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文生图纯逻辑测试（不触网）。
 *
 * 这些用例覆盖的是**用户最容易踩的输入差异**：OpenAI 习惯传
 * `qwen-image` / `1024x1024`，而上游只认 `qwen-image-2.0-pro` / `16:9`。
 * 映射一旦出错，表现是「上游说模型不存在」或「尺寸被忽略」，
 * 排查成本全落在用户身上，所以这里逐条钉死。
 */
class ImageGenerationTest {

    // ---------------- 模型名归一化 ----------------

    @Test
    fun `别名 qwen-image 落到上游默认模型`() {
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel("qwen-image"))
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel("Qwen-Image"))
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel("  qwen-image  "))
    }

    @Test
    fun `带组织前缀与大小写都能识别`() {
        assertEquals("qwen-image-3.0-pro", QwenImageClient.normalizeModel("qwen/Qwen-Image-3.0-pro"))
        assertEquals("qwen-image-3.0-pro", QwenImageClient.normalizeModel("QWEN-IMAGE-3.0-PRO"))
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel("qwen-image-2.0"))
    }

    @Test
    fun `未知模型回落到默认而不是透传给上游`() {
        // 关键：透传会让上游报「模型不存在」，用户以为网关坏了。
        // 回落 + 后续响应里回显实际模型，是更可解释的行为。
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel("gpt-image-1"))
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel(""))
        assertEquals("qwen-image-2.0-pro", QwenImageClient.normalizeModel(null))
    }

    @Test
    fun `auto 只在 3 point 0 pro 上成立`() {
        assertEquals("qwen-image-3.0-pro", QwenImageClient.normalizeModel("auto"))
    }

    // ---------------- 尺寸归一化 ----------------

    @Test
    fun `合法比例原样通过`() {
        for (s in QwenImageClient.ALLOWED_SIZES) {
            assertEquals(s, QwenImageClient.normalizeSize(s, "qwen-image-2.0-pro"))
        }
    }

    @Test
    fun `像素尺寸映射到最近比例`() {
        assertEquals("1:1", QwenImageClient.normalizeSize("1024x1024", "qwen-image-2.0-pro"))
        assertEquals("16:9", QwenImageClient.normalizeSize("1280x720", "qwen-image-2.0-pro"))
        assertEquals("9:16", QwenImageClient.normalizeSize("720*1280", "qwen-image-2.0-pro"))
        assertEquals("4:3", QwenImageClient.normalizeSize("1024×768", "qwen-image-2.0-pro"))
    }

    @Test
    fun `空尺寸回落到模型默认`() {
        assertEquals("16:9", QwenImageClient.normalizeSize("", "qwen-image-2.0-pro"))
        assertEquals("auto", QwenImageClient.normalizeSize("", "qwen-image-3.0-pro"))
        // 2.0 不支持 auto -> 必须回落到该模型默认，否则上游会拿到非法值
        assertEquals("16:9", QwenImageClient.normalizeSize("auto", "qwen-image-2.0-pro"))
    }

    @Test
    fun `非法尺寸不炸且回落到默认`() {
        assertEquals("16:9", QwenImageClient.normalizeSize("huge", "qwen-image-2.0-pro"))
        assertEquals("16:9", QwenImageClient.normalizeSize("0x0", "qwen-image-2.0-pro"))
        assertEquals("16:9", QwenImageClient.normalizeSize("16:9:4", "qwen-image-2.0-pro"))
    }

    @Test
    fun `nearestSize 覆盖全部合法比例`() {
        assertEquals("1:1", QwenImageClient.nearestSize(1.0))
        assertEquals("3:4", QwenImageClient.nearestSize(0.75))
        assertEquals("4:3", QwenImageClient.nearestSize(1.333))
        assertEquals("16:9", QwenImageClient.nearestSize(1.777))
        assertEquals("9:16", QwenImageClient.nearestSize(0.5625))
    }

    @Test
    fun `sizeToPixels 各比例都是正数`() {
        for (s in QwenImageClient.ALLOWED_SIZES) {
            val (w, h) = QwenImageClient.sizeToPixels(s)
            assertTrue("$s 宽高应为正", w > 0 && h > 0)
        }
    }

    // ---------------- t2i 请求体字段布局（回归重点） ----------------

    /**
     * 这是整条文生图链路最容易写错的一处。
     *
     * 现场证据：把图像模型填进顶层 `model` 时上游返回
     * `{"code":"Not_Found","details":"Model not found"}` —— 图像模型不在
     * LLM 模型表里，无法作为顶层 model 路由；前端 bundle 里顶层 model
     * 永远是用户选的 qwen3.x，图像模型只出现在 `messages[0].extra.meta.model`。
     */
    @Test
    fun `顶层 model 用文本 LLM 而图像模型只在 extra meta`() {
        val p = QwenImageClient.buildImagePayload(
            chatId = "c1",
            prompt = "一只橘子猫",
            imageModel = "qwen-image-2.0-pro",
            llmModel = "qwen3.8-max",
            size = "16:9",
        )
        assertEquals("qwen3.8-max", p.getString("model"))
        val msg = p.getJSONArray("messages").getJSONObject(0)
        assertEquals("", msg.getString("model"))
        assertEquals("qwen3.8-max", msg.getJSONArray("models").getString(0))
        val meta = msg.getJSONObject("extra").getJSONObject("meta")
        assertEquals("qwen-image-2.0-pro", meta.getString("model"))
        assertEquals("t2i", meta.getString("subChatType"))
        assertEquals("16:9", meta.getString("size"))
        // 顶层不能再出现图像模型（extra.meta.model 有它是应该的，所以要按顶层字段判）
        assertFalse(p.getString("model").startsWith("qwen-image"))
    }

    @Test
    fun `t2i 标记同时出现在三处`() {
        val p = QwenImageClient.buildImagePayload(
            chatId = "c1",
            prompt = "x",
            imageModel = "qwen-image-3.0-pro",
            llmModel = "qwen3.8-max",
            size = "auto",
        )
        val msg = p.getJSONArray("messages").getJSONObject(0)
        assertEquals("t2i", msg.getString("chat_type"))
        assertEquals("t2i", msg.getString("sub_chat_type"))
        assertEquals(
            "t2i",
            msg.getJSONObject("extra").getJSONObject("meta").getString("subChatType"),
        )
    }

    @Test
    fun `比例同时下发顶层与 info`() {
        val p = QwenImageClient.buildImagePayload(
            chatId = "c1",
            prompt = "x",
            imageModel = "qwen-image-2.0-pro",
            llmModel = "qwen3.8-max",
            size = "9:16",
        )
        assertEquals("9:16", p.getString("size"))
        val msg = p.getJSONArray("messages").getJSONObject(0)
        assertEquals("9:16", msg.getJSONObject("info").getString("size"))
    }

    @Test
    fun `建会话的 models 也必须是 LLM`() {
        val c = QwenImageClient.buildImageChatPayload("qwen3.8-max")
        assertEquals("qwen3.8-max", c.getJSONArray("models").getString(0))
        assertEquals("t2i", c.getString("chat_type"))
        assertFalse(c.toString().contains("qwen-image"))
    }

    @Test
    fun `负向提示词与空 size 的边界`() {
        val p = QwenImageClient.buildImagePayload(
            chatId = "c1",
            prompt = "x",
            imageModel = "qwen-image-2.0-pro",
            llmModel = "qwen3.8-max",
            size = "",
            negativePrompt = "  blurry  ",
        )
        assertEquals("blurry", p.getString("negative_prompt"))
        assertFalse(p.has("size"))
        val msg = p.getJSONArray("messages").getJSONObject(0)
        assertFalse(msg.has("info"))
        assertFalse(msg.getJSONObject("extra").getJSONObject("meta").has("size"))
    }

    // ---------------- 数据模型 ----------------

    @Test
    fun `ImageRequest 默认值符合上游要求`() {
        val r = ImageRequest(prompt = "a cat", model = "")
        assertEquals(1, r.n)
        assertEquals("", r.size)
        assertEquals(false, r.b64Only)
        assertEquals("", r.negativePrompt)
    }

    @Test
    fun `GeneratedImage 可表达仅有 url 无 b64 的情形`() {
        val img = GeneratedImage(url = "https://x/y.png", b64 = "", width = 1024, height = 1024)
        assertEquals("https://x/y.png", img.url)
        assertEquals("", img.b64)
        assertNull(null as String?)
    }
}
