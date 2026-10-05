package com.torxone.app.ui.components

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torxone.app.data.entity.ConversationAppearanceEntity
import com.torxone.app.ui.appearance.*
import com.torxone.app.ui.theme.TorXAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Full-height local editor. Preview changes never touch transport or persistence until Apply. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatAppearanceSheet(
    repository: ChatAppearanceRepository,
    conversationId: String? = null,
    legacy: ConversationAppearanceEntity? = null,
    legacyFlow: Flow<ConversationAppearanceEntity?>? = null,
    onDismiss: () -> Unit
) {
    val global = conversationId == null
    val currentFlow = remember(repository, conversationId, legacy, legacyFlow) {
        if (global) repository.observeDefault().map { ResolvedChatAppearance(it, false) }
        else repository.observeConversation(requireNotNull(conversationId), legacyFlow ?: flowOf(legacy))
    }
    val current by currentFlow.collectAsState(initial = null)
    val default by repository.observeDefault().collectAsState(initial = ChatAppearance())
    var initialized by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(ChatAppearance()) }
    var inherit by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(current) { if (!initialized && current != null) {
        draft = requireNotNull(current).config; inherit = requireNotNull(current).inheritsDefault; initialized = true
    } }
    suspend fun discardPending(keep: String? = null) {
        pending.toList().filter { it != keep }.forEach { repository.discardDraftAsset(it) }
        pending.clear()
    }
    fun cancel() { if (!saving && !importing) scope.launch {
        try { discardPending() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Private orphan cleanup can retry later. */ }
        onDismiss()
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = null
            try {
                val id = repository.assets.importPhoto(uri)
                pending.add(id); inherit = false
                draft = draft.copy(wallpaperType = "PHOTO", wallpaperAssetId = id, photoZoom = 1f, photoPanX = 0f, photoPanY = 0f)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn't open this photo. Choose a supported photo under 20 MB." }
            finally { importing = false }
        }
    }
    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(
            title = { Text(if (global) "Default chat appearance" else "Chat appearance") },
            navigationIcon = { IconButton(onClick = ::cancel, enabled = !saving && !importing) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel appearance changes") } },
            actions = { TextButton(enabled = initialized && !saving && !importing, onClick = { scope.launch {
                saving = true; error = null
                try {
                    if (global) repository.setDefault(draft)
                    else if (inherit) repository.useDefault(requireNotNull(conversationId))
                    else repository.setOverride(requireNotNull(conversationId), draft)
                    try { discardPending(if (inherit) null else draft.wallpaperAssetId); repository.cleanUnusedAssets() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Saved configuration remains authoritative; cleanup is best effort. */ }
                    onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn't save appearance. Your previous settings are unchanged." }
                finally { saving = false }
            } }) { Text(if (saving) "Saving…" else "Apply") } }
        ) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Wallpaper and appearance stay on this device.", style = MaterialTheme.typography.bodySmall)
                if (!global) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Use default", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = inherit, onCheckedChange = { inherit = it; if (it) draft = default }, enabled = !saving && !importing,
                        modifier = Modifier.semantics { contentDescription = "Use global chat appearance" })
                }
                val shown = if (inherit) default else draft
                ChatThemePreview(shown, Modifier.pointerInput(inherit, shown.wallpaperType) {
                    if (!inherit && shown.wallpaperType == "PHOTO") detectTransformGestures { _, pan, zoom, _ ->
                        draft = draft.copy(photoZoom = (draft.photoZoom * zoom).coerceIn(1f, 4f),
                            photoPanX = (draft.photoPanX + pan.x / 180f).coerceIn(-1f, 1f),
                            photoPanY = (draft.photoPanY + pan.y / 180f).coerceIn(-1f, 1f))
                    }
                })
                val enabled = !inherit && !saving && !importing
                if (importing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing private wallpaper…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SectionChoices("Chat theme", ChatAppearance.presets, draft.preset, enabled) { draft = draft.copy(preset = it) }
                Text("Chat accent", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.accentId == null, enabled = enabled, onClick = { draft = draft.copy(accentId = null) }, label = { Text("App accent") }, modifier = Modifier.heightIn(min = 48.dp))
                    TorXAccent.entries.forEach { accent -> FilterChip(selected = draft.accentId == accent.name, enabled = enabled,
                        onClick = { draft = draft.copy(accentId = accent.name) }, label = { Text(accent.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                SectionChoices("Wallpaper", listOf("NONE", "WARM", "COOL"), draft.wallpaperType, enabled) { draft = draft.copy(wallpaperType = it) }
                OutlinedButton(onClick = { picker.launch("image/*") }, enabled = enabled) { Text(if (draft.wallpaperType == "PHOTO") "Replace photo" else "Choose photo") }
                if (draft.wallpaperType == "PHOTO") {
                    if (repository.assets.resolve(draft.wallpaperAssetId) == null) Text("Wallpaper photo unavailable. Choose a photo again.", color = MaterialTheme.colorScheme.error)
                    Text("Pinch and drag the preview, or use the crop controls.", style = MaterialTheme.typography.bodySmall)
                    AppearanceSlider("Photo zoom", draft.photoZoom, 1f..4f, enabled) { draft = draft.copy(photoZoom = it) }
                    AppearanceSlider("Horizontal position", draft.photoPanX, -1f..1f, enabled) { draft = draft.copy(photoPanX = it) }
                    AppearanceSlider("Vertical position", draft.photoPanY, -1f..1f, enabled) { draft = draft.copy(photoPanY = it) }
                    TextButton(enabled = enabled, onClick = { draft = draft.copy(photoZoom = 1f, photoPanX = 0f, photoPanY = 0f) }) { Text("Reset crop") }
                    AppearanceSlider("Wallpaper dim", draft.dim, 0f..0.8f, enabled) { draft = draft.copy(dim = it) }
                    AppearanceSlider("Wallpaper blur", draft.blur, 0f..20f, enabled && Build.VERSION.SDK_INT >= 31) { draft = draft.copy(blur = it) }
                    if (Build.VERSION.SDK_INT < 31) Text("Photo blur requires Android 12 or newer.", style = MaterialTheme.typography.bodySmall)
                }
                SectionChoices("Bubble style", ChatAppearance.bubbles, draft.bubbleStyle, enabled) { draft = draft.copy(bubbleStyle = it) }
                if (draft.preset == "DEPTH") {
                    SectionChoices("Depth motion", ChatAppearance.motions, draft.motionMode, enabled) { draft = draft.copy(motionMode = it) }
                    AppearanceSlider("Parallax strength", draft.parallaxStrength, 0f..1f, enabled && draft.motionMode != "REDUCED") { draft = draft.copy(parallaxStrength = it) }
                    Text("Battery saver and disabled system animations automatically use a static background.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = enabled, onClick = { draft = ChatAppearance() }) { Text("Reset appearance") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionChoices(title: String, values: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { value ->
        FilterChip(selected = selected == value, enabled = enabled, onClick = { onSelect(value) },
            label = { Text(value.lowercase().replaceFirstChar { it.titlecase() }) }, modifier = Modifier.heightIn(min = 48.dp))
    } }
}
@Composable
private fun AppearanceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label })
}
