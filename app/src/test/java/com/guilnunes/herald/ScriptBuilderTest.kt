package com.guilnunes.herald

import com.guilnunes.herald.data.BlockNode
import com.guilnunes.herald.data.ChildPageItem
import com.guilnunes.herald.data.CodeItem
import com.guilnunes.herald.data.Kind
import com.guilnunes.herald.data.NoteItem
import com.guilnunes.herald.data.Script
import com.guilnunes.herald.data.ScriptBuilder
import com.guilnunes.herald.data.TableItem
import com.guilnunes.herald.data.TableSummary
import com.guilnunes.herald.data.TextItem
import com.guilnunes.herald.data.TextUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptBuilderTest {

    private fun rt(t: String) = """[{"type":"text","plain_text":${kotlinx.serialization.json.JsonPrimitive(t)}}]"""
    private fun block(type: String, body: String, id: String = "b") =
        Json.parseToJsonElement("""{"object":"block","id":"$id","type":"$type","has_children":false,"$type":$body}""").jsonObject
    private fun text(type: String, t: String) = BlockNode(block(type, """{"rich_text":${rt(t)}}"""))

    @Test
    fun buildsSectionsWithPaths() {
        val s = ScriptBuilder("p", "Page", "t").build(
            listOf(
                text("paragraph", "Intro."),
                text("heading_1", "Design"),
                text("paragraph", "About design."),
                text("heading_2", "Overview"),
                text("bulleted_list_item", "Point"),
                text("heading_1", "Build"),
            ),
        )
        assertEquals(listOf("Design", "Overview", "Build"), s.sections.map { it.title })
        assertEquals(listOf("Design", "Overview"), s.sections[1].path)
        assertEquals(listOf("Build"), s.sections[2].path)
        assertEquals(-1, s.items[0].section)
        assertEquals(1, (s.items[4] as TextItem).section)
        assertEquals(Kind.BULLET, (s.items[4] as TextItem).kind)
    }

    @Test
    fun numbersListsAndNestsChildren() {
        val nested = BlockNode(block("bulleted_list_item", """{"rich_text":${rt("child")}}"""))
        val s = ScriptBuilder("p", "Page", "t").build(
            listOf(
                text("numbered_list_item", "one"),
                BlockNode(block("numbered_list_item", """{"rich_text":${rt("two")}}"""), listOf(nested)),
                text("paragraph", "break"),
                text("numbered_list_item", "again"),
            ),
        )
        val items = s.items.map { it as TextItem }
        assertEquals(listOf("1.", "2.", "•", null, "1."), items.map { it.marker })
        assertEquals(1, items[2].depth)
    }

    @Test
    fun gatesForTablesChildPagesCodeAndDatabases() {
        val row = { a: String, b: String -> BlockNode(block("table_row", """{"cells":[${rt(a)},${rt(b)}]}""")) }
        val table = BlockNode(block("table", """{"table_width":2,"has_column_header":true,"has_row_header":false}"""),
            listOf(row("Name", "Status"), row("Alpha", "Done"), row("Beta", "")))
        val s = ScriptBuilder("p", "Page", "t").build(
            listOf(
                table,
                BlockNode(block("child_page", """{"title":"Notes"}""", id = "0123456789abcdef0123456789abcdef")),
                BlockNode(block("code", """{"language":"kotlin","rich_text":${rt("val x = 1")}}""")),
                BlockNode(block("child_database", """{"title":"Tasks"}""")),
                BlockNode(block("divider", "{}")),
            ),
        )
        assertTrue(s.items[0] is TableItem)
        val child = s.items[1] as ChildPageItem
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", child.pageId)
        assertTrue(s.items[2] is CodeItem)
        assertTrue(s.items[3] is NoteItem)
        assertEquals(4, s.items.size)

        val t = s.items[0] as TableItem
        assertEquals("Table with 2 rows and 2 columns. Summarize or skip?", TableSummary.prompt(t))
        val summary = TableSummary.summarize(t)
        assertTrue(summary.any { it == "The columns are Name and Status." })
        assertTrue(summary.any { it == "The rows are: Alpha and Beta." })
        assertEquals("Name: Alpha. Status: Done.", TableSummary.rowText(t, 1))
        assertEquals("Name: Beta.", TableSummary.rowText(t, 2))
    }

    @Test
    fun todoGetsSpokenPrefix() {
        val s = ScriptBuilder("p", "Page", "t").build(listOf(BlockNode(block("to_do", """{"rich_text":${rt("ship it")},"checked":true}"""))))
        val item = s.items[0] as TextItem
        assertEquals("Done.", item.spokenPrefix)
        assertEquals("☑", item.marker)
    }

    @Test
    fun scriptSurvivesJsonRoundTrip() {
        val s = ScriptBuilder("p", "Page", "t").build(listOf(text("heading_1", "A"), text("paragraph", "b c d")))
        val json = Json.encodeToString(Script.serializer(), s)
        assertEquals(s, Json.decodeFromString(Script.serializer(), json))
        assertEquals(4, s.wordCount())
    }

    @Test
    fun extractsIdsFromShareLinks() {
        assertEquals("1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
            TextUtil.extractPageId("https://www.notion.so/My-Page-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d?pvs=4"))
        assertEquals("1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
            TextUtil.extractPageId("Check this https://guil.notion.site/1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"))
        assertEquals("1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
            TextUtil.extractPageId("https://www.notion.so/ws/Title-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d#abcdefabcdefabcdefabcdefabcdefab"))
        assertNull(TextUtil.extractPageId("hello world"))
    }

    @Test
    fun splitsSentencesKeepingAbbreviationsAndNumbers() {
        val t = "Hello there. This costs 3.50 dollars, e.g. a coffee! Is it? Yes"
        val parts = TextUtil.sentences(t).map { t.substring(it.first, it.last + 1) }
        assertEquals(listOf("Hello there.", "This costs 3.50 dollars, e.g. a coffee!", "Is it?", "Yes"), parts)
    }

    @Test
    fun splitsVeryLongSentences() {
        val t = (1..200).joinToString(" ") { "word$it" }
        val parts = TextUtil.sentences(t, maxLen = 100)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.last - it.first < 100 })
        assertEquals(t, parts.joinToString(" ") { t.substring(it.first, it.last + 1) })
    }
}
