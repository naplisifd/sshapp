package com.sshapp.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.sshapp.terminal.TerminalEmulator.Companion.BOLD
import com.sshapp.terminal.TerminalEmulator.Companion.DEFAULT_COLOR
import com.sshapp.terminal.TerminalEmulator.Companion.DIM
import com.sshapp.terminal.TerminalEmulator.Companion.INVERSE
import com.sshapp.terminal.TerminalEmulator.Companion.INVISIBLE
import com.sshapp.terminal.TerminalEmulator.Companion.ITALIC
import com.sshapp.terminal.TerminalEmulator.Companion.STRIKE
import com.sshapp.terminal.TerminalEmulator.Companion.UNDERLINE

/**
 * What the terminal should display, safe to hand to Compose: every line in it is immutable (scrollback
 * rows never change, screen rows are copies). Styled text is built lazily, only for rows on screen.
 */
data class TerminalSnapshot(
    val lines: List<TerminalEmulator.Line>,
    /** Index into [lines] of the first visible screen row; everything before it is scrollback. */
    val screenStart: Int,
    /** Index into [lines] of the row holding the cursor, or -1 when the cursor is hidden. */
    val cursorIndex: Int,
    val cursorX: Int,
    val version: Long,
    val usingAlt: Boolean,
) {
    companion object {
        val EMPTY = TerminalSnapshot(emptyList(), 0, -1, 0, -1, false)
    }
}

object TerminalColors {
    val background = Color(0xFF0D1117)
    val foreground = Color(0xFFE6EDF3)
    val cursor = Color(0xFF7EE787)

    private val base16 = listOf(
        0xFF21262D, 0xFFFF7B72, 0xFF7EE787, 0xFFD29922, 0xFF58A6FF, 0xFFBC8CFF, 0xFF39C5CF, 0xFFB1BAC4,
        0xFF6E7681, 0xFFFFA198, 0xFF56D364, 0xFFE3B341, 0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFFFFFFF,
    ).map { Color(it) }

    val palette: List<Color> = buildList {
        addAll(base16)
        val steps = listOf(0, 95, 135, 175, 215, 255)
        for (r in 0..5) for (g in 0..5) for (b in 0..5) add(Color(steps[r], steps[g], steps[b]))
        for (i in 0..23) { val v = 8 + i * 10; add(Color(v, v, v)) }
    }

    fun fg(index: Int) = if (index == DEFAULT_COLOR) foreground else palette[index]
    fun bg(index: Int) = if (index == DEFAULT_COLOR) Color.Unspecified else palette[index]
}

/** Snapshots the emulator and converts its lines to styled strings. */
object TerminalRenderer {
    /**
     * Must be called while holding the emulator's lock. Cheap even with a full scrollback: scrollback rows
     * are shared as-is and only screen rows that changed since the last snapshot are copied.
     */
    fun snapshot(term: TerminalEmulator): TerminalSnapshot {
        val screen = term.screen
        val out = ArrayList<TerminalEmulator.Line>((if (term.usingAlt) 0 else term.scrollback.size) + screen.size)
        if (!term.usingAlt) out.addAll(term.scrollback)
        val start = out.size
        for (line in screen) {
            out += line.frozen?.takeIf { line.frozenVersion == line.version }
                ?: line.frozenCopy().also { line.frozen = it; line.frozenVersion = line.version }
        }
        val cursorIndex = if (term.cursorVisible) start + term.cursorY else -1
        return TerminalSnapshot(out, start, cursorIndex, term.cursorX, term.version, term.usingAlt)
    }

    /** Styled text for a snapshot line. UI thread only; cached on the line except for the cursor row. */
    fun text(line: TerminalEmulator.Line, cursorX: Int): AnnotatedString {
        if (cursorX >= 0) return build(line, cursorX)
        (line.uiText as? AnnotatedString)?.let { return it }
        return build(line, -1).also { line.uiText = it }
    }

    private fun build(line: TerminalEmulator.Line, cursorX: Int): AnnotatedString {
        val chars = line.chars
        var len = chars.size
        while (len > 0 && chars[len - 1] == ' ' && line.attrs[len - 1] == TerminalEmulator.DEFAULT_ATTR && len - 1 != cursorX) len--
        if (cursorX >= len) len = cursorX + 1
        val b = AnnotatedString.Builder(len)
        var i = 0
        while (i < len) {
            val a = line.attrs.getOrElse(i) { TerminalEmulator.DEFAULT_ATTR }
            val isCursor = i == cursorX
            var j = i + 1
            if (!isCursor) while (j < len && line.attrs[j] == a && j != cursorX) j++
            val segment = String(chars, i, j - i)
            val start = b.length
            b.append(segment)
            val style = styleFor(a, isCursor)
            if (style != null) b.addStyle(style, start, b.length)
            i = j
        }
        return b.toAnnotatedString()
    }

    private fun styleFor(a: Int, isCursor: Boolean): SpanStyle? {
        if (a == TerminalEmulator.DEFAULT_ATTR && !isCursor) return null
        var fgIndex = TerminalEmulator.fg(a)
        if (a and BOLD != 0 && fgIndex < 8) fgIndex += 8
        var fg = TerminalColors.fg(fgIndex)
        var bg = TerminalColors.bg(TerminalEmulator.bg(a))
        if (a and INVERSE != 0) {
            val newBg = fg
            fg = if (bg == Color.Unspecified) TerminalColors.background else bg
            bg = newBg
        }
        if (a and DIM != 0) fg = fg.copy(alpha = 0.6f)
        if (a and INVISIBLE != 0) fg = Color.Transparent
        if (isCursor) {
            bg = TerminalColors.cursor
            fg = TerminalColors.background
        }
        val decorations = buildList {
            if (a and UNDERLINE != 0) add(TextDecoration.Underline)
            if (a and STRIKE != 0) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (a and BOLD != 0) FontWeight.Bold else null,
            fontStyle = if (a and ITALIC != 0) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        )
    }
}

/** Escape sequences for special keys. */
object TerminalKeys {
    fun arrow(dir: Char, appMode: Boolean) = if (appMode) "\u001bO$dir" else "\u001b[$dir"
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007f"
    const val HOME = "\u001b[H"
    const val END = "\u001b[F"
    const val PAGE_UP = "\u001b[5~"
    const val PAGE_DOWN = "\u001b[6~"
    const val DELETE = "\u001b[3~"

    /** Ctrl+letter → control code (Ctrl+C = 0x03). Returns null if the char has no control form. */
    fun ctrl(c: Char): String? {
        val u = c.uppercaseChar()
        return when {
            u in 'A'..'Z' -> (u.code - 64).toChar().toString()
            u == '[' -> "\u001b"
            u == '\\' -> "\u001c"
            u == ']' -> "\u001d"
            u == '^' -> "\u001e"
            u == '_' || u == '-' -> "\u001f"
            u == ' ' || u == '@' || u == '2' -> "\u0000"
            else -> null
        }
    }
}
