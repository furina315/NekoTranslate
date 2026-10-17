package com.nekohome.translate.data

import com.nekohome.translate.engine.OutputCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelProfileTest {

    private val idx = BuiltInProfiles.INDEX_TRANSLATE
    private val hy = BuiltInProfiles.HY_MT2

    @Test
    fun indexPromptWithSource() {
        val p = idx.buildUserPrompt(idx.findLanguage("zh")!!, idx.findLanguage("en")!!, " 你好，世界！ ")
        assertEquals("请将以下中文文本翻译为英语，直接输出翻译结果，不要进行任何解释。\n\n你好，世界！", p)
    }

    @Test
    fun indexPromptAutoSource() {
        val p = idx.buildUserPrompt(Language.AUTO, idx.findLanguage("en")!!, "你好，世界！")
        assertEquals("请将以下文本翻译为英语，直接输出翻译结果，不要进行任何解释。\n\n你好，世界！", p)
    }

    @Test
    fun hyChinesePromptWhenChineseInvolved() {
        val p = hy.buildUserPrompt(hy.findLanguage("en")!!, hy.findLanguage("zh")!!, "Hello")
        assertEquals("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello", p)
    }

    @Test
    fun hyEnglishPromptOtherwise() {
        val p = hy.buildUserPrompt(hy.findLanguage("fr")!!, hy.findLanguage("de")!!, "Bonjour")
        assertEquals(
            "Translate the following text into German. Note that you should only output the translated " +
                "result without any additional explanation:\n\nBonjour",
            p,
        )
    }

    @Test
    fun matching() {
        assertSame(idx, BuiltInProfiles.match("qwen35", "Index-Translate-2B", "x.gguf"))
        assertSame(idx, BuiltInProfiles.match("qwen35", null, "Index-Translate-9B.Q4_K_M.gguf"))
        // Real metadata of the official GGUFs: general.name is just the size label.
        assertSame(idx, BuiltInProfiles.match("qwen35", "2b", "Index-Translate-2B.Q4_K_M.gguf"))
        assertSame(hy, BuiltInProfiles.match("hunyuan-dense", "1.8B", "Hy-MT2-1.8B-Q4_K_M.gguf"))
        assertSame(hy, BuiltInProfiles.match("hunyuan-dense", "1.8B", "renamed.gguf")) // arch fallback
        assertSame(BuiltInProfiles.CUSTOM, BuiltInProfiles.match("llama", "Llama-3", "llama.gguf"))
        // right arch but unrelated model -> custom
        assertSame(BuiltInProfiles.CUSTOM, BuiltInProfiles.match("qwen35", "Qwen3.5-2B-Instruct", "q.gguf"))
    }

    @Test
    fun hyWrapperUsesFullwidthBars() {
        val w = (hy.chatFormat as ChatFormat.Wrapper).template
        assertEquals("<｜hy_begin▁of▁sentence｜><｜hy_User｜>{prompt}<｜hy_Assistant｜>", w)
    }

    @Test
    fun cleanerStripsThinkAndStops() {
        val c = OutputCleaner(listOf("<|im_end|>"))
        assertEquals("", c.clean("<think>\nhmm").text)
        assertEquals("Hello", c.clean("<think>x</think>\n\nHello").text)
        assertEquals("Hi", c.clean("Hi<|im_end|>junk").text)
    }
}
