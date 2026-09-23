package io.github.hatake716.dango.ui.quicklook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import androidx.compose.foundation.layout.Arrangement
import io.github.hatake716.dango.ui.browser.components.EntryKindIcon
import io.github.hatake716.dango.ui.browser.components.FinderTextField
import io.github.hatake716.dango.ui.browser.components.FinderPushButton
import io.github.hatake716.dango.ui.browser.components.FinderButtonStyle
import io.github.hatake716.dango.ui.browser.components.FinderAlertButton
import io.github.hatake716.dango.ui.browser.components.FinderAlertDialog
import io.github.hatake716.dango.data.text.LineEnding
import io.github.hatake716.dango.data.text.TextDocument
import io.github.hatake716.dango.data.text.TextFileStore
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.FinderMenu
import io.github.hatake716.dango.ui.browser.components.FinderMenuHeader
import io.github.hatake716.dango.ui.browser.components.FinderMenuItem
import kotlinx.coroutines.delay

/**
 * テキストプレビュー＋簡易編集（SPEC §6.5, §6.5.1）。
 * シンタックスハイライト・Markdown レンダリング・CSV 表表示は M6 で対応（docs/PROGRESS.md）。
 */
@Composable
fun TextPage(
    entry: FsEntry,
    textFileStore: TextFileStore,
    onNotify: (Int) -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    closeSignal: Int = 0,
    onHostClose: () -> Unit = {},
) {
    var doc by remember { mutableStateOf<TextDocument?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pendingHostClose by remember { mutableStateOf(false) }

    LaunchedEffect(entry.path.key) {
        doc = null
        loadError = false
        runCatching { textFileStore.load(entry.path) }
            .onSuccess { doc = it }
            .onFailure { loadError = true }
    }
    LaunchedEffect(editing) {
        onEditingChanged(editing)
    }
    // ホストの×ボタン: 編集中は未保存確認を経由してから Quick Look を閉じる
    LaunchedEffect(closeSignal) {
        if (closeSignal > 0) {
            if (editing) pendingHostClose = true else onHostClose()
        }
    }

    val current = doc
    when {
        loadError -> CenterMessage(stringResource(R.string.ql_load_error))
        current == null -> CenterLoading()
        editing -> TextEditor(
            entry = entry,
            doc = current,
            textFileStore = textFileStore,
            onNotify = onNotify,
            closeSignal = closeSignal,
            onSaved = { newText -> doc = current.copy(text = newText) },
            onExit = {
                editing = false
                if (pendingHostClose) {
                    pendingHostClose = false
                    onHostClose()
                }
            },
        )
        else -> TextViewer(
            entry = entry,
            doc = current,
            textFileStore = textFileStore,
            onDocLoaded = { doc = it },
            onEdit = {
                if (current.editable) {
                    editing = true
                } else {
                    onNotify(R.string.txt_read_only)
                }
            },
        )
    }
}

@Composable
private fun TextViewer(
    entry: FsEntry,
    doc: TextDocument,
    textFileStore: TextFileStore,
    onDocLoaded: (TextDocument) -> Unit,
    onEdit: () -> Unit,
) {
    var wrap by remember { mutableStateOf(true) }
    var loadingAll by remember { mutableStateOf(false) }
    val lines = remember(doc.text) { doc.text.split('\n') }

    LaunchedEffect(loadingAll) {
        if (loadingAll) {
            runCatching { textFileStore.load(entry.path, full = true) }
                .onSuccess { onDocLoaded(it) }
            loadingAll = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = doc.charsetName + " · " + doc.lineEnding.label +
                    if (doc.truncated) " · " + stringResource(R.string.txt_truncated) else "",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (doc.truncated) {
                TextButton(onClick = { loadingAll = true }, enabled = !loadingAll) {
                    Text(stringResource(R.string.txt_load_all), fontSize = 11.sp)
                }
            }
            IconButton(onClick = { wrap = !wrap }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.WrapText,
                    contentDescription = stringResource(R.string.txt_wrap),
                    tint = if (wrap) Color.White else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.txt_edit),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // 行番号付き表示（SPEC §6.5）
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f)),
        ) {
            itemsIndexed(lines) { index, line ->
                Row {
                    Text(
                        text = "${index + 1}",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(44.dp)
                            .padding(end = 8.dp),
                    )
                    Text(
                        text = line.ifEmpty { " " },
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextEditor(
    entry: FsEntry,
    doc: TextDocument,
    textFileStore: TextFileStore,
    onNotify: (Int) -> Unit,
    closeSignal: Int,
    onSaved: (String) -> Unit,
    onExit: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(doc.text)) }
    var lineEnding by remember { mutableStateOf(doc.lineEnding) }
    var dirty by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var showSaveConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var exitAfterSave by remember { mutableStateOf(false) }

    // 取り消し / やり直しスタック（SPEC §6.5.1）。400ms デバウンスでスナップショットを積む
    val undoStack = remember { mutableStateOf(listOf(doc.text)) }
    val redoStack = remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(value.text) {
        if (value.text != undoStack.value.last()) {
            dirty = value.text != doc.text
            delay(400)
            undoStack.value = (undoStack.value + value.text).takeLast(100)
            redoStack.value = emptyList()
        }
    }

    fun undo() {
        val stack = undoStack.value
        if (stack.size <= 1) return
        redoStack.value = redoStack.value + stack.last()
        val prev = stack[stack.size - 2]
        undoStack.value = stack.dropLast(1)
        value = TextFieldValue(prev, TextRange(prev.length))
        dirty = prev != doc.text
    }

    fun redo() {
        val next = redoStack.value.lastOrNull() ?: return
        redoStack.value = redoStack.value.dropLast(1)
        undoStack.value = undoStack.value + next
        value = TextFieldValue(next, TextRange(next.length))
        dirty = next != doc.text
    }

    fun findNext() {
        if (findQuery.isEmpty()) return
        val from = value.selection.max
        val index = value.text.indexOf(findQuery, from).let {
            if (it < 0) value.text.indexOf(findQuery) else it
        }
        if (index >= 0) {
            value = value.copy(selection = TextRange(index, index + findQuery.length))
        }
    }

    fun replaceCurrent() {
        if (findQuery.isEmpty()) return
        val sel = value.selection
        val selected = value.text.substring(sel.min, sel.max)
        if (selected == findQuery) {
            val newText = value.text.replaceRange(sel.min, sel.max, replaceWith)
            value = TextFieldValue(newText, TextRange(sel.min + replaceWith.length))
        }
        findNext()
    }

    fun replaceAll() {
        if (findQuery.isEmpty()) return
        val newText = value.text.replace(findQuery, replaceWith)
        value = TextFieldValue(newText, TextRange(newText.length))
    }

    fun requestExit() {
        if (dirty) showExitConfirm = true else onExit()
    }

    BackHandler { requestExit() }

    LaunchedEffect(closeSignal) {
        if (closeSignal > 0) requestExit()
    }

    LaunchedEffect(saving) {
        if (saving) {
            runCatching {
                textFileStore.save(entry.path, value.text, doc.charsetName, lineEnding)
            }.onSuccess { savedCharset ->
                dirty = false
                onSaved(value.text)
                onNotify(
                    if (savedCharset != doc.charsetName) R.string.txt_saved_utf8 else R.string.txt_saved,
                )
                if (exitAfterSave) {
                    exitAfterSave = false
                    onExit()
                }
            }.onFailure {
                exitAfterSave = false
                onNotify(R.string.op_failed)
            }
            saving = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(), // edge-to-edge のためキーボードに隠れないよう明示的に避ける
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { undo() }, enabled = undoStack.value.size > 1) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = stringResource(R.string.txt_undo),
                    tint = if (undoStack.value.size > 1) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = { redo() }, enabled = redoStack.value.isNotEmpty()) {
                Icon(
                    Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = stringResource(R.string.txt_redo),
                    tint = if (redoStack.value.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(
                    Icons.Outlined.FindReplace,
                    contentDescription = stringResource(R.string.txt_find),
                    tint = if (showSearch) Color.White else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            LineEndingSelector(lineEnding) { lineEnding = it; dirty = true }
            TextButton(onClick = { showSaveConfirm = true }, enabled = dirty && !saving) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = null,
                    tint = if (dirty) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.txt_save), fontSize = 12.sp)
            }
            TextButton(onClick = { requestExit() }) {
                Text(stringResource(R.string.ql_close), fontSize = 12.sp)
            }
        }
        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FinderTextField(
                    value = findQuery,
                    onValueChange = { findQuery = it },
                    placeholder = stringResource(R.string.txt_find),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                FinderTextField(
                    value = replaceWith,
                    onValueChange = { replaceWith = it },
                    placeholder = stringResource(R.string.txt_replace),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FinderPushButton(stringResource(R.string.txt_next), onClick = { findNext() }, compact = true)
                FinderPushButton(stringResource(R.string.txt_replace), onClick = { replaceCurrent() }, compact = true)
                FinderPushButton(stringResource(R.string.txt_replace_all), onClick = { replaceAll() }, compact = true)
            }
        }
        TextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions.Default,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black.copy(alpha = 0.25f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
    }

    if (showSaveConfirm) {
        FinderAlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = stringResource(R.string.txt_save_confirm_title),
            message = stringResource(R.string.txt_save_confirm_body, entry.name),
            icon = { EntryKindIcon(kind = entry.kind, name = entry.name, size = 48.dp) },
            buttons = listOf(
                FinderAlertButton(stringResource(R.string.txt_save), FinderButtonStyle.Default) {
                    showSaveConfirm = false
                    saving = true
                },
                FinderAlertButton(stringResource(R.string.cancel)) { showSaveConfirm = false },
            ),
        )
    }
    if (showExitConfirm) {
        // macOS の「保存しますか？」と同じ並び: 保存（既定）/ 保存しない / キャンセル
        FinderAlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = stringResource(R.string.txt_unsaved_title),
            message = stringResource(R.string.txt_unsaved_body),
            icon = { EntryKindIcon(kind = entry.kind, name = entry.name, size = 48.dp) },
            stackButtons = true,
            buttons = listOf(
                FinderAlertButton(stringResource(R.string.txt_save), FinderButtonStyle.Default) {
                    showExitConfirm = false
                    exitAfterSave = true
                    showSaveConfirm = true
                },
                FinderAlertButton(stringResource(R.string.txt_discard), FinderButtonStyle.Destructive) {
                    showExitConfirm = false
                    onExit()
                },
                FinderAlertButton(stringResource(R.string.cancel)) { showExitConfirm = false },
            ),
        )
    }
}

@Composable
private fun LineEndingSelector(
    current: LineEnding,
    onSelect: (LineEnding) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // macOS のポップアップボタン風: 現在値＋上下シェブロン、選ぶとチェック付きのメニュー
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current.label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = stringResource(R.string.txt_line_ending),
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(12.dp),
            )
        }
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FinderMenuHeader(stringResource(R.string.txt_line_ending))
            LineEnding.entries.forEach { ending ->
                FinderMenuItem(
                    text = ending.label,
                    checked = ending == current,
                    onClick = {
                        expanded = false
                        if (ending != current) onSelect(ending)
                    },
                )
            }
        }
    }
}

@Composable
private fun CenterLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}
