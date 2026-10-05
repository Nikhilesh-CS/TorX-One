package com.torxone.app.ui.components

import android.content.Context
import android.text.TextPaint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
data class EmojiEntry(val emoji: String, val name: String, val category: String, val subgroup: String)

object EmojiCatalogue {
    @Volatile private var cached: List<EmojiEntry>? = null
    private val mutex = Mutex()
    fun parse(lines: Sequence<String>): List<EmojiEntry> {
        var category = ""; var subgroup = ""
        val entries = ArrayList<EmojiEntry>()
        for (line in lines) {
            when {
                line.startsWith("# group: ") -> category = line.substringAfter("# group: ")
                line.startsWith("# subgroup: ") -> subgroup = line.substringAfter("# subgroup: ")
                !line.startsWith('#') && ';' in line && '#' in line -> {
                    val status = line.substringAfter(';').substringBefore('#').trim()
                    if (status != "fully-qualified" && status != "component") continue
                    val points = line.substringBefore(';').trim().split(Regex("\\s+")).map { it.toInt(16) }
                    val emoji = buildString { points.forEach { appendCodePoint(it) } }
                    val description = line.substringAfter('#').trim().substringAfter(' ').substringAfter(' ')
                    entries += EmojiEntry(emoji, description, category, subgroup)
                }
            }
        }
        return entries.distinctBy { it.emoji }
    }
    suspend fun load(context: Context): List<EmojiEntry> = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            context.applicationContext.assets.open("emoji/emoji-test.txt").bufferedReader().use { parse(it.lineSequence()) }
        }.also { cached = it }
    }
    fun tone(entry: EmojiEntry): List<Int> = entry.emoji.codePoints().toArray().filter { it in 0x1F3FB..0x1F3FF }
    /** Keyboard input may omit text/emoji presentation selectors; preserve the selected string. */
    fun keyboardEquivalent(catalogueEmoji: String, input: String): Boolean =
        catalogueEmoji.filterNot { it == '\uFE0E' || it == '\uFE0F' } ==
            input.filterNot { it == '\uFE0E' || it == '\uFE0F' }
    fun matches(entry: EmojiEntry, query: String, category: String, skin: Int): Boolean =
        (category == "All" || entry.category == category) &&
            (query.isBlank() || (entry.name + " " + entry.subgroup + " " + entry.emoji).contains(query.trim(), ignoreCase = true)) &&
            (skin == -1 || if (skin == 0) tone(entry).isEmpty() else skin in tone(entry))
}

@Serializable private data class EmojiUsage(val emoji: String, val count: Int, val lastUsed: Long)

/** Tiny local history. Quick reactions never load/search the catalogue or wait for preferences. */
object EmojiReactionHistory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val mutex = Mutex()
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("reaction_picker", Context.MODE_PRIVATE)
    private fun read(context: Context): List<EmojiUsage> = runCatching {
        val text = preferences(context).getString("usage", "[]") ?: "[]"
        if (text.length > 32_768) emptyList() else Json.decodeFromString<List<EmojiUsage>>(text)
            .filter { it.emoji.isNotBlank() && it.emoji.length <= 32 && it.count in 1..10_000 }.take(100)
    }.getOrDefault(emptyList())
    suspend fun lists(context: Context): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val usage = mutex.withLock { read(context) }
        usage.sortedByDescending { it.lastUsed }.take(24).map { it.emoji } to
            usage.sortedWith(compareByDescending<EmojiUsage> { it.count }.thenByDescending { it.lastUsed }).take(24).map { it.emoji }
    }
    fun record(context: Context, emoji: String) {
        val appContext = context.applicationContext
        scope.launch { mutex.withLock {
            val current = read(appContext)
            val count = ((current.firstOrNull { it.emoji == emoji }?.count ?: 0) + 1).coerceAtMost(10_000)
            val updated = listOf(EmojiUsage(emoji, count, System.currentTimeMillis())) + current.filter { it.emoji != emoji }
            preferences(appContext).edit().putString("usage", Json.encodeToString(updated.take(100))).commit()
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiReactionPicker(onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var skin by rememberSaveable { mutableIntStateOf(-1) }
    var keyboardEmoji by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<EmojiEntry>?>(null) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var frequent by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(context) {
        try {
            val all = EmojiCatalogue.load(context)
            entries = withContext(Dispatchers.Default) {
                val paint = TextPaint().apply { textSize = 32f }
                all.filter { it.emoji.length <= 32 && paint.hasGlyph(it.emoji) }
            }
            val lists = EmojiReactionHistory.lists(context)
            recent = lists.first; frequent = lists.second
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't load the emoji catalogue. Try your emoji keyboard or reopen this picker." }
    }
    val all = entries.orEmpty()
    val names = remember(all) { all.associateBy { it.emoji } }
    val categories = remember(all) { listOf("Recent", "Frequent", "All") + all.map { it.category }.distinct() }
    val visible = remember(all, query, category, skin, recent, frequent) {
        val list = when (category) {
            "Recent" -> recent.mapNotNull(names::get)
            "Frequent" -> frequent.mapNotNull(names::get)
            else -> all
        }
        list.filter { EmojiCatalogue.matches(it, query, if (category in listOf("Recent", "Frequent")) "All" else category, skin) }
    }
    fun choose(emoji: String) {
        // Close first; authoritative VM/Room reaction state remains the owner of add/remove.
        onDismiss()
        onSelected(emoji)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("React to message", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, label = { Text("Search emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(categories) { item ->
                FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) })
            } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(-1 to "All tones", 0 to "Default") + (0x1F3FB..0x1F3FF).mapIndexed { index, point ->
                    point to listOf("Light", "Medium light", "Medium", "Medium dark", "Dark")[index]
                }) { (point, label) -> FilterChip(selected = skin == point, onClick = { skin = point }, label = { Text(label) }) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (entries == null && error == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading supported emoji…") }
            else if (visible.isEmpty()) Text(if (query.isBlank()) "No emoji in this selection yet." else "No matching emoji. Try another name or your keyboard.")
            LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 48.dp, max = 320.dp)) {
                items(visible, key = { it.emoji }) { item ->
                    TextButton(onClick = { choose(item.emoji) }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "React with ${item.name}" }) {
                        Text(item.emoji, fontSize = 26.sp)
                    }
                }
            }
            OutlinedTextField(keyboardEmoji, { if (it.length <= 32) keyboardEmoji = it }, singleLine = true,
                label = { Text("Or enter an emoji from your keyboard") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val value = keyboardEmoji.trim()
                val paint = TextPaint().apply { textSize = 32f }
                val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(value) }
                val singleCluster = iterator.first() == 0 && iterator.next() == value.length && iterator.next() == android.icu.text.BreakIterator.DONE
                // UProperty.EMOJI is exposed from API 28; older devices use the bundled repertoire.
                val containsEmoji = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    value.codePoints().anyMatch { it == 0x20E3 ||
                        (it > 0x7F && android.icu.lang.UCharacter.hasBinaryProperty(it, android.icu.lang.UProperty.EMOJI)) }
                } else all.any { EmojiCatalogue.keyboardEquivalent(it.emoji, value) }
                if (value.isNotEmpty() && value.length <= 32 && singleCluster && containsEmoji && paint.hasGlyph(value)) choose(value)
                else error = "Enter one emoji or emoji sequence supported by your device."
            }, enabled = keyboardEmoji.isNotBlank()) { Text("React with keyboard emoji") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
