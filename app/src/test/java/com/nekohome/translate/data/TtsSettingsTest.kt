package com.nekohome.translate.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nekohome.translate.tts.TtsEngineChoice
import com.nekohome.translate.tts.TtsEngineInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 朗读引擎 / 不再提示 persistence through the real Preferences DataStore. */
class TtsSettingsTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tts-settings").toFile()
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        dir.deleteRecursively()
    }

    private fun repository(): SettingsRepository {
        // Wait until the previous DataStore is really gone: opening a second one on the same file
        // while the first is still shutting down made this test flaky.
        if (::scope.isInitialized) runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        return SettingsRepository(store)
    }

    @Test
    fun defaultsFollowSystemEngineAndShowHint() = runBlocking {
        val s = repository().settings.first()
        assertEquals(TtsEngineChoice.SYSTEM_DEFAULT, s.ttsEngine)
        assertFalse(s.chineseVoiceHintDismissed)
    }

    @Test
    fun engineChoiceAndHintSurviveReopen() = runBlocking {
        val first = repository()
        first.setTtsEngine("com.k2fsa.sherpa.onnx.tts.engine")
        first.setChineseVoiceHintDismissed(true)
        // A new DataStore on the same file (as after an app restart).
        val s = repository().settings.first()
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", s.ttsEngine)
        assertTrue(s.chineseVoiceHintDismissed)
    }

    @Test
    fun resetHintAndBackToSystemDefault() = runBlocking {
        val repo = repository()
        repo.setTtsEngine("com.k2fsa.sherpa.onnx.tts.engine")
        repo.setChineseVoiceHintDismissed(true)
        repo.setTtsEngine(TtsEngineChoice.SYSTEM_DEFAULT)
        repo.setChineseVoiceHintDismissed(false)
        val s = repo.settings.first()
        assertEquals(TtsEngineChoice.SYSTEM_DEFAULT, s.ttsEngine)
        assertFalse(s.chineseVoiceHintDismissed)
    }

    @Test
    fun savedEngineThatWasUninstalledFallsBackToDefault() = runBlocking {
        val repo = repository()
        repo.setTtsEngine("com.k2fsa.sherpa.onnx.tts.engine")
        val saved = repo.settings.first().ttsEngine
        val stillThere = listOf(TtsEngineInfo("com.google.android.tts", "Google", system = true))
        assertNull(TtsEngineChoice.resolve(saved, stillThere))
        // The choice itself is kept: reinstalling the engine brings it back.
        assertEquals(saved, TtsEngineChoice.resolve(saved, stillThere + TtsEngineInfo(saved, "sherpa-onnx")))
    }

    @Test
    fun historyIsOnByDefaultAndCanBeTurnedOff() = runBlocking {
        val repo = repository()
        assertTrue(repo.settings.first().historyEnabled)
        repo.setHistoryEnabled(false)
        assertFalse(repo.settings.first().historyEnabled)
    }
}
