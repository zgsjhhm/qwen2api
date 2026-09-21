package com.qwen2api.tx

import com.qwen2api.tx.core.ImageEvent
import com.qwen2api.tx.core.QwenImageSseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文生图 SSE 解析测试。
 *
 * 上游字段形状在小版本间会漂移（image_gen / image_gen_tool、content 有时是
 * 数组、图片地址有时藏在 extra），解析失败时用户只看到「没出图」，
 * 真实原因却只是字段换了个位置。这里把**已知的几种真实形状**都钉住。
 */
class ImageSseParserTest {

    private fun data(json: String): String = "data: $json\n"

    @Test
    fun `从 image_gen 事件取出图片地址与尺寸`() {
        val p = QwenImageSseParser()
        val evts = p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen",
                   "content":"https://cdn.qwen.ai/out/abc.png",
                   "extra":{"output_image_hw":[[1328,1328]]}}}]}""",
            ),
        )
        val img = evts.filterIsInstance<ImageEvent.Image>().single()
        assertEquals("https://cdn.qwen.ai/out/abc.png", img.url)
        assertEquals(1328, img.width)
        assertEquals(1328, img.height)
        assertEquals(1, p.images.size)
    }

    @Test
    fun `尺寸用对象形态也能解析`() {
        val p = QwenImageSseParser()
        p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen",
                   "content":"https://x/1.png",
                   "extra":{"output_image_hw":[{"width":1024,"height":768}]}}}]}""",
            ),
        )
        assertEquals(1024, p.images[0].width)
        assertEquals(768, p.images[0].height)
    }

    @Test
    fun `缺尺寸时不报错且宽高为 0`() {
        val p = QwenImageSseParser()
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"https://x/2.png"}}]}"""))
        assertEquals(1, p.images.size)
        assertEquals(0, p.images[0].width)
    }

    @Test
    fun `content 是数组时逐个提取`() {
        val p = QwenImageSseParser()
        val evts = p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen",
                   "content":["https://x/a.png","https://x/b.png"]}}]}""",
            ),
        )
        assertEquals(2, evts.filterIsInstance<ImageEvent.Image>().size)
        assertEquals(2, p.images.size)
    }

    @Test
    fun `图片地址藏在 extra 时也能找到`() {
        val p = QwenImageSseParser()
        p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen","content":"",
                   "extra":{"image_url":"https://x/c.png","output_image_hw":[[512,512]]}}}]}""",
            ),
        )
        assertEquals("https://x/c.png", p.images[0].url)
        assertEquals(512, p.images[0].width)
    }

    @Test
    fun `相对路径会补全为绝对地址`() {
        val p = QwenImageSseParser()
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"/api/v2/files/f1/content"}}]}"""))
        assertEquals("https://chat.qwen.ai/api/v2/files/f1/content", p.images[0].url)
    }

    @Test
    fun `协议相对地址补 https`() {
        val p = QwenImageSseParser()
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"//cdn.x/p.png"}}]}"""))
        assertEquals("https://cdn.x/p.png", p.images[0].url)
    }

    @Test
    fun `同一地址重复推送只计一张`() {
        val p = QwenImageSseParser()
        val body = data("""{"choices":[{"delta":{"phase":"image_gen","content":"https://x/same.png"}}]}""")
        p.feed(body)
        p.feed(body)
        p.feed(body)
        assertEquals(1, p.images.size)
    }

    @Test
    fun `先无尺寸后有尺寸时补齐宽高`() {
        val p = QwenImageSseParser()
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"https://x/4.png"}}]}"""))
        assertEquals(0, p.images[0].width)
        p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen","content":"https://x/4.png",
                   "extra":{"output_image_hw":[[600,800]]}}}]}""",
            ),
        )
        assertEquals(800, p.images[0].width)
        assertEquals(600, p.images[0].height)
    }

    /**
     * 回归：`output_image_hw` 的元素顺序是 **[高, 宽]**。
     *
     * 现场证据：请求 `size=16:9` 时上游回 `[[1536,2688]]`；把同一响应里的
     * CDN 地址下载下来读 PNG IHDR 得到 2688x1536。所以 1536 是高。
     * 按 [宽,高] 读会让每张出图都转 90°，而正方形图（1:1）恰好掩盖这个 bug
     * —— 这就是必须有这条非方形用例的原因。
     */
    @Test
    fun `output_image_hw 按高宽顺序解析`() {
        val p = QwenImageSseParser()
        p.feed(
            data(
                """{"choices":[{"delta":{"phase":"image_gen","content":"https://x/hw.png",
                   "extra":{"output_image_hw":[[1536,2688]]}}}]}""",
            ),
        )
        assertEquals(2688, p.images[0].width)
        assertEquals(1536, p.images[0].height)
    }

    @Test
    fun `思考事件归到 Thinking 且不污染图片`() {
        val p = QwenImageSseParser()
        val evts = p.feed(data("""{"choices":[{"delta":{"phase":"image_gen_think","content":"规划中"}}]}"""))
        assertTrue(evts.any { it is ImageEvent.Thinking })
        assertEquals(0, p.images.size)
        assertEquals("规划中", p.thinking)
    }

    @Test
    fun `answer 里的文字作为说明而非图片`() {
        val p = QwenImageSseParser()
        val evts = p.feed(data("""{"choices":[{"delta":{"phase":"answer","content":"已为你生成"}}]}"""))
        assertTrue(evts.any { it is ImageEvent.Caption })
        assertEquals("已为你生成", p.captions)
        assertEquals(0, p.images.size)
    }

    @Test
    fun `content_list 打包形态也能刮出图片`() {
        val p = QwenImageSseParser()
        p.feed(
            data(
                """{"choices":[{"delta":{"content_list":[
                   {"phase":"image_gen","content":"https://x/l.png","extra":{"output_image_hw":[[640,640]]}}
                   ]}}]}""",
            ),
        )
        assertEquals(1, p.images.size)
        assertEquals(640, p.images[0].width)
    }

    @Test
    fun `错误帧被识别为 Error 事件`() {
        val p = QwenImageSseParser()
        val evts = p.feed(data("""{"success":false,"data":{"code":"data_inspection_failed","details":"内容不合规"}}"""))
        val err = evts.filterIsInstance<ImageEvent.Error>().single()
        assertEquals("data_inspection_failed", err.code)
        assertTrue(err.message.contains("内容不合规"))
    }

    @Test
    fun `跨 chunk 断行也能正确解析`() {
        val p = QwenImageSseParser()
        // 模拟 TCP 分片把一行拆成两半
        val line = """{"choices":[{"delta":{"phase":"image_gen","content":"https://x/split.png"}}]}"""
        val half = line.length / 2
        p.feed("data: " + line.substring(0, half))
        assertEquals(0, p.images.size)
        p.feed(line.substring(half) + "\n")
        assertEquals(1, p.images.size)
    }

    @Test
    fun `DONE 与空行被忽略`() {
        val p = QwenImageSseParser()
        val evts = p.feed("data: [DONE]\n\ndata: \n\n")
        assertEquals(0, evts.size)
    }

    @Test
    fun `非法 JSON 不抛异常`() {
        val p = QwenImageSseParser()
        val evts = p.feed("data: {not json\n")
        assertEquals(0, evts.size)
    }

    @Test
    fun `response completed 触发 Completed 事件`() {
        val p = QwenImageSseParser()
        val evts = p.feed(data("""{"response.completed":{},"usage":{"input_tokens":5}}"""))
        assertTrue(evts.any { it is ImageEvent.Completed })
        assertTrue(p.usage != null)
    }

    @Test
    fun `data URI 形态的图片被原样保留`() {
        val p = QwenImageSseParser()
        val png = "data:image/png;base64,iVBORw0KGgo="
        p.feed(data("""{"choices":[{"delta":{"phase":"image_gen","content":"$png"}}]}"""))
        assertEquals(png, p.images[0].url)
    }
}
