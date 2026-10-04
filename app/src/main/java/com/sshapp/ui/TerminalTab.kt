package com.sshapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshapp.SessionController
import com.sshapp.data.CatalogCommand
import com.sshapp.data.Suggester
import com.sshapp.terminal.TerminalColors
import com.sshapp.terminal.TerminalKeys
import kotlinx.coroutines.launch

private const val SENTINEL = "​​"

@Composable
fun TerminalTab(
    session: SessionController,
    fontSize: Float,
    pendingInput: String?,
    onPendingConsumed: () -> Unit,
    disconnected: Boolean,
    onReconnect: () -> Unit,
) {
    val snapshot by session.terminal.collectAsState()
    val appCursor by session.appCursorKeys.collectAsState()
    val passwordPrompt by session.passwordPrompt.collectAsState()
    val historyEntries by session.history.entries.collectAsState()

    var rawMode by rememberSaveable { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf(TextFieldValue("")) }
    var raw by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Full-screen apps (nano, htop, less, vim) need keystrokes, not lines.
    LaunchedEffect(snapshot.usingAlt) { rawMode = snapshot.usingAlt }

    LaunchedEffect(pendingInput) {
        val cmd = pendingInput ?: return@LaunchedEffect
        rawMode = false
        val ph = CatalogCommand.PLACEHOLDER.find(cmd)
        line = TextFieldValue(cmd, ph?.let { TextRange(it.range.first, it.range.last + 1) } ?: TextRange(cmd.length))
        onPendingConsumed()
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun send(s: String) = session.sendRaw(s)

    /** Applies the sticky Ctrl modifier to typed text; returns true if consumed. */
    fun consumeCtrl(typed: String): Boolean {
        if (!ctrlArmed || typed.isEmpty()) return false
        ctrlArmed = false
        TerminalKeys.ctrl(typed.last())?.let(::send)
        return true
    }

    fun submitLine() {
        val text = line.text
        session.sendCommand(text)
        line = TextFieldValue("")
    }

    val suggestions = remember(line.text, historyEntries) { Suggester.suggest(line.text, historyEntries, 10) }

    Column(Modifier.fillMaxSize()) {
        // Terminal output
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalColors.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    runCatching { focus.requestFocus() }
                    keyboard?.show()
                }
                .padding(horizontal = 4.dp),
        ) {
            val measurer = rememberTextMeasurer()
            val style = remember(fontSize) {
                TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.2f).sp, color = TerminalColors.foreground)
            }
            val cell = remember(style) { measurer.measure("W".repeat(20), style, softWrap = false) }
            val density = LocalDensity.current
            val charW = cell.size.width / 20f
            val lineH = cell.size.height
            val cols = (constraints.maxWidth / charW).toInt().coerceAtLeast(20)
            val rows = (constraints.maxHeight / lineH).coerceAtLeast(5)
            // Debounced so the keyboard slide animation doesn't cause a burst of resizes.
            LaunchedEffect(cols, rows) {
                kotlinx.coroutines.delay(150)
                session.resizeTerminal(cols, rows)
            }

            val listState = rememberLazyListState()
            val lines = snapshot.lines
            val lineHeightDp = with(density) { lineH.toDp() }
            // reverseLayout keeps the newest output pinned to the bottom unless the user scrolls back.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !snapshot.usingAlt,
            ) {
                items(lines.size) { i ->
                    Text(
                        lines[lines.size - 1 - i],
                        style = style,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.height(lineHeightDp),
                    )
                }
            }
            val scrolledBack by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
            if (scrolledBack) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.scrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Default.KeyboardDoubleArrowDown, "Jump to bottom") }
            }
        }

        if (disconnected) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Session ended", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onReconnect) { Text("Reconnect") }
                }
            }
        }

        // Suggestions for the command line
        if (!rawMode && !passwordPrompt) {
            LazyRow(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(suggestions, key = { it.command }) { s ->
                    SuggestionChip(
                        command = s.command,
                        fromHistory = s.timesUsed > 0,
                        onClick = {
                            val ph = CatalogCommand.PLACEHOLDER.find(s.command)
                            line = TextFieldValue(
                                s.command,
                                ph?.let { TextRange(it.range.first, it.range.last + 1) } ?: TextRange(s.command.length),
                            )
                            runCatching { focus.requestFocus() }
                        },
                    )
                }
            }
        }

        // Special keys
        ExtraKeys(
            ctrlArmed = ctrlArmed,
            onCtrl = { ctrlArmed = !ctrlArmed },
            onKey = { send(it); ctrlArmed = false },
            appCursor = appCursor,
        )

        // Input
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { rawMode = !rawMode; runCatching { focus.requestFocus() } }) {
                Icon(
                    if (rawMode) Icons.Default.Keyboard else Icons.AutoMirrored.Filled.ShortText,
                    if (rawMode) "Switch to command line" else "Switch to raw keys",
                    tint = if (rawMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
            }
            val fieldColors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            )
            val hardwareKeys = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val arrow = when (e.key) {
                    Key.DirectionUp -> 'A'; Key.DirectionDown -> 'B'
                    Key.DirectionRight -> 'C'; Key.DirectionLeft -> 'D'
                    else -> null
                }
                when {
                    e.isCtrlPressed && e.utf16CodePoint > 0 ->
                        TerminalKeys.ctrl(e.utf16CodePoint.toChar())?.let { send(it); true } ?: false
                    rawMode && arrow != null -> { send(TerminalKeys.arrow(arrow, appCursor)); true }
                    rawMode && e.key == Key.Escape -> { send(TerminalKeys.ESC); true }
                    rawMode && e.key == Key.Tab -> { send(TerminalKeys.TAB); true }
                    else -> false
                }
            }
            if (rawMode) {
                TextField(
                    value = raw,
                    onValueChange = { v ->
                        val t = v.text
                        when {
                            t.length < SENTINEL.length -> send(TerminalKeys.BACKSPACE.repeat(SENTINEL.length - t.length))
                            t.startsWith(SENTINEL) && t.length > SENTINEL.length -> {
                                val typed = t.substring(SENTINEL.length)
                                if (!consumeCtrl(typed)) send(typed.replace("\n", "\r"))
                            }
                        }
                        raw = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                    },
                    placeholder = { Text("Raw keys: typing goes straight to the shell", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send(TerminalKeys.ENTER) }),
                    modifier = Modifier.weight(1f).focusRequester(focus).then(hardwareKeys),
                )
                IconButton(onClick = { send(TerminalKeys.ENTER) }) { Icon(Icons.AutoMirrored.Filled.Send, "Enter") }
            } else {
                TextField(
                    value = line,
                    onValueChange = { v ->
                        val typed = if (v.text.length > line.text.length) v.text.getOrNull(v.selection.start - 1)?.toString() else null
                        if (typed == null || !consumeCtrl(typed)) line = v
                    },
                    placeholder = { Text(if (passwordPrompt) "Password (not saved to history)" else "Type a command…") },
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    visualTransformation = if (passwordPrompt) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (passwordPrompt) KeyboardType.Password else KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submitLine() }),
                    modifier = Modifier.weight(1f).focusRequester(focus).then(hardwareKeys),
                )
                IconButton(onClick = ::submitLine) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(command: String, fromHistory: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (fromHistory) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (fromHistory) Icons.Default.History else Icons.Default.AutoAwesome,
                null,
                Modifier.size(14.dp),
                tint = if (fromHistory) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 260.dp),
            )
        }
    }
}

@Composable
private fun ExtraKeys(ctrlArmed: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit, appCursor: Boolean) {
    val keys: List<Pair<String, String>> = listOf(
        "Esc" to TerminalKeys.ESC,
        "Tab" to TerminalKeys.TAB,
        "↑" to TerminalKeys.arrow('A', appCursor),
        "↓" to TerminalKeys.arrow('B', appCursor),
        "←" to TerminalKeys.arrow('D', appCursor),
        "→" to TerminalKeys.arrow('C', appCursor),
        "^C" to "\u0003",
        "^D" to "\u0004",
        "^Z" to "\u001a",
        "^R" to "\u0012",
        "^L" to "\u000c",
        "Home" to TerminalKeys.HOME,
        "End" to TerminalKeys.END,
        "PgUp" to TerminalKeys.PAGE_UP,
        "PgDn" to TerminalKeys.PAGE_DOWN,
        "Del" to TerminalKeys.DELETE,
        "|" to "|", "~" to "~", "/" to "/", "-" to "-",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyButton("Ctrl", highlighted = ctrlArmed, onClick = onCtrl)
        keys.forEach { (label, seq) -> KeyButton(label, onClick = { onKey(seq) }) }
    }
}

@Composable
private fun KeyButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 36.dp).widthIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}
