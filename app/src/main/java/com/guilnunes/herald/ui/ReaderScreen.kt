package com.guilnunes.herald.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guilnunes.herald.data.ChildPageItem
import com.guilnunes.herald.data.CodeItem
import com.guilnunes.herald.data.Kind
import com.guilnunes.herald.data.Lang
import com.guilnunes.herald.data.NoteItem
import com.guilnunes.herald.data.TableItem
import com.guilnunes.herald.data.TextItem
import com.guilnunes.herald.player.Engine
import com.guilnunes.herald.player.GateKind
import com.guilnunes.herald.player.PlayerState
import com.guilnunes.herald.player.Status
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel) {
    val engine = vm.engine
    val st by engine.state.collectAsStateWithLifecycle()
    var showSections by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            ReaderHeader(st, onBack = { vm.go(Screen.HOME) }, onSection = { engine.pause(); showSections = true })

            st.message?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { engine.clearMessage() }) {
                    Text(msg, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Box(Modifier.weight(1f)) {
                val script = st.script
                if (st.status == Status.LOADING || script == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (st.status == Status.LOADING) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(if (st.loadingBlocks > 0) "Loading page… ${st.loadingBlocks} blocks" else "Loading page…", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Text("Nothing open.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    key(script.pageId) { PageBody(st, engine) }
                }
            }

            st.gate?.let { g ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(g.prompt, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { engine.decide(true) }, modifier = Modifier.weight(1f).height(56.dp)) { Text(g.yesLabel) }
                            OutlinedButton(onClick = { engine.decide(false) }, modifier = Modifier.weight(1f).height(56.dp)) { Text(g.noLabel) }
                        }
                    }
                }
            }
            if (st.gate == null && st.aside != null) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(highlight(st.aside!!, null, st.asideWord), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Controls(st, engine)
        }
    }

    if (showSections) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSections = false }, sheetState = sheet) {
            val sections = st.script?.sections.orEmpty()
            Text("Sections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (sections.isEmpty()) Text("This page has no headings.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
            LazyColumn {
                item {
                    SectionRow("Start of page", 1, st.sectionIndex == -1) { engine.jumpAndPlay(0); showSections = false }
                }
                itemsIndexed(sections) { i, s ->
                    SectionRow(s.title, s.level, i == st.sectionIndex) { engine.jumpAndPlay(s.itemIndex); showSections = false }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun SectionRow(title: String, level: Int, current: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = if (level == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = if (current) FontWeight.Bold else null,
        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else Color.Transparent)
            .padding(start = (20 + (level - 1) * 20).dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
    )
}

@Composable
private fun ReaderHeader(st: PlayerState, onBack: () -> Unit, onSection: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    if (st.crumbs.size > 1) {
                        Text(st.crumbs.dropLast(1).joinToString(" › ") { it.title } + " ›", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(st.script?.title ?: "Herald", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.weight(1f).clickable(onClick = onSection),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, null)
                        Spacer(Modifier.width(8.dp))
                        Text(st.sectionTitle ?: "Start of page", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("${st.percent}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp))
        }
    }
}

@Composable
private fun PageBody(st: PlayerState, engine: Engine) {
    val script = st.script ?: return
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (st.item - 1).coerceAtLeast(0))
    LaunchedEffect(st.item) {
        val target = (st.item - 1).coerceAtLeast(0)
        val visible = list.layoutInfo.visibleItemsInfo
        val onScreen = visible.any { it.index == st.item } && visible.lastOrNull()?.index != st.item
        if (!onScreen || st.status == Status.PLAYING) list.animateScrollToItem(target)
    }
    val hl = MaterialTheme.colorScheme.primaryContainer
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        itemsIndexed(script.items, key = { i, _ -> i }) { i, item ->
            val current = i == st.item
            val bg = if (current) hl.copy(alpha = 0.25f) else Color.Transparent
            Box(Modifier.fillMaxWidth().background(bg)) {
                when (item) {
                    is TextItem -> TextBlock(item, if (current) st else null) { engine.moveTo(i) }
                    is NoteItem -> Text(item.text, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { engine.moveTo(i) }.padding(horizontal = 20.dp, vertical = 8.dp))
                    is TableItem -> TableBlock(item, current, st, onOpen = { engine.moveTo(i) }, onRow = { r -> engine.speakTableRow(i, r) })
                    is ChildPageItem -> CardBlock("📄  Child page", item.title,
                        subtitle = if (current && st.gate?.kind == GateKind.CHILD) st.gate?.minutes?.let { "About $it min" } else null) { engine.moveTo(i) }
                    is CodeItem -> CodeBlock(item, current, st) { engine.moveTo(i) }
                }
            }
        }
    }
}

@Composable
private fun TextBlock(item: TextItem, st: PlayerState?, onClick: () -> Unit) {
    val style: TextStyle = when (item.kind) {
        Kind.H1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp)
        Kind.H2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp)
        Kind.H3 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        Kind.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
        else -> MaterialTheme.typography.bodyLarge
    }
    val top = when (item.kind) { Kind.H1 -> 24.dp; Kind.H2 -> 18.dp; Kind.H3 -> 12.dp; else -> 6.dp }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (20 + item.depth * 22).dp, end = 20.dp, top = top, bottom = 6.dp)) {
        item.marker?.let {
            Text(it, style = style, modifier = Modifier.widthIn(min = 28.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(highlight(item.text, st?.sentenceRange, st?.wordRange), style = style)
    }
}

@Composable
private fun highlight(text: String, sentence: IntRange?, word: IntRange?): AnnotatedString {
    val sBg = MaterialTheme.colorScheme.primaryContainer
    val sFg = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        append(text)
        fun ok(r: IntRange) = r.first >= 0 && r.last < text.length && r.first <= r.last
        if (sentence != null && ok(sentence)) addStyle(SpanStyle(background = sBg, color = sFg), sentence.first, sentence.last + 1)
        if (word != null && ok(word)) addStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), word.first, word.last + 1)
    }
}

@Composable
private fun CardBlock(label: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TableBlock(t: TableItem, current: Boolean, st: PlayerState, onOpen: () -> Unit, onRow: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            val body = t.rowCount - if (t.hasColumnHeader) 1 else 0
            Text("▦  Table · $body rows × ${t.columnCount} columns", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onOpen).padding(horizontal = 16.dp))
            if (current && st.expansion != null) {
                Column(Modifier.padding(16.dp)) {
                    st.expansion.forEachIndexed { i, line ->
                        Text(line, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.background(if (i == st.expansionIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent))
                    }
                }
            }
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
                t.rows.forEachIndexed { r, row ->
                    val header = t.hasColumnHeader && r == 0
                    Row(Modifier.clickable { if (!header) onRow(r) }.padding(vertical = 2.dp)) {
                        for (c in 0 until t.columnCount) {
                            val cell = row.getOrNull(c).orEmpty()
                            Text(
                                cell.ifEmpty { " " },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (header || (t.hasRowHeader && c == 0)) FontWeight.Bold else null,
                                modifier = Modifier.width(170.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Text("Tap a row to hear it in full", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun CodeBlock(c: CodeItem, current: Boolean, st: PlayerState, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("</>  Code · ${c.language}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            val activeLine = if (current && st.expansion != null) st.expansion.getOrNull(st.expansionIndex) else null
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                c.code.lines().forEach { line ->
                    Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 16.sp,
                        modifier = Modifier.background(if (activeLine != null && line.trim() == activeLine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun Controls(st: PlayerState, engine: Engine) {
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                BigIcon(Icons.Filled.SkipPrevious, "Previous section", engine::previousSection)
                BigIcon(Icons.Filled.FastRewind, "Back one paragraph", engine::previousParagraph)
                FilledIconButton(
                    onClick = engine::togglePlay,
                    modifier = Modifier.size(76.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(if (st.status == Status.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (st.status == Status.PLAYING) "Pause" else "Play", Modifier.size(44.dp))
                }
                BigIcon(Icons.Filled.FastForward, "Forward one paragraph", engine::nextParagraph)
                BigIcon(Icons.Filled.SkipNext, "Next section", engine::nextSection)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { engine.setRate(((st.rate - 0.1f) * 10).roundToInt() / 10f) }) { Icon(Icons.Filled.Remove, "Slower") }
                    Text(String.format(java.util.Locale.US, "%.1f×", st.rate), style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 52.dp))
                    IconButton(onClick = { engine.setRate(((st.rate + 0.1f) * 10).roundToInt() / 10f) }) { Icon(Icons.Filled.Add, "Faster") }
                }
                TextButton(onClick = { engine.setLang(if (st.lang == Lang.EN) Lang.PT else Lang.EN) }) {
                    Text("Voice: ${st.lang.label}", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = engine::refresh) { Icon(Icons.Filled.Refresh, "Reload from Notion") }
            }
        }
    }
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(60.dp)) { Icon(icon, desc, Modifier.size(36.dp)) }
}
