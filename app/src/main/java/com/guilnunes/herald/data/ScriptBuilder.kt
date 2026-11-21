package com.guilnunes.herald.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A Notion block with its (already fetched) children. */
data class BlockNode(val json: JsonObject, val children: List<BlockNode> = emptyList()) {
    val type: String get() = json.str("type") ?: "unsupported"
    val id: String get() = json.str("id") ?: ""
    val body: JsonObject? get() = json[type] as? JsonObject
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

object NotionText {
    fun plain(richText: JsonArray?): String =
        richText?.joinToString("") { (it as? JsonObject)?.str("plain_text") ?: "" }?.trim() ?: ""

    fun pageTitle(page: JsonObject): String {
        val props = page["properties"] as? JsonObject ?: return "Untitled"
        for ((_, v) in props) {
            val o = v as? JsonObject ?: continue
            if (o.str("type") == "title") {
                return plain(o["title"] as? JsonArray).ifBlank { "Untitled" }
            }
        }
        return "Untitled"
    }

    fun pageIcon(page: JsonObject): String? {
        val icon = page["icon"] as? JsonObject ?: return null
        return if (icon.str("type") == "emoji") icon.str("emoji") else null
    }
}

/** Turns a fetched block tree into a flat [Script]. Pure, so it is unit tested on the JVM. */
class ScriptBuilder(private val pageId: String, private val title: String, private val lastEdited: String) {

    private val items = mutableListOf<Item>()
    private val sections = mutableListOf<Section>()
    private val headingStack = ArrayList<Pair<Int, String>>() // level to title
    private var currentSection = -1

    fun build(blocks: List<BlockNode>): Script {
        walk(blocks, 0)
        return Script(pageId, title, lastEdited, items.toList(), sections.toList())
    }

    private fun walk(blocks: List<BlockNode>, depth: Int) {
        var number = 0
        for (b in blocks) {
            if (b.type == "numbered_list_item") number++ else number = 0
            visit(b, depth, number)
        }
    }

    private fun rich(b: BlockNode, key: String = "rich_text") = NotionText.plain(b.body?.get(key) as? JsonArray)

    private fun text(kind: Kind, text: String, depth: Int, marker: String? = null, prefix: String? = null) {
        if (text.isBlank()) return
        items += TextItem(kind, text, depth, currentSection, marker, prefix)
    }

    private fun visit(b: BlockNode, depth: Int, number: Int) {
        when (b.type) {
            "heading_1", "heading_2", "heading_3" -> {
                val level = b.type.last().digitToInt()
                val t = rich(b)
                if (t.isNotBlank()) {
                    while (headingStack.isNotEmpty() && headingStack.last().first >= level) headingStack.removeAt(headingStack.lastIndex)
                    headingStack += level to t
                    sections += Section(t, level, items.size, headingStack.map { it.second })
                    currentSection = sections.lastIndex
                    items += TextItem(Kind.valueOf("H$level"), t, 0, currentSection)
                }
                walk(b.children, depth) // toggleable headings
            }
            "paragraph" -> { text(Kind.PARAGRAPH, rich(b), depth); walk(b.children, depth + 1) }
            "bulleted_list_item" -> { text(Kind.BULLET, rich(b), depth, "•"); walk(b.children, depth + 1) }
            "numbered_list_item" -> { text(Kind.NUMBERED, rich(b), depth, "$number."); walk(b.children, depth + 1) }
            "to_do" -> {
                val checked = b.body?.bool("checked") == true
                text(Kind.TODO, rich(b), depth, if (checked) "☑" else "☐", if (checked) "Done." else "To do.")
                walk(b.children, depth + 1)
            }
            "toggle" -> { text(Kind.TOGGLE, rich(b), depth, "▸"); walk(b.children, depth + 1) }
            "quote" -> { text(Kind.QUOTE, rich(b), depth, "❝"); walk(b.children, depth + 1) }
            "callout" -> {
                val emoji = (b.body?.get("icon") as? JsonObject)?.str("emoji")
                text(Kind.CALLOUT, rich(b), depth, emoji ?: "ℹ")
                walk(b.children, depth + 1)
            }
            "code" -> {
                val code = rich(b)
                if (code.isNotBlank()) items += CodeItem(b.body?.str("language") ?: "code", code, currentSection)
            }
            "equation" -> text(Kind.EQUATION, b.body?.str("expression") ?: "", depth, "∑", "Equation.")
            "table" -> {
                val rows = b.children.filter { it.type == "table_row" }.map { row ->
                    (row.body?.get("cells") as? JsonArray)?.map { NotionText.plain(it.jsonArray) } ?: emptyList()
                }
                if (rows.isNotEmpty()) {
                    items += TableItem(rows, b.body?.bool("has_column_header") == true, b.body?.bool("has_row_header") == true, currentSection)
                }
            }
            "child_page" -> items += ChildPageItem(TextUtil.normalizeId(b.id), b.body?.str("title")?.ifBlank { null } ?: "Untitled", currentSection)
            "child_database" -> {
                val t = b.body?.str("title")?.ifBlank { null } ?: "Untitled"
                items += NoteItem("Embedded database: $t. Databases are not read aloud.", currentSection)
            }
            "link_to_page" -> items += NoteItem("Link to another page.", currentSection)
            "image", "video", "file", "pdf", "audio" -> {
                val caption = rich(b, "caption")
                val label = when (b.type) { "image" -> "Image"; "video" -> "Video"; "audio" -> "Audio"; "pdf" -> "PDF"; else -> "File" }
                text(Kind.MEDIA, if (caption.isNotBlank()) "$label: $caption" else "$label.", depth, "▣")
            }
            "bookmark", "embed", "link_preview" -> {
                val url = b.body?.str("url") ?: ""
                val caption = rich(b, "caption")
                val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
                val t = when {
                    caption.isNotBlank() -> "Link: $caption"
                    host.isNotBlank() -> "Link to $host."
                    else -> ""
                }
                text(Kind.LINK, t, depth, "🔗")
            }
            "column_list", "column", "synced_block", "template" -> walk(b.children, depth)
            "divider", "table_of_contents", "breadcrumb", "table_row", "unsupported" -> Unit
            else -> walk(b.children, depth)
        }
    }
}

object TableSummary {
    private const val MAX_NAMED_ROWS = 10

    fun prompt(t: TableItem): String =
        "Table with ${t.rowCount - if (t.hasColumnHeader) 1 else 0} rows and ${t.columnCount} columns. Summarize or skip?"

    /** A spoken outline built only from the table itself: size, columns, and what each row is about. */
    fun summarize(t: TableItem): List<String> {
        val out = mutableListOf<String>()
        val header = if (t.hasColumnHeader) t.rows.first() else null
        val body = if (t.hasColumnHeader) t.rows.drop(1) else t.rows
        val cols = t.columnCount
        out += "This table has ${body.size} ${if (body.size == 1) "row" else "rows"} and $cols ${if (cols == 1) "column" else "columns"}."
        val names = header?.filter { it.isNotBlank() }.orEmpty()
        if (names.isNotEmpty()) out += "The columns are ${joinList(names)}."
        val firsts = body.mapNotNull { r -> r.firstOrNull { it.isNotBlank() } }
        if (firsts.isNotEmpty()) {
            val shown = firsts.take(MAX_NAMED_ROWS)
            val more = firsts.size - shown.size
            out += "The rows are: ${joinList(shown)}${if (more > 0) ", and $more more" else ""}."
        }
        val empty = body.count { r -> r.all { it.isBlank() } }
        if (empty > 0) out += "$empty ${if (empty == 1) "row is" else "rows are"} empty."
        out += "Tap any row on screen to hear it in full."
        return out
    }

    /** One row read as "Column: value" pairs. */
    fun rowText(t: TableItem, rowIndex: Int): String {
        val header = if (t.hasColumnHeader) t.rows.first() else null
        val row = t.rows.getOrNull(rowIndex) ?: return ""
        return row.mapIndexedNotNull { i, cell ->
            if (cell.isBlank()) null
            else {
                val name = header?.getOrNull(i)?.takeIf { it.isNotBlank() && header !== row }
                if (name != null) "$name: $cell" else cell
            }
        }.joinToString(". ").let { if (it.isBlank()) "Empty row." else "$it." }
    }

    private fun joinList(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        2 -> "${xs[0]} and ${xs[1]}"
        else -> xs.dropLast(1).joinToString(", ") + ", and " + xs.last()
    }
}
