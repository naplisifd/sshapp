package com.sshapp.ui

import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sshapp.SessionController
import com.sshapp.TreeRow
import com.sshapp.ssh.RemoteFile
import com.sshapp.ssh.SshConnection
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface FileDialog {
    data class NewFolder(val parent: String) : FileDialog
    data class Rename(val file: RemoteFile) : FileDialog
    data class Delete(val file: RemoteFile) : FileDialog
    data object GoTo : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesTab(session: SessionController, onRunInTerminal: (String) -> Unit) {
    val tree by session.tree.collectAsState()
    val rows = remember(tree) { session.visibleRows(tree) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var actionsFor by remember { mutableStateOf<RemoteFile?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    var uploadTarget by remember { mutableStateOf<String?>(null) }
    var downloadSource by remember { mutableStateOf<RemoteFile?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val dir = uploadTarget ?: return@rememberLauncherForActivityResult
        uploadTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "upload.bin"
        session.upload(dir, name) { context.contentResolver.openInputStream(uri) }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val file = downloadSource ?: return@rememberLauncherForActivityResult
        downloadSource = null
        if (uri != null) session.download(file) { context.contentResolver.openOutputStream(uri) }
    }

    fun copy(text: String) = scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", text)))
        session.emitMessage("Copied $text")
    }

    Column(Modifier.fillMaxSize()) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { session.openFolder(SshConnection.parentOf(tree.root)) }, enabled = tree.root != "/") {
                        Icon(Icons.Default.ArrowUpward, "Parent folder")
                    }
                    IconButton(onClick = { session.openFolder(session.homeDir) }) { Icon(Icons.Default.Home, "Home") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = session::toggleHidden) {
                        Icon(if (tree.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Show hidden files")
                    }
                    IconButton(onClick = { dialog = FileDialog.NewFolder(tree.root) }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                    IconButton(onClick = { uploadTarget = tree.root; uploadLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, "Upload") }
                    IconButton(onClick = session::refreshTree) { Icon(Icons.Default.Refresh, "Refresh") }
                }
                Breadcrumbs(tree.root, onOpen = session::openFolder, onEdit = { dialog = FileDialog.GoTo })
            }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val rootLoading = tree.root in tree.loading && tree.root !in tree.children
            val rootError = tree.errors[tree.root]
            when {
                rootLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                rootError != null -> Text(rootError, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                rows.isEmpty() && tree.root in tree.children -> Text(
                    "Empty folder", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rows, key = { it.file.path }) { row ->
                        TreeItem(
                            row,
                            onClick = {
                                if (row.file.isDir) session.toggleExpanded(row.file)
                                else viewing = row.file
                            },
                            onLongClick = { actionsFor = row.file },
                        )
                    }
                }
            }
        }
    }

    actionsFor?.let { f ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${f.permissions}  ${if (f.isDir) "folder" else formatSize(f.size)}  ${formatDate(f.modified)}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                    },
                    leadingContent = { FileIcon(f, expanded = false) },
                )
                HorizontalDivider()
                @Composable
                fun action(icon: ImageVector, label: String, block: () -> Unit) {
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Icon(icon, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(onClick = { actionsFor = null; block() }),
                    )
                }
                val quoted = SshConnection.shellQuote(f.path)
                if (f.isDir) {
                    action(Icons.Default.FolderOpen, "Open as top folder") { session.openFolder(f.path) }
                    action(Icons.Default.Terminal, "cd here in terminal") { onRunInTerminal("cd $quoted") }
                    action(Icons.Default.CreateNewFolder, "New folder inside") { dialog = FileDialog.NewFolder(f.path) }
                    action(Icons.Default.Upload, "Upload file here") { uploadTarget = f.path; uploadLauncher.launch(arrayOf("*/*")) }
                    action(Icons.Default.Archive, "Show size (du)") { onRunInTerminal("du -sh $quoted") }
                } else {
                    action(Icons.AutoMirrored.Filled.Article, "View / edit") { viewing = f }
                    action(Icons.Default.Edit, "Open in nano (sudo)") { onRunInTerminal("sudo nano $quoted") }
                    if (f.name.endsWith(".log") || f.path.startsWith("/var/log")) {
                        action(Icons.Default.Terminal, "Follow (tail -f)") { onRunInTerminal("sudo tail -f -n 100 $quoted") }
                    }
                    action(Icons.Default.Download, "Download to phone") { downloadSource = f; downloadLauncher.launch(f.name) }
                }
                action(Icons.Default.ContentCopy, "Copy path") { copy(f.path) }
                action(Icons.Default.DriveFileRenameOutline, "Rename") { dialog = FileDialog.Rename(f) }
                action(Icons.Default.Delete, "Delete") { dialog = FileDialog.Delete(f) }
            }
        }
    }

    when (val d = dialog) {
        is FileDialog.NewFolder -> TextPromptDialog("New folder in ${d.parent}", "Folder name", "", "Create", onDismiss = { dialog = null }) {
            dialog = null; session.createFolder(d.parent, it)
        }
        is FileDialog.Rename -> TextPromptDialog("Rename", "New name", d.file.name, "Rename", onDismiss = { dialog = null }) {
            dialog = null; session.rename(d.file, it)
        }
        is FileDialog.GoTo -> TextPromptDialog("Go to folder", "Path", tree.root, "Open", onDismiss = { dialog = null }) {
            dialog = null; session.openFolder(it.trimEnd('/').ifEmpty { "/" })
        }
        is FileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.file.name}?") },
            text = { Text(if (d.file.isDir) "This folder and everything inside it will be permanently deleted." else "This file will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { dialog = null; session.delete(d.file) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> {}
    }

    viewing?.let { f ->
        FileViewer(session, f, onClose = { viewing = null }, onOpenInNano = { viewing = null; onRunInTerminal("sudo nano ${SshConnection.shellQuote(f.path)}") })
    }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit, onEdit: () -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    val state = rememberLazyListState()
    LaunchedEffect(path) { state.scrollToItem(parts.size) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Crumb("/", current = parts.isEmpty()) { onOpen("/") } }
        itemsIndexed(parts) { i, part ->
            Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val target = "/" + parts.take(i + 1).joinToString("/")
            Crumb(part, current = i == parts.lastIndex) { if (i == parts.lastIndex) onEdit() else onOpen(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeItem(row: TreeRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val f = row.file
    val guide = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(44.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indentation guides make the folder hierarchy visible at a glance.
        repeat(row.depth) {
            Box(Modifier.width(20.dp).fillMaxHeightGuide(guide))
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                row.loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                f.isDir -> Icon(
                    Icons.Default.ExpandMore, null,
                    Modifier.size(20.dp).rotate(if (row.expanded) 0f else -90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FileIcon(f, row.expanded)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (row.error != null) {
                Text(row.error, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            if (f.isDir) "" else formatSize(f.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

private fun Modifier.fillMaxHeightGuide(color: Color) = this.then(
    Modifier.height(44.dp).padding(start = 11.dp, end = 8.dp).background(color)
)

@Composable
private fun FileIcon(f: RemoteFile, expanded: Boolean) {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val (icon, tint) = when {
        f.isDir -> (if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder) to Color(0xFFE3B341)
        f.isLink -> Icons.Default.Link to MaterialTheme.colorScheme.secondary
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico") -> Icons.Default.Image to Color(0xFFD2A8FF)
        ext in setOf("gz", "tgz", "zip", "xz", "bz2", "tar", "7z", "deb", "zst") -> Icons.Default.Archive to Color(0xFFFFA657)
        ext in setOf("sh", "py", "js", "ts", "go", "rs", "rb", "php", "java", "kt", "c", "cpp", "h", "html", "css") -> Icons.Default.Code to Color(0xFF79C0FF)
        ext in setOf("conf", "cfg", "ini", "yaml", "yml", "toml", "json", "env", "service", "xml") || f.name.startsWith(".") ->
            Icons.Default.Settings to Color(0xFF7EE787)
        ext in setOf("log", "txt", "md") -> Icons.AutoMirrored.Filled.Article to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, null, Modifier.size(22.dp), tint = tint)
}

@Composable
private fun TextPromptDialog(
    title: String, label: String, initial: String, confirm: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewer(session: SessionController, file: RemoteFile, onClose: () -> Unit, onOpenInNano: () -> Unit) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file.path) {
        session.readText(file)
            .onSuccess { (text, cut) -> content = text; edited = text; truncated = cut }
            .onFailure { error = SessionController.describe(it) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") } },
                    actions = {
                        if (content != null && !truncated) {
                            if (editing) {
                                IconButton(enabled = !saving, onClick = {
                                    saving = true
                                    scope.launch {
                                        session.writeText(file, edited)
                                            .onSuccess { content = edited; editing = false; session.emitMessage("Saved ${file.name}") }
                                            .onFailure { error = SessionController.describe(it) + "\nTry \"Open in nano (sudo)\" for system files." }
                                        saving = false
                                    }
                                }) { Icon(Icons.Default.Save, "Save") }
                            } else {
                                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit") }
                            }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Open in nano (sudo)") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                    onClick = { menu = false; onOpenInNano() },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                when {
                    error != null && content == null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    content == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> Column(Modifier.fillMaxSize()) {
                        if (truncated) Text(
                            "Showing the first ${SessionController.MAX_PREVIEW_BYTES / 1024} KB (read-only).",
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (error != null) Text(
                            error!!,
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val scrollModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)
                        if (editing) {
                            BasicTextField(edited, { edited = it; error = null }, textStyle = mono, modifier = scrollModifier)
                        } else {
                            SelectionContainer(scrollModifier) { Text(content!!, style = mono, softWrap = false) }
                        }
                    }
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

private fun formatDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
