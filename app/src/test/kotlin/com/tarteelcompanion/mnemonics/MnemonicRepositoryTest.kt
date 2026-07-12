package com.tarteelcompanion.mnemonics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tarteelcompanion.data.AppDatabase
import com.tarteelcompanion.quran.AyahRef
import com.tarteelcompanion.quran.MutashabihatGroup
import com.tarteelcompanion.quran.QuranRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class MnemonicRepositoryTest {

    private class FakeKeys(var key: String? = "test-key") : ApiKeyProvider {
        override suspend fun save(apiKey: String) { key = apiKey }
        override suspend fun load(): String? = key
        override suspend fun clear() { key = null }
    }

    private class FakeLlm(var result: LlmResult = LlmResult.Success("مذكرة تجريبية")) : LlmClient {
        var calls = 0
        override fun generate(apiKey: String, prompt: String): LlmResult {
            calls++
            return result
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var keys: FakeKeys
    private lateinit var llm: FakeLlm
    private lateinit var repo: MnemonicRepository

    private val group = MutashabihatGroup(AyahRef(2, 2), listOf(AyahRef(32, 2)))
    private val target = AyahRef(2, 2)

    private val quran: QuranRepository? get() = com.tarteelcompanion.TestData.quran

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        keys = FakeKeys()
        llm = FakeLlm()
        repo = MnemonicRepository(db.mnemonicDao(), keys, llm)
    }

    @After
    fun tearDown() = db.close()

    private fun requireQuran(): QuranRepository {
        assumeTrue("dataset not fetched", quran != null)
        return quran!!
    }

    @Test
    fun `generation fills a pending mnemonic with arabic text`() = runTest {
        repo.mnemonicFor(group, target)
        val done = repo.generatePending(requireQuran())

        assertTrue(done)
        val m = repo.mnemonicFor(group, target)
        assertEquals(MnemonicStatus.READY, m.status)
        assertEquals(MnemonicSource.LLM, m.source)
        assertEquals("مذكرة تجريبية", m.text)
    }

    @Test
    fun `no key means waiting not failure`() = runTest {
        keys.key = null
        repo.mnemonicFor(group, target)

        val done = repo.generatePending(requireQuran())

        assertFalse(done) // worker retries later
        assertEquals(MnemonicStatus.PENDING, repo.mnemonicFor(group, target).status)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `bad key marks FAILED with reason and does not loop`() = runTest {
        llm.result = LlmResult.Failed("API key rejected (HTTP 401)")
        repo.mnemonicFor(group, target)

        val done = repo.generatePending(requireQuran())

        assertTrue(done) // handled terminally — no retry loop
        val m = repo.mnemonicFor(group, target)
        assertEquals(MnemonicStatus.FAILED, m.status)
        assertTrue(m.failureReason!!.contains("401"))
    }

    @Test
    fun `safety block is terminal and retryable errors are not`() = runTest {
        llm.result = LlmResult.Retryable("HTTP 429")
        repo.mnemonicFor(group, target)
        assertFalse(repo.generatePending(requireQuran()))
        assertEquals(MnemonicStatus.PENDING, repo.mnemonicFor(group, target).status)

        llm.result = LlmResult.Failed("blocked by safety filter: OTHER")
        assertTrue(repo.generatePending(requireQuran()))
        assertEquals(MnemonicStatus.FAILED, repo.mnemonicFor(group, target).status)
    }

    @Test
    fun `user text wins and is never overwritten by generation`() = runTest {
        val m = repo.mnemonicFor(group, target)
        repo.saveUserText(m, "مذكرتي الخاصة")

        repo.generatePending(requireQuran())

        val after = repo.mnemonicFor(group, target)
        assertEquals("مذكرتي الخاصة", after.text)
        assertEquals(MnemonicSource.USER, after.source)
        assertEquals(0, llm.calls) // nothing pending, LLM never called
    }

    @Test
    fun `manual retry re-queues a failed LLM mnemonic but not user text`() = runTest {
        llm.result = LlmResult.Failed("bad key")
        repo.mnemonicFor(group, target)
        repo.generatePending(requireQuran())

        repo.retryFailed(repo.mnemonicFor(group, target))
        assertEquals(MnemonicStatus.PENDING, repo.mnemonicFor(group, target).status)

        llm.result = LlmResult.Success("نجحت الآن")
        repo.generatePending(requireQuran())
        assertEquals("نجحت الآن", repo.mnemonicFor(group, target).text)
    }

    @Test
    fun `prompt carries only canonical verse content`() = runTest {
        var captured: String? = null
        repo = MnemonicRepository(
            db.mnemonicDao(), keys,
            object : LlmClient {
                override fun generate(apiKey: String, prompt: String): LlmResult {
                    captured = prompt
                    return LlmResult.Success("ok")
                }
            },
        )
        repo.mnemonicFor(group, target)
        repo.generatePending(requireQuran())

        val prompt = captured!!
        assertTrue(prompt.contains("2:2"))
        assertTrue(prompt.contains(requireQuran().ayahText(target)!!.take(20)))
        assertFalse(prompt.contains("screenshot", ignoreCase = true))
    }

    @Test
    fun `gemini client classifies http errors`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val client = GeminiClient(baseUrl = server.url("/").toString().trimEnd('/'))

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(client.generate("k", "p") is LlmResult.Failed)

        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429).setBody("{}"))
        assertTrue(client.generate("k", "p") is LlmResult.Retryable)

        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                .setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""),
        )
        val blocked = client.generate("k", "p")
        assertTrue(blocked is LlmResult.Failed && (blocked as LlmResult.Failed).reason.contains("safety"))

        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"مرحبا"}]}}]}"""),
        )
        val ok = client.generate("k", "p")
        assertTrue(ok is LlmResult.Success && (ok as LlmResult.Success).text == "مرحبا")

        server.shutdown()
        assertNull(null)
    }
}
