package com.guilnunes.herald.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.guilnunes.herald.data.ChildPageItem
import com.guilnunes.herald.data.CodeItem
import com.guilnunes.herald.data.Item
import com.guilnunes.herald.data.Lang
import com.guilnunes.herald.data.NoteItem
import com.guilnunes.herald.data.Repository
import com.guilnunes.herald.data.Script
import com.guilnunes.herald.data.TableItem
import com.guilnunes.herald.data.TableSummary
import com.guilnunes.herald.data.TextItem
import com.guilnunes.herald.data.TextUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class Status { IDLE, LOADING, PLAYING, PAUSED, AWAITING, FINISHED }

enum class GateKind { TABLE, CHILD, CODE }

data class Gate(
    val item: Int,
    val kind: GateKind,
    val prompt: String,
    val yesLabel: String,
    val noLabel: String = "Skip",
    val minutes: Int? = null,
)

/** One level of reading. The bottom frame is the page you opened; child pages push frames on top. */
class Frame(
    val script: Script,
    var item: Int,
    var sentence: Int = 0,
    /** Sentences spoken in place of the current gate item (table summary, code lines). */
    var expansion: List<String>? = null,
    /** In the parent frame: index of the child-page item this frame was opened from. */
    val openedFromItem: Int = -1,
)

data class Crumb(val pageId: String, val title: String)

data class PlayerState(
    val status: Status = Status.IDLE,
    val script: Script? = null,
    val crumbs: List<Crumb> = emptyList(),
    val item: Int = 0,
    /** Range in the current item's display text being spoken. */
    val sentenceRange: IntRange? = null,
    val wordRange: IntRange? = null,
    /** Text being spoken that is not part of the page text (prompts, summaries, notices). */
    val aside: String? = null,
    val asideWord: IntRange? = null,
    val expansion: List<String>? = null,
    val expansionIndex: Int = 0,
    val gate: Gate? = null,
    val rate: Float = 1f,
    val lang: Lang = Lang.EN,
    val percent: Int = 0,
    val loadingBlocks: Int = 0,
    val message: String? = null,
) {
    val sectionTitle: String?
        get() {
            val s = script ?: return null
            val idx = s.items.getOrNull(item)?.section ?: return null
            return s.sections.getOrNull(idx)?.title
        }
    val sectionIndex: Int get() = script?.items?.getOrNull(item)?.section ?: -1
}

/**
 * The reading engine: turns a [Script] into speech with Android's free built-in TextToSpeech,
 * one sentence at a time so it can pause, skip and highlight precisely.
 */
class Engine(private val context: Context, private val repo: Repository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState(rate = repo.prefs.rate))
    val state: StateFlow<PlayerState> = _state

    /** Called when playback starts so the foreground service can be (re)started. */
    var onPlaybackStarted: (() -> Unit)? = null

    private val stack = ArrayList<Frame>()
    private var generation = 0
    private var afterAside: (() -> Unit)? = null
    private var asideId: String? = null
    private var pendingChild: Script? = null
    private var loadJob: Job? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null
    private var spokenPrefixLen = 0
    private var currentSentenceRange: IntRange? = null

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            scope.launch {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
        }
        .build()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        scope.launch {
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                update { copy(message = "Text-to-speech isn't available. Install Speech Services by Google from the Play Store.") }
            } else {
                tts.setAudioAttributes(attrs)
                applyVoice()
                pendingSpeak?.invoke()
                pendingSpeak = null
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { scope.launch { onUtteranceDone(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { scope.launch { onUtteranceDone(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { scope.launch { onUtteranceDone(utteranceId) } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                scope.launch { onRange(utteranceId, start, end) }
            }
        })
    }

    private inline fun update(f: PlayerState.() -> PlayerState) { _state.value = _state.value.f() }

    private val top: Frame? get() = stack.lastOrNull()

    // ---------------------------------------------------------------- opening

    fun open(pageId: String, autoplay: Boolean = true) {
        val id = TextUtil.normalizeId(pageId)
        if (stack.firstOrNull()?.script?.pageId == id) {
            if (autoplay && _state.value.status != Status.PLAYING) play()
            return
        }
        stopSpeech()
        stack.clear()
        loadJob?.cancel()
        update { PlayerState(status = Status.LOADING, rate = rate, crumbs = emptyList()) }
        loadJob = scope.launch {
            try {
                val script = repo.load(id) { n -> update { copy(loadingBlocks = n) } }
                repo.markOpened(script, repo.saved.value.firstOrNull { it.id == id }?.icon)
                val saved = repo.prefs.position(id).coerceIn(0, maxOf(0, script.items.size - 1))
                stack += Frame(script, saved)
                update { copy(status = Status.PAUSED, lang = repo.prefs.lang(id), loadingBlocks = 0) }
                applyVoice()
                publish()
                if (autoplay) play()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                update { copy(status = Status.IDLE, message = e.message ?: "Couldn't load the page.") }
            }
        }
    }

    fun refresh() {
        val id = stack.firstOrNull()?.script?.pageId ?: return
        val wasItem = stack.first().item
        stopSpeech()
        stack.clear()
        update { copy(status = Status.LOADING) }
        loadJob = scope.launch {
            try {
                val script = repo.load(id, forceRefresh = true) { n -> update { copy(loadingBlocks = n) } }
                stack += Frame(script, wasItem.coerceIn(0, maxOf(0, script.items.size - 1)))
                update { copy(status = Status.PAUSED, loadingBlocks = 0, message = "Page refreshed.") }
                publish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                update { copy(status = Status.IDLE, message = e.message) }
            }
        }
    }

    fun close() {
        stopSpeech()
        saveProgress()
        stack.clear()
        update { PlayerState(rate = rate) }
        abandonFocus()
    }

    fun clearMessage() = update { copy(message = null) }

    // ---------------------------------------------------------------- transport

    fun play() {
        val f = top ?: return
        val st = _state.value
        when (st.status) {
            Status.AWAITING -> { decide(true); return }
            Status.LOADING -> return
            Status.FINISHED -> { stack.subList(1, stack.size).clear(); stack[0].item = 0; stack[0].sentence = 0; stack[0].expansion = null }
            else -> Unit
        }
        if (f.item >= f.script.items.size) { f.item = 0; f.sentence = 0 }
        requestFocus()
        update { copy(status = Status.PLAYING, message = null) }
        onPlaybackStarted?.invoke()
        speakCurrent()
    }

    fun pause() {
        val s = _state.value.status
        if (s != Status.PLAYING) return
        stopSpeech()
        // An interrupted aside is dropped. Resuming re-enters the current item, which re-asks any gate.
        afterAside = null
        asideId = null
        update { copy(status = Status.PAUSED, aside = null, asideWord = null, wordRange = null) }
        saveProgress()
    }

    fun togglePlay() { if (_state.value.status == Status.PLAYING) pause() else play() }

    fun nextParagraph() { moveTo((top?.item ?: return) + 1) }

    fun previousParagraph() {
        val f = top ?: return
        if (f.sentence > 0 || f.expansion != null) moveTo(f.item) else moveTo(f.item - 1)
    }

    fun nextSection() {
        val f = top ?: return
        val next = f.script.sections.firstOrNull { it.itemIndex > f.item }
        if (next != null) moveTo(next.itemIndex) else moveTo(f.script.items.size)
    }

    fun previousSection() {
        val f = top ?: return
        val currentStart = f.script.sections.lastOrNull { it.itemIndex <= f.item }?.itemIndex ?: 0
        val target = if (f.item > currentStart || f.sentence > 0) currentStart
        else f.script.sections.lastOrNull { it.itemIndex < currentStart }?.itemIndex ?: 0
        moveTo(target)
    }

    /** Jump to an item of the page currently being read. Keeps playing if it was playing. */
    fun moveTo(index: Int) {
        val f = top ?: return
        val playing = _state.value.status == Status.PLAYING || _state.value.status == Status.AWAITING
        stopSpeech()
        afterAside = null
        f.item = index.coerceIn(0, f.script.items.size)
        f.sentence = 0
        f.expansion = null
        update { copy(gate = null, aside = null, asideWord = null) }
        if (f.item >= f.script.items.size) { endOfFrame(); return }
        publish()
        saveProgress()
        if (playing) { update { copy(status = Status.PLAYING) }; speakCurrent() }
        else update { copy(status = Status.PAUSED) }
    }

    /** Jump and start playing (used after choosing a section). */
    fun jumpAndPlay(index: Int) {
        moveTo(index)
        if (_state.value.status != Status.PLAYING) play()
    }

    fun setRate(r: Float) {
        val v = r.coerceIn(0.5f, 3f)
        repo.prefs.rate = v
        tts.setSpeechRate(v)
        update { copy(rate = v, gate = gate?.let { g -> if (g.kind == GateKind.CHILD && pendingChild != null) g.copy(minutes = pendingChild!!.minutes(v)) else g }) }
        if (_state.value.status == Status.PLAYING) restartCurrentSentence()
    }

    fun setLang(l: Lang) {
        val base = stack.firstOrNull() ?: return
        repo.prefs.setLang(base.script.pageId, l)
        update { copy(lang = l) }
        applyVoice()
        if (_state.value.status == Status.PLAYING) restartCurrentSentence()
    }

    // ---------------------------------------------------------------- gates

    /** Answer the pending gate. [yes] = Summarize / Read, otherwise Skip. */
    fun decide(yes: Boolean) {
        val f = top ?: return
        val gate = _state.value.gate ?: return
        stopSpeech()
        afterAside = null
        update { copy(gate = null, aside = null, asideWord = null, status = Status.PLAYING) }
        requestFocus()
        onPlaybackStarted?.invoke()
        if (!yes) { advanceItem(); return }
        when (val item = f.script.items[gate.item]) {
            is TableItem -> { f.expansion = TableSummary.summarize(item); f.sentence = 0; speakCurrent() }
            is CodeItem -> {
                f.expansion = listOf("Code, ${item.language}.") + item.code.lines().map { it.trim() }.filter { it.isNotEmpty() }
                f.sentence = 0
                speakCurrent()
            }
            is ChildPageItem -> {
                val child = pendingChild
                pendingChild = null
                if (child == null) { advanceItem(); return }
                stack += Frame(child, 0, openedFromItem = gate.item)
                publish()
                speakAside("Child page: ${child.title}.") { speakCurrent() }
            }
            else -> advanceItem()
        }
    }

    /** Speak one table row on request, then return to where reading was. */
    fun speakTableRow(itemIndex: Int, row: Int) {
        val f = top ?: return
        val table = f.script.items.getOrNull(itemIndex) as? TableItem ?: return
        val wasPlaying = _state.value.status == Status.PLAYING
        val gate = _state.value.gate
        stopSpeech()
        val text = TableSummary.rowText(table, row)
        update { copy(status = Status.PLAYING) }
        requestFocus()
        speakAside(text) {
            when {
                gate != null -> update { copy(status = Status.AWAITING, gate = gate) }
                wasPlaying -> speakCurrent()
                else -> update { copy(status = Status.PAUSED) }
            }
        }
    }

    private fun enterGate(f: Frame, index: Int, item: Item) {
        when (item) {
            is TableItem -> showGate(Gate(index, GateKind.TABLE, TableSummary.prompt(item), "Summarize"))
            is CodeItem -> {
                val lines = item.code.lines().count { it.isNotBlank() }
                showGate(Gate(index, GateKind.CODE, "Code block, $lines ${if (lines == 1) "line" else "lines"}. Read or skip?", "Read"))
            }
            is ChildPageItem -> {
                update { copy(aside = "Checking the child page ${item.title}…") }
                val gen = generation
                scope.launch {
                    val child = try { repo.load(item.pageId) } catch (e: Exception) { null }
                    if (gen != generation || top !== f || f.item != index) return@launch
                    if (child == null) {
                        speakAside("The child page ${item.title} can't be loaded right now, so I'm skipping it.") { advanceItem() }
                        return@launch
                    }
                    pendingChild = child
                    val m = child.minutes(_state.value.rate)
                    showGate(Gate(index, GateKind.CHILD, "Child page: ${child.title}. About $m ${if (m == 1) "minute" else "minutes"}. Read or skip?", "Read", minutes = m))
                }
            }
            else -> advanceItem()
        }
    }

    private fun showGate(g: Gate) {
        update { copy(gate = g) }
        speakAside(g.prompt) { update { copy(status = Status.AWAITING) } }
    }

    // ---------------------------------------------------------------- core loop

    private fun speakCurrent() {
        if (!ttsReady) { pendingSpeak = { speakCurrent() }; return }
        val f = top ?: return
        if (_state.value.status != Status.PLAYING) return
        if (f.item >= f.script.items.size) { endOfFrame(); return }
        val item = f.script.items[f.item]
        publish()

        f.expansion?.let { exp ->
            if (f.sentence >= exp.size) { f.expansion = null; advanceItem(); return }
            update { copy(expansion = exp, expansionIndex = f.sentence, sentenceRange = null, wordRange = null) }
            speak(exp[f.sentence], "x")
            return
        }

        when (item) {
            is TextItem, is NoteItem -> {
                val text = if (item is TextItem) item.text else (item as NoteItem).text
                val ranges = TextUtil.sentences(text)
                if (f.sentence >= ranges.size) { advanceItem(); return }
                val r = ranges[f.sentence]
                val prefix = if (item is TextItem && f.sentence == 0) item.spokenPrefix?.let { "$it " } ?: "" else ""
                currentSentenceRange = r
                spokenPrefixLen = prefix.length
                update { copy(sentenceRange = r, wordRange = null, expansion = null, aside = null) }
                speak(prefix + text.substring(r.first, r.last + 1), "s")
            }
            else -> enterGate(f, f.item, item)
        }
    }

    private fun advanceItem() {
        val f = top ?: return
        f.item++
        f.sentence = 0
        f.expansion = null
        saveProgress()
        if (_state.value.status == Status.PLAYING) speakCurrent() else publish()
    }

    private fun endOfFrame() {
        if (stack.size > 1) {
            val child = stack.removeAt(stack.lastIndex)
            val parent = top!!
            val gateItem = child.openedFromItem
            parent.item = gateItem + 1
            parent.sentence = 0
            parent.expansion = null
            val sec = parent.script.items.getOrNull(gateItem)?.section ?: -1
            val section = parent.script.sections.getOrNull(sec)
            val where = if (section != null) " on section ${section.title}" else ""
            val notice = "I have finished reading the child page ${child.script.title} and I'm going back to ${parent.script.title}$where."
            publish()
            saveProgress()
            if (_state.value.status == Status.PLAYING) speakAside(notice) { speakCurrent() }
            return
        }
        val f = top ?: return
        f.item = f.script.items.size
        repo.prefs.setPosition(f.script.pageId, 0)
        repo.updatePercent(f.script.pageId, 100)
        val wasPlaying = _state.value.status == Status.PLAYING
        update { copy(status = Status.FINISHED, percent = 100, sentenceRange = null, wordRange = null) }
        if (wasPlaying) speakAside("End of ${f.script.title}.") {}
    }

    private fun onUtteranceDone(id: String?) {
        if (id == null) return
        val gen = id.substringBefore(':').toIntOrNull() ?: return
        if (gen != generation) return
        if (id == asideId) {
            asideId = null
            val next = afterAside
            afterAside = null
            update { copy(aside = null, asideWord = null) }
            next?.invoke()
            return
        }
        if (_state.value.status != Status.PLAYING) return
        val f = top ?: return
        f.sentence++
        speakCurrent()
    }

    private fun onRange(id: String?, start: Int, end: Int) {
        if (id == null) return
        val gen = id.substringBefore(':').toIntOrNull() ?: return
        if (gen != generation) return
        if (id == asideId) { update { copy(asideWord = start until end) }; return }
        if (id.endsWith(":x")) return
        val r = currentSentenceRange ?: return
        val s = start - spokenPrefixLen
        if (s < 0) return
        update { copy(wordRange = (r.first + s) until (r.first + (end - spokenPrefixLen))) }
    }

    private fun speak(text: String, tag: String) {
        generation++
        val id = "$generation:$tag"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    private fun speakAside(text: String, then: () -> Unit) {
        if (!ttsReady) { pendingSpeak = { speakAside(text, then) }; return }
        generation++
        val id = "$generation:a"
        asideId = id
        afterAside = then
        update { copy(aside = text, asideWord = null) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    private fun restartCurrentSentence() {
        if (asideId != null) return
        stopSpeech()
        speakCurrent()
    }

    private fun stopSpeech() {
        generation++
        asideId = null
        if (ttsReady) tts.stop()
    }

    // ---------------------------------------------------------------- state & persistence

    private fun publish() {
        val f = top ?: return
        val base = stack.first()
        update {
            copy(
                script = f.script,
                crumbs = stack.map { Crumb(it.script.pageId, it.script.title) },
                item = f.item.coerceAtMost(maxOf(0, f.script.items.size - 1)),
                percent = percent(f),
                expansion = f.expansion,
                expansionIndex = f.sentence,
                sentenceRange = if (f.expansion != null) null else sentenceRange,
            )
        }
        repo.updatePercent(base.script.pageId, percent(base))
    }

    private fun weight(i: Item): Int = when (i) {
        is TextItem -> i.text.length + 20
        is NoteItem -> i.text.length
        is TableItem -> 120
        is CodeItem -> 80
        is ChildPageItem -> 60
    }

    private fun percent(f: Frame): Int {
        val items = f.script.items
        if (items.isEmpty()) return 100
        val total = items.sumOf { weight(it) }.toFloat()
        var done = items.take(f.item.coerceAtMost(items.size)).sumOf { weight(it) }.toFloat()
        val cur = items.getOrNull(f.item)
        if (cur is TextItem && f.sentence > 0) {
            val ranges = TextUtil.sentences(cur.text)
            if (ranges.isNotEmpty()) done += weight(cur) * f.sentence.toFloat() / ranges.size
        }
        return ((done / total) * 100).toInt().coerceIn(0, 100)
    }

    private fun saveProgress() {
        stack.forEach { repo.prefs.setPosition(it.script.pageId, it.item) }
        stack.firstOrNull()?.let { repo.updatePercent(it.script.pageId, percent(it)) }
    }

    private fun applyVoice() {
        if (!ttsReady) return
        val locale = Locale.forLanguageTag(_state.value.lang.code)
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            update { copy(message = "No ${_state.value.lang.label} voice installed. Add it in Android Settings › Text-to-speech.") }
        }
        // Prefer the best offline voice for the language.
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        voices.filter { it.locale.language == locale.language && (it.locale.country == locale.country || it.locale.country.isEmpty()) }
            .filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxByOrNull { it.quality * 10 - it.latency }
            ?.let { tts.voice = it }
        tts.setSpeechRate(_state.value.rate)
    }

    private fun requestFocus() { audio.requestAudioFocus(focusRequest) }
    private fun abandonFocus() { audio.abandonAudioFocusRequest(focusRequest) }
}
