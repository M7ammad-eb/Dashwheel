package com.openauto.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Decides when a spoken question is over from the microphone's loudness: once
 * the driver has spoken, a stretch of quiet sends it; nothing said at all
 * gives up. Pure, so it's unit-tested. Car noise can keep the level up, hence
 * the hard limit (and the Send button).
 */
internal class SilenceDetector(
    private val speechLevel: Int = 2_500,
    private val quietLevel: Int = 1_500,
    private val quietMs: Long = 1_500,
    private val waitMs: Long = 6_000,
    private val maxMs: Long = 15_000
) {
    enum class Verdict { LISTEN, DONE, NOTHING_HEARD }

    private var startedAt: Long? = null
    private var spoke = false
    private var quietSince: Long? = null

    /** One loudness reading (0..32767, as MediaRecorder gives it) at [now]. */
    fun feed(amplitude: Int, now: Long): Verdict {
        val start = startedAt ?: now.also { startedAt = it }
        when {
            amplitude >= speechLevel -> {
                spoke = true
                quietSince = null
            }
            spoke && amplitude < quietLevel -> {
                val since = quietSince ?: now.also { quietSince = it }
                if (now - since >= quietMs) return Verdict.DONE
            }
            else -> quietSince = null
        }
        if (now - start >= maxMs) return if (spoke) Verdict.DONE else Verdict.NOTHING_HEARD
        if (!spoke && now - start >= waitMs) return Verdict.NOTHING_HEARD
        return Verdict.LISTEN
    }
}

/** One question and its answer. */
data class Exchange(val heard: String, val answer: String)

/**
 * The spoken question about a fault for Gemini, with what the mechanic already
 * said as context. Its advice lines and answer rules serve [CarQuestionPrompt]
 * too. Pure, so it's unit-tested.
 */
internal object QuestionPrompt {

    val SCHEMA: JSONObject
        get() = JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("heard", JSONObject().put("type", "STRING"))
                .put("answer", JSONObject().put("type", "STRING"))
        ).put("required", JSONArray(listOf("heard", "answer")))

    fun build(
        car: String,
        language: AiLanguage,
        codes: List<String>,
        diagnosis: Diagnosis?,
        focus: String,
        previous: Exchange?,
        carWarnings: List<String> = emptyList()
    ): String = buildString {
        appendLine(MechanicPersona.of(car) + " You are talking with its driver.")
        appendLine("Its stored fault codes: ${codes.joinToString(", ")}. The driver is looking at $focus.")
        if (carWarnings.isNotEmpty()) {
            appendLine("The car's own dashboard also shows (its body computer, not engine codes): ${carWarnings.joinToString("; ")}.")
        }
        diagnosis?.let { d ->
            appendLine("What you already told the driver: ${d.summary} ${d.overview}".trim())
            adviceLines(d).forEach { appendLine(it) }
        }
        previous?.let { appendLine(previousLine(it)) }
        appendLine()
        appendAnswerRules(this, language)
        append("If you are not sure, say so rather than guess, and never tell the driver a fault is safe to ignore without good grounds.")
    }

    /** One line per code the mechanic explained: what it is, causes, repair, cost, whether to drive on. */
    fun adviceLines(d: Diagnosis): List<String> = d.codes.map { c ->
        val facts = listOfNotNull(
            c.meaning.ifBlank { null },
            c.causes.takeIf { it.isNotEmpty() }?.let { "likely causes: " + it.joinToString("; ") },
            c.checkFirst.ifBlank { null }?.let { "check first: $it" },
            c.repair.ifBlank { null }?.let { "usual repair: $it" },
            c.cost.ifBlank { null }?.let { "cost: $it" },
            c.driving.ifBlank { null }?.let { "driving: $it" }
        )
        "- ${c.code}: ${facts.joinToString(". ")}"
    }

    /** So "and how much?" makes sense. */
    fun previousLine(previous: Exchange): String =
        "The driver's previous question was \"${previous.heard}\" and you answered \"${previous.answer}\"."

    /** What the recording is and how to answer it: short, spoken, in the mechanic's language. */
    fun appendAnswerRules(out: StringBuilder, language: AiLanguage) {
        out.appendLine("The attached recording is the driver asking you a question out loud.")
        out.appendLine("Answer in ${language.promptName}, with correct spelling and all accents, the way a mechanic would in person: 2 to 4 short sentences, concrete and specific to this car. The answer is read aloud, so no lists and no symbols.")
        out.appendLine("- heard: the driver's question as you understood it, written in ${language.promptName}.")
        out.appendLine("- answer: your answer. If the recording holds no clear question, say so in one sentence.")
    }

    /** The question as heard and the answer; null when unusable. */
    fun parse(text: String): Exchange? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val answer = json.optString("answer").trim().ifEmpty { return null }
        return Exchange(json.optString("heard").trim(), answer)
    }
}

/** Records the question to a small AAC file ([name] in the cache), the format Gemini takes as is. */
internal class QuestionRecorder(private val context: Context, name: String = "question.aac") {
    private val file = File(context.cacheDir, name)
    private var recorder: MediaRecorder? = null

    fun start() {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    /** Loudest level since the last call, 0..32767. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Stops and returns the recording; null when nothing usable was captured. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        // stop() throws when nothing was recorded yet.
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val audio = if (ok) runCatching { file.readBytes() }.getOrNull() else null
        file.delete()
        return audio
    }

    fun release() {
        stop()
    }
}

/**
 * A question asked out loud, about a fault on screen or about the car as it
 * is now, answered by the AI mechanic and read back. The recording goes to
 * Gemini as is, which hears it itself, so it doesn't depend on the head unit
 * having speech recognition.
 */
object AskMechanic {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Thinking : State
        data class Answered(val exchange: Exchange) : State
        data object NothingHeard : State
        /** [reason] is already worded for the screen. */
        data class Failed(val reason: String) : State
    }

    private const val BUDGET_MS = 60_000L
    // Shorter than this is a click, not a question.
    private const val MIN_AUDIO_BYTES = 2_000
    // A follow-up ("and how much?") is read against the last exchange on the same topic, if this recent.
    private const val PREVIOUS_MS = 10 * 60_000L
    // How long the "I'm listening" cue may take to start, then to finish, before recording anyway.
    private const val CUE_START_MS = 1_500L
    private const val CUE_MAX_MS = 4_000L
    // The speakers' own tail after the voice ends, so the cue isn't in the recording.
    private const val CUE_TAIL_MS = 250L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: QuestionRecorder? = null
    private var job: Job? = null
    private var question: Question? = null
    private var previous: Previous? = null
    // Held while recording: music pauses or ducks, so the question is heard over it.
    private var audioFocus: AudioFocusRequest? = null

    /** What a question is about: one fault on screen, or the car as it is now. */
    private sealed interface Topic {
        /** Follow-ups are matched on it. */
        val key: String

        class Fault(val code: String, val codes: List<String>, val diagnosis: Diagnosis?) : Topic {
            override val key: String get() = code
        }

        data object Car : Topic {
            override val key: String get() = "car"
        }
    }

    /** [handsFree]: asked with nothing to look at, so every outcome is said out loud too. */
    private class Question(val context: Context, val topic: Topic, val handsFree: Boolean)

    private class Previous(val key: String, val exchange: Exchange, val at: Long)

    /** Starts listening for a question about [focus]; while listening, sends what was said. */
    fun listen(context: Context, focus: String, codes: List<String>, diagnosis: Diagnosis?) =
        start(context, Topic.Fault(focus, codes, diagnosis), handsFree = false)

    /**
     * Starts listening for a question about the car as it is now (the fault
     * codes tile's microphone); while listening, sends what was said.
     */
    fun listenAboutCar(context: Context) = start(context, Topic.Car, handsFree = false)

    /**
     * The same with nothing to tap or read: "I'm listening" first, and the
     * answer, or why there's none, said out loud. A second press sends.
     */
    fun listenHandsFree(context: Context) = start(context, Topic.Car, handsFree = true)

    private fun start(context: Context, topic: Topic, handsFree: Boolean) {
        when (_state.value) {
            State.Listening -> return send()
            State.Thinking -> return
            else -> Unit
        }
        val app = context.applicationContext
        val config = AiSettings.load(app)
        val aiText = config.language.resources(app)
        if (config.apiKey.isBlank()) {
            _state.value = State.Failed(AiMechanic.noteText(app, AiMechanic.Note.NoKey))
            if (handsFree) say(app, config, aiText.getString(R.string.ai_ask_say_no_key), show = true)
            return
        }
        // The screen asks for the microphone first; a question asked hands-free can't.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.Failed(aiText.getString(R.string.ai_ask_mic_denied))
            if (handsFree) say(app, config, aiText.getString(R.string.ai_ask_say_mic), show = true)
            return
        }
        job?.cancel()
        question = Question(app, topic, handsFree)
        _state.value = State.Listening
        job = scope.launch {
            if (handsFree) {
                say(app, config, aiText.getString(R.string.ai_ask_say_listening), show = false)
                awaitVoice(app)
            }
            val r = QuestionRecorder(app)
            try {
                holdFocus(app)
                // Setting the recorder up takes the encoder a good part of a second on these units: not on the frame.
                withContext(Dispatchers.IO) { r.start() }
            } catch (e: CancellationException) {
                // The sheet closed meanwhile (cancel() set Idle): not a microphone failure.
                r.release()
                throw e
            } catch (e: Exception) {
                releaseFocus(app)
                val why = aiText.getString(R.string.ai_ask_mic_error)
                _state.value = State.Failed(why)
                if (handsFree) say(app, config, why, show = true)
                return@launch
            }
            recorder = r
            val detector = SilenceDetector()
            while (isActive) {
                delay(100)
                // The unit's clock is set by GPS mid-drive; the silence windows want a clock that only goes forward.
                when (detector.feed(r.amplitude(), SystemClock.elapsedRealtime())) {
                    SilenceDetector.Verdict.LISTEN -> Unit
                    SilenceDetector.Verdict.DONE -> return@launch finish(heard = true)
                    SilenceDetector.Verdict.NOTHING_HEARD -> return@launch finish(heard = false)
                }
            }
        }
    }

    /** Sends what was said so far (the Send button). Before the recording has begun there's nothing to send. */
    fun send() {
        if (_state.value != State.Listening || recorder == null) return
        job?.cancel()
        job = scope.launch { finish(heard = true) }
    }

    /** The microphone permission was refused. */
    fun micDenied(context: Context) {
        _state.value = State.Failed(AiSettings.load(context).language.resources(context).getString(R.string.ai_ask_mic_denied))
    }

    /** The sheet closed, or the answer was put away: stop listening or waiting, forget what was on screen. */
    fun cancel() {
        job?.cancel()
        // Stopping the recorder flushes its file: off the main thread, like starting it.
        recorder?.let { r -> scope.launch(Dispatchers.IO) { r.release() } }
        recorder = null
        question?.let { releaseFocus(it.context) }
        _state.value = State.Idle
    }

    /** Says the last answer again. */
    fun replay(context: Context) {
        val answered = _state.value as? State.Answered ?: return
        val language = AiSettings.load(context).language
        CarVoice.speak(answered.exchange.answer, language.locale)
    }

    private suspend fun finish(heard: Boolean) {
        val r = recorder
        recorder = null
        val audio = r?.let { withContext(Dispatchers.IO) { it.stop() } }
        val q = question ?: return
        releaseFocus(q.context)
        val config = AiSettings.load(q.context)
        if (!heard || audio == null || audio.size < MIN_AUDIO_BYTES) {
            _state.value = State.NothingHeard
            if (q.handsFree) say(q.context, config, config.language.resources(q.context).getString(R.string.ai_ask_say_nothing_heard), show = true)
            return
        }
        _state.value = State.Thinking
        job = scope.launch {
            val now = System.currentTimeMillis()
            val before = previous?.takeIf { it.key == q.topic.key && now - it.at in 0..PREVIOUS_MS }?.exchange
            val car = CarProfileStore.current
            // The readings as the question ends, not as it began.
            val prompt = when (val t = q.topic) {
                is Topic.Fault -> QuestionPrompt.build(car.promptDescription(), config.language, t.codes, t.diagnosis, t.code, before, PsaWarnings.forPrompt())
                Topic.Car -> CarQuestionPrompt.build(currentCarSnapshot(now), config.language, Units.current.value, before)
            }
            val reply = GeminiClient.generate(
                config.apiKey, prompt, QuestionPrompt.SCHEMA, budgetMs = BUDGET_MS, audio = audio, audioMime = "audio/aac"
            )
            val exchange = reply.getOrNull()?.let { QuestionPrompt.parse(it.text) }
            if (exchange != null) {
                previous = Previous(q.topic.key, exchange, System.currentTimeMillis())
                _state.value = State.Answered(exchange)
                say(q.context, config, exchange.answer, show = q.handsFree)
            } else {
                val why = reply.exceptionOrNull()?.let { AiMechanic.describe(q.context, it) }
                    ?: q.context.getString(R.string.ai_error_unreadable)
                val text = config.language.resources(q.context).getString(R.string.ai_ask_failed, why)
                _state.value = State.Failed(text)
                if (q.handsFree) say(q.context, config, text, show = true)
            }
        }
    }

    /**
     * Says [text] in the mechanic's language; [show] also puts it up for a
     * moment, for a unit with no voice when nothing else is on screen.
     */
    private fun say(context: Context, config: AiConfig, text: String, show: Boolean) {
        CarVoice.setContext(context)
        CarVoice.speak(text, config.language.locale)
        if (show) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Waits for the voice to say the cue, so the recording doesn't start with
     * it. The voice gives no word when it's done; its sound does show among
     * what's playing. Guidance from a navigation app counts too, hence the limits.
     */
    private suspend fun awaitVoice(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return delay(CUE_START_MS)
        fun talking() = runCatching {
            audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        }.getOrDefault(false)
        withTimeoutOrNull(CUE_START_MS) { while (!talking()) delay(50) }
        withTimeoutOrNull(CUE_MAX_MS) { while (talking()) delay(50) }
        delay(CUE_TAIL_MS)
    }

    private fun holdFocus(context: Context) {
        if (audioFocus != null) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        // Refused (a call, the unit's own radio): the question is still recorded.
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) audioFocus = request
    }

    private fun releaseFocus(context: Context) {
        val request = audioFocus ?: return
        audioFocus = null
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}
