package com.qwen2api.tx

import com.qwen2api.tx.core.GatewayConfig
import com.qwen2api.tx.core.SystemPromptImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * System Prompt 文本导入的回归测试。
 *
 * 覆盖三条真实踩过的坑：
 *  1. **编码**：Windows 记事本的 BOM-UTF-8、老中文文件的 GBK、无 BOM 的 UTF-16。
 *     按 UTF-8 硬解这些文件**不报错**，只会得到乱码 —— 乱码进上游后表现为
 *     "模型行为莫名其妙"，用户几乎不可能自己定位到编码。所以每类编码都要有断言。
 *  2. **形态**：.md 围栏与各种 JSON 导出必须剥壳；但正文中间的代码块**不能**被剥，
 *     否则提示词里的示例代码会被吃掉，而用户不会发现。
 *  3. **边界**：空文件、纯空白、超长、超限读取，都要给出明确失败或明确截断，
 *     不允许"静默变成空提示词"。
 */
class SystemPromptImportTest {

    private fun ok(outcome: SystemPromptImport.Outcome): SystemPromptImport.Imported {
        assertTrue("期望导入成功，实际: $outcome", outcome is SystemPromptImport.Outcome.Ok)
        return (outcome as SystemPromptImport.Outcome.Ok).value
    }

    private fun failMessage(outcome: SystemPromptImport.Outcome): String {
        assertTrue("期望导入失败，实际: $outcome", outcome is SystemPromptImport.Outcome.Failed)
        return (outcome as SystemPromptImport.Outcome.Failed).message
    }

    private val gbk: Charset = Charset.forName("GBK")

    private val chinesePrompt = "你是专业的技术助手，始终用简体中文回答，不要输出免责声明。"

    // ==================== 编码嗅探 ====================

    @Test
    fun `utf8 with BOM - BOM removed and encoding reported`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            chinesePrompt.toByteArray(Charsets.UTF_8)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        // BOM 必须消失：留在开头会被原样送进上游
        assertFalse(r.text.startsWith("\uFEFF"))
        assertTrue(r.encoding.contains("UTF-8"))
    }

    @Test
    fun `utf8 without BOM - decodes as utf8`() {
        val r = ok(SystemPromptImport.fromBytes(chinesePrompt.toByteArray(Charsets.UTF_8)))
        assertEquals(chinesePrompt, r.text)
        assertEquals("UTF-8", r.encoding)
    }

    @Test
    fun `utf16le with BOM - decoded`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            chinesePrompt.toByteArray(Charsets.UTF_16LE)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        assertTrue(r.encoding.contains("UTF-16LE"))
    }

    @Test
    fun `utf16be with BOM - decoded`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            chinesePrompt.toByteArray(Charsets.UTF_16BE)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        assertTrue(r.encoding.contains("UTF-16BE"))
    }

    @Test
    fun `utf16le without BOM - detected by NUL distribution`() {
        // 无 BOM 的 UTF-16 会把每个 ASCII 字符写成 x\0；靠 NUL 分布识别
        val bytes = chinesePrompt.toByteArray(Charsets.UTF_16LE)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        assertTrue("应识别为 UTF-16，实际: ${r.encoding}", r.encoding.contains("UTF-16"))
    }

    @Test
    fun `gbk chinese - decoded without mojibake`() {
        val bytes = chinesePrompt.toByteArray(gbk)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        assertEquals("GBK", r.encoding)
        // 反向确认这组字节确实不是合法 UTF-8：否则本用例测不到 GBK 分支
        assertFalse(
            "该样本恰好是合法 UTF-8，用例失去意义",
            runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            }.isSuccess,
        )
    }

    @Test
    fun `pure ascii - treated as utf8`() {
        val r = ok(SystemPromptImport.fromBytes("You are a helpful assistant.".toByteArray()))
        assertEquals("You are a helpful assistant.", r.text)
        assertEquals("UTF-8", r.encoding)
    }

    @Test
    fun `illegal bytes - falls back with replacement instead of throwing`() {
        // 0xFF 既不是合法 UTF-8 前导字节，也不是合法 GBK 前导字节（GBK 上限 0xFE），
        // 因此这条用例稳定落在「兜底替换解码」分支上。
        val bytes = byteArrayOf(0xFF.toByte(), 0x41, 0x42, 0xFF.toByte())
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertTrue("兜底解码不应抛异常，且可读部分要保留", r.text.contains("AB"))
    }

    @Test
    fun `utf16be without BOM - detected by control byte parity`() {
        val bytes = chinesePrompt.toByteArray(Charsets.UTF_16BE)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals(chinesePrompt, r.text)
        assertTrue("应识别为 UTF-16BE，实际: ${r.encoding}", r.encoding.contains("UTF-16BE"))
    }

    @Test
    fun `gbk not misdetected as utf16 - parity rule does not fire on clean bytes`() {
        // GBK 正文里不可能出现 <0x20 的控制字节（\t\n\r 除外），
        // 因此「控制字节奇偶分布」这条证据在真实 GBK 文本上必须不成立 ——
        // 否则 GBK 会被误判成 UTF-16，整份变乱码。
        val bytes = "第一行中文提示词\n第二行还是要用中文回答".toByteArray(gbk)
        val r = ok(SystemPromptImport.fromBytes(bytes))
        assertEquals("第一行中文提示词\n第二行还是要用中文回答", r.text)
        assertEquals("GBK", r.encoding)
    }

    @Test
    fun `single corruption in utf8 - keeps utf8 instead of degrading to gbk`() {
        // 只坏一个字节时若改判 GBK，整份中文都会变乱码；
        // 保留一个 U+FFFD 是代价小得多的选择。
        val good = chinesePrompt.toByteArray(Charsets.UTF_8)
        val broken = good.copyOf()
        // 把一个多字节序列的续字节改成非法值
        val idx = good.size - 1
        broken[idx] = (good[idx].toInt() and 0x0F).toByte()
        val r = ok(SystemPromptImport.fromBytes(broken))
        assertTrue("主体中文必须保持可读，实际: ${r.text}", r.text.startsWith("你是专业的技术助手"))
    }

    // ==================== 形态剥离 ====================

    @Test
    fun `markdown whole fence - stripped`() {
        val r = ok(SystemPromptImport.fromText("```markdown\n$chinesePrompt\n```"))
        assertEquals(chinesePrompt, r.text)
        assertEquals("Markdown 代码块", r.shape)
    }

    @Test
    fun `markdown fence inside body - preserved`() {
        // 提示词里要求模型输出代码块，这种围栏必须原样保留
        val src = "回答时按此格式输出：\n```python\nprint(1)\n```\n不要加解释。"
        val r = ok(SystemPromptImport.fromText(src))
        assertEquals(src, r.text)
        assertEquals("纯文本", r.shape)
    }

    @Test
    fun `json flat systemPrompt field - extracted`() {
        val r = ok(SystemPromptImport.fromText("""{"systemPrompt":"$chinesePrompt"}"""))
        assertEquals(chinesePrompt, r.text)
        assertTrue(r.shape.contains("systemPrompt"))
    }

    @Test
    fun `json nested field - still found`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"version":2,"config":{"prompt":"$chinesePrompt"}}""",
            ),
        )
        assertEquals(chinesePrompt, r.text)
    }

    // ---- 壳套壳：真机导入时踩到过，见 SystemPromptImport.extract(MAX_LAYERS) ----

    @Test
    fun `fence wrapping json - inner json is extracted not dumped raw`() {
        // 真机复现过的缺陷：只剥围栏不认内层，整段 JSON 原样进输入框。
        // 那种内容会被回显成「导入成功（Markdown 代码块）」，用户完全看不出问题。
        val r = ok(
            SystemPromptImport.fromText(
                "```json\n{\"instructions\":\"$chinesePrompt\"}\n```",
            ),
        )
        assertEquals(chinesePrompt, r.text)
        assertTrue("应说明剥了两层壳，实际: ${r.shape}", r.shape.contains("instructions"))
        assertTrue(r.shape.contains("Markdown 代码块"))
    }

    @Test
    fun `fence wrapping json with no prompt field - still fails loudly`() {
        val msg = failMessage(SystemPromptImport.fromText("```\n{\"foo\":1}\n```"))
        assertTrue("不能把这段 JSON 当正文导进去，实际: $msg", msg.contains("没有可识别"))
        assertTrue("要给出「去掉围栏原样使用」的出路", msg.contains("去掉外层围栏"))
    }

    @Test
    fun `fence wrapping plain text - shape stays markdown`() {
        // 内层就是纯文本时不该拼出「Markdown 代码块 → 纯文本」这种噪音回显
        val r = ok(SystemPromptImport.fromText("```\n$chinesePrompt\n```"))
        assertEquals("Markdown 代码块", r.shape)
    }

    @Test
    fun `json field whose value is a fence - fence peeled`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"systemPrompt":"```\n$chinesePrompt\n```"}""",
            ),
        )
        assertEquals(chinesePrompt, r.text)
        assertTrue("字段名和围栏都要交代，实际: ${r.shape}", r.shape.contains("systemPrompt"))
        assertTrue(r.shape.contains("Markdown 代码块"))
    }

    @Test
    fun `nested fences - unwrapped until stable`() {
        val r = ok(SystemPromptImport.fromText("```\n```\n$chinesePrompt\n```\n```"))
        assertEquals(chinesePrompt, r.text)
    }

    @Test
    fun `fence nesting beyond limit - stops instead of eating content`() {
        // 超过层数上限就不该再挖：继续猜下去容易把正文当成壳剥掉
        val deep = "```\n".repeat(6) + chinesePrompt + "\n```".repeat(6)
        val r = ok(SystemPromptImport.fromText(deep))
        assertTrue("内容不能凭空丢失，实际: ${r.text.take(40)}", r.text.contains(chinesePrompt))
    }

    @Test
    fun `json message object with role - extracted`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"role":"system","content":"$chinesePrompt"}""",
            ),
        )
        assertEquals(chinesePrompt, r.text)
        assertEquals("JSON 消息对象", r.shape)
    }

    @Test
    fun `json messages array - prefers system over user`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"messages":[{"role":"system","content":"$chinesePrompt"},""" +
                    """{"role":"user","content":"随便聊聊"}]}""",
            ),
        )
        assertEquals(chinesePrompt, r.text)
    }

    @Test
    fun `openai content parts array - concatenated`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"messages":[{"role":"system","content":[""" +
                    """{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}]}""",
            ),
        )
        assertEquals("第一段\n第二段", r.text)
    }

    @Test
    fun `json without prompt field - explicit failure not raw dump`() {
        // 原样把 JSON 塞进输入框等于给模型一段无意义元数据，必须明确报错
        val msg = failMessage(SystemPromptImport.fromText("""{"foo":1,"bar":"x"}"""))
        assertTrue("应说明 JSON 里没有提示词字段，实际: $msg", msg.contains("没有可识别"))
    }

    @Test
    fun `json tool messages - skipped`() {
        val r = ok(
            SystemPromptImport.fromText(
                """{"messages":[""" +
                    """{"role":"tool","content":"{\"result\":123}"},""" +
                    """{"role":"system","content":"$chinesePrompt"}]}""",
            ),
        )
        assertEquals(chinesePrompt, r.text)
    }

    // ==================== 清洗与边界 ====================

    @Test
    fun `zero width chars removed and crlf normalized`() {
        val r = ok(SystemPromptImport.fromText("第一行\u200B指令\r\n第二行\uFEFF指令\r第三行"))
        assertEquals("第一行指令\n第二行指令\n第三行", r.text)
    }

    @Test
    fun `whitespace padding trimmed`() {
        val r = ok(SystemPromptImport.fromText("\n\n   $chinesePrompt   \n\n"))
        assertEquals(chinesePrompt, r.text)
    }

    @Test
    fun `empty file - failed`() {
        assertEquals("文件是空的", failMessage(SystemPromptImport.fromBytes(ByteArray(0))))
    }

    @Test
    fun `whitespace only - failed`() {
        val msg = failMessage(SystemPromptImport.fromText("   \n\t  "))
        assertTrue(msg.contains("空"))
    }

    @Test
    fun `oversize text - truncated with flag`() {
        val max = GatewayConfig.MAX_SYSTEM_PROMPT_CHARS
        val r = ok(SystemPromptImport.fromText("A".repeat(max + 500)))
        assertEquals(max, r.text.length)
        assertTrue("必须标记截断，否则用户不知道尾部丢了", r.truncated)
    }

    @Test
    fun `exact limit - not marked truncated`() {
        val max = GatewayConfig.MAX_SYSTEM_PROMPT_CHARS
        val r = ok(SystemPromptImport.fromText("A".repeat(max)))
        assertEquals(max, r.text.length)
        assertFalse("刚好等于上限不应报截断", r.truncated)
    }

    @Test
    fun `normal text - not marked truncated`() {
        assertFalse(ok(SystemPromptImport.fromText(chinesePrompt)).truncated)
    }

    @Test
    fun `markdown fence and truncation combined`() {
        val max = GatewayConfig.MAX_SYSTEM_PROMPT_CHARS
        val r = ok(SystemPromptImport.fromText("```\n" + "B".repeat(max + 10) + "\n```"))
        assertEquals("Markdown 代码块", r.shape)
        assertEquals(max, r.text.length)
        assertTrue(r.truncated)
    }

    // ==================== 流式限长 ====================

    @Test
    fun `readLimited - normal read`() {
        val data = "hello".toByteArray()
        val out = SystemPromptImport.readLimited(ByteArrayInputStream(data))
        assertEquals("hello", String(out, Charsets.UTF_8))
    }

    @Test
    fun `readLimited - over limit throws TooLarge`() {
        val big = ByteArray(SystemPromptImport.MAX_BYTES + 1)
        var thrown: SystemPromptImport.TooLargeException? = null
        try {
            SystemPromptImport.readLimited(ByteArrayInputStream(big))
        } catch (e: SystemPromptImport.TooLargeException) {
            thrown = e
        }
        assertTrue("超限必须先抛出、不能读满内存", thrown != null)
        assertEquals(SystemPromptImport.MAX_BYTES, thrown!!.limitBytes)
    }

    @Test
    fun `readLimited - exact limit allowed`() {
        val data = ByteArray(SystemPromptImport.MAX_BYTES)
        val out = SystemPromptImport.readLimited(ByteArrayInputStream(data))
        assertEquals(SystemPromptImport.MAX_BYTES, out.size)
    }

    // ==================== 与落盘清洗的一致性 ====================

    @Test
    fun `imported text survives sanitize unchanged`() {
        // 导入结果会被原样交给 ConfigStore.sanitizeSystemPrompt 落盘。
        // 若再清洗一次会变短，说明导入少做了一步，用户会看到"保存后字数变少"。
        val r = ok(SystemPromptImport.fromText("```\n$chinesePrompt\u200B\n```"))
        assertEquals(r.text, com.qwen2api.tx.core.ConfigStore.sanitizeSystemPrompt(r.text))
    }
}
