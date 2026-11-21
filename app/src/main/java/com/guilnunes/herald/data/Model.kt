package com.guilnunes.herald.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Notion page flattened into an ordered reading script. */
@Serializable
data class Script(
    val pageId: String,
    val title: String,
    val lastEdited: String,
    val items: List<Item>,
    val sections: List<Section>,
) {
    /** Rough word count of everything that is read straight through (gates excluded). */
    fun wordCount(): Int =
        items.sumOf { item ->
            when (item) {
                is TextItem -> words(item.text)
                is NoteItem -> words(item.text)
                else -> 0
            }
        }

    /** Estimated listening time in minutes at the given speech rate. */
    fun minutes(rate: Float): Int =
        maxOf(1, Math.round(wordCount() / (WORDS_PER_MINUTE * rate)))

    companion object {
        const val WORDS_PER_MINUTE = 165f
        fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}

/** A heading. [itemIndex] is where the heading item sits in [Script.items]. */
@Serializable
data class Section(
    val title: String,
    val level: Int,
    val itemIndex: Int,
    val path: List<String>,
)

@Serializable
sealed interface Item {
    /** Index into [Script.sections] of the section this item belongs to, -1 before the first heading. */
    val section: Int
}

@Serializable
enum class Kind { H1, H2, H3, PARAGRAPH, BULLET, NUMBERED, TODO, TOGGLE, QUOTE, CALLOUT, MEDIA, LINK, EQUATION }

@Serializable
@SerialName("text")
data class TextItem(
    val kind: Kind,
    val text: String,
    val depth: Int,
    override val section: Int,
    /** Display marker such as "3." or "☐". */
    val marker: String? = null,
    /** Spoken before the text (for example "Done." for checked to-dos). Not displayed. */
    val spokenPrefix: String? = null,
) : Item

@Serializable
@SerialName("table")
data class TableItem(
    val rows: List<List<String>>,
    val hasColumnHeader: Boolean,
    val hasRowHeader: Boolean,
    override val section: Int,
) : Item {
    val rowCount get() = rows.size
    val columnCount get() = rows.maxOfOrNull { it.size } ?: 0
}

@Serializable
@SerialName("child")
data class ChildPageItem(
    val pageId: String,
    val title: String,
    override val section: Int,
) : Item

@Serializable
@SerialName("code")
data class CodeItem(
    val language: String,
    val code: String,
    override val section: Int,
) : Item

/** Something announced but not expanded, like an embedded database. */
@Serializable
@SerialName("note")
data class NoteItem(
    val text: String,
    override val section: Int,
) : Item

@Serializable
data class PageRef(
    val id: String,
    val title: String,
    val lastEdited: String = "",
    val icon: String? = null,
)
