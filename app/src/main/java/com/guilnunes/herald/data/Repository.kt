package com.guilnunes.herald.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

enum class Lang(val code: String, val label: String) { EN("en-US", "EN"), PT("pt-BR", "PT") }

@Serializable
data class SavedPage(val id: String, val title: String, val icon: String? = null, val lastOpened: Long, val percent: Int = 0)

class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("herald", Context.MODE_PRIVATE)

    var token: String?
        get() = p.getString("token", null)?.takeIf { it.isNotBlank() }
        set(v) { p.edit().putString("token", v?.trim()).apply() }

    var rate: Float
        get() = p.getFloat("rate", 1.0f)
        set(v) { p.edit().putFloat("rate", v).apply() }

    fun position(pageId: String): Int = p.getInt("pos:$pageId", 0)
    fun setPosition(pageId: String, item: Int) { p.edit().putInt("pos:$pageId", item).apply() }

    fun lang(pageId: String): Lang = runCatching { Lang.valueOf(p.getString("lang:$pageId", null) ?: "EN") }.getOrDefault(Lang.EN)
    fun setLang(pageId: String, l: Lang) { p.edit().putString("lang:$pageId", l.name).apply() }
}

/** Fetches pages from Notion, flattens them and keeps an offline copy of everything opened. */
class Repository(context: Context, val prefs: Prefs) {

    val api = NotionApi { prefs.token }
    private val dir = File(context.filesDir, "pages").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "saved.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _saved = MutableStateFlow(loadIndex())
    val saved: StateFlow<List<SavedPage>> = _saved

    private val memory = HashMap<String, Script>()

    private fun loadIndex(): List<SavedPage> =
        runCatching { json.decodeFromString<List<SavedPage>>(indexFile.readText()) }.getOrDefault(emptyList())

    @Synchronized
    private fun writeIndex(list: List<SavedPage>) {
        _saved.value = list.sortedByDescending { it.lastOpened }
        runCatching { indexFile.writeText(json.encodeToString(_saved.value)) }
    }

    fun markOpened(script: Script, icon: String? = null) {
        val others = _saved.value.filterNot { it.id == script.pageId }
        val old = _saved.value.firstOrNull { it.id == script.pageId }
        writeIndex(others + SavedPage(script.pageId, script.title, icon ?: old?.icon, System.currentTimeMillis(), old?.percent ?: 0))
    }

    fun updatePercent(pageId: String, percent: Int) {
        val list = _saved.value
        val i = list.indexOfFirst { it.id == pageId }
        if (i < 0 || list[i].percent == percent) return
        _saved.value = list.toMutableList().also { it[i] = it[i].copy(percent = percent) }
        runCatching { indexFile.writeText(json.encodeToString(_saved.value)) }
    }

    fun forget(pageId: String) {
        File(dir, "$pageId.json").delete()
        memory.remove(pageId)
        writeIndex(_saved.value.filterNot { it.id == pageId })
    }

    private fun cached(id: String): Script? =
        memory[id] ?: runCatching { json.decodeFromString<Script>(File(dir, "$id.json").readText()) }.getOrNull()?.also { memory[id] = it }

    private fun store(s: Script) {
        memory[s.pageId] = s
        runCatching { File(dir, "${s.pageId}.json").writeText(json.encodeToString(s)) }
    }

    /**
     * Returns the page script. Uses the offline copy when Notion says nothing changed, or when offline.
     * [onProgress] receives the number of blocks fetched so far.
     */
    suspend fun load(rawId: String, forceRefresh: Boolean = false, onProgress: (Int) -> Unit = {}): Script = withContext(Dispatchers.IO) {
        val id = TextUtil.normalizeId(rawId)
        val local = cached(id)
        val page: JsonObject = try {
            api.page(id)
        } catch (e: NotionException) {
            if (local != null && e.status == 0) return@withContext local
            throw e
        } catch (e: java.io.IOException) {
            if (local != null) return@withContext local
            throw NotionException("You're offline and this page isn't saved yet.")
        }
        val edited = page.str("last_edited_time") ?: ""
        if (!forceRefresh && local != null && local.lastEdited == edited) return@withContext local
        var count = 0
        val tree = fetchTree(id) { count += it; onProgress(count) }
        val script = ScriptBuilder(id, NotionText.pageTitle(page), edited).build(tree)
        store(script)
        script
    }

    fun cachedOnly(id: String): Script? = cached(TextUtil.normalizeId(id))

    private suspend fun fetchTree(blockId: String, onBlocks: (Int) -> Unit): List<BlockNode> {
        val blocks = api.children(blockId)
        onBlocks(blocks.size)
        return blocks.map { b ->
            val node = BlockNode(b)
            val type = node.type
            val childId = when {
                type == "child_page" || type == "child_database" -> null
                type == "synced_block" -> ((node.body?.get("synced_from") as? JsonObject)?.str("block_id")) ?: node.id.takeIf { b.bool("has_children") }
                b.bool("has_children") -> node.id
                else -> null
            }
            if (childId != null) node.copy(children = fetchTree(childId, onBlocks)) else node
        }
    }
}
