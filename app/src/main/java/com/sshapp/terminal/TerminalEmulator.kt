package com.sshapp.terminal

/**
 * A compact xterm-compatible screen model: enough of VT100/xterm for shells, `top`, `htop`,
 * `nano`, `less`, `vim` and whiptail dialogs. Not thread-safe; callers synchronize on the instance.
 *
 * Cell attributes are packed into an Int: fg (bits 0-8), bg (bits 9-17), flags (bits 18+).
 * Colour index [DEFAULT_COLOR] means "terminal default".
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    /** Bytes the terminal must send back to the host (device status reports etc). */
    private val reply: (String) -> Unit,
) {
    /**
     * One row of text. Rows on screen are modified in place; once a row scrolls into [scrollback] it is
     * never modified again, which lets snapshots share scrollback rows with the UI without copying.
     */
    class Line(
        cols: Int,
        /** Stable identity for UI list keys; kept as the row scrolls into scrollback and by [frozenCopy]. */
        val id: Long,
    ) {
        var chars = CharArray(cols).also { java.util.Arrays.fill(it, ' ') }
        var attrs = IntArray(cols).also { java.util.Arrays.fill(it, DEFAULT_ATTR) }
        /** Bumped on every modification so renderers can cache. */
        var version = 0L

        /** Immutable copy for the UI, valid while [frozenVersion] == [version]. Only touched under the emulator lock. */
        @JvmField internal var frozen: Line? = null
        @JvmField internal var frozenVersion = -1L
        /** Styled text cache for the renderer. Only touched on the UI thread, and only on rows that no longer change. */
        @JvmField internal var uiText: Any? = null

        internal fun frozenCopy(): Line = Line(0, id).also {
            it.chars = chars.copyOf()
            it.attrs = attrs.copyOf()
            it.version = version
        }

        fun resize(cols: Int) {
            if (cols == chars.size) return
            val old = chars.size
            chars = chars.copyOf(cols).also { for (i in old until cols) it[i] = ' ' }
            attrs = attrs.copyOf(cols).also { for (i in old until cols) it[i] = DEFAULT_ATTR }
            version++
        }

        fun clear(from: Int, to: Int, attr: Int) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
                chars[i] = ' '
                attrs[i] = attr
            }
            version++
        }

        fun text(): String = String(chars).trimEnd()
    }

    var cols = cols; private set
    var rows = rows; private set

    private var nextLineId = 0L
    private fun newLine(cols: Int) = Line(cols, nextLineId++)

    private var main = MutableList(rows) { newLine(cols) }
    private var alt = MutableList(rows) { newLine(cols) }
    val scrollback = ArrayDeque<Line>()
    var usingAlt = false; private set
    val screen: List<Line> get() = if (usingAlt) alt else main

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    var appCursorKeys = false; private set
    var bracketedPaste = false; private set

    private var attr = DEFAULT_ATTR
    private var wrapPending = false
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedX = 0
    private var savedY = 0
    private var savedAttr = DEFAULT_ATTR
    private var tabStops = BooleanArray(cols) { it % 8 == 0 }
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET0, CHARSET1, SKIP_ONE, DCS, DCS_ESC }

    private var state = State.GROUND
    private val params = StringBuilder()
    private var csiPrivate: Char? = null

    /** Monotonic counter of changes; renderers poll this. */
    var version = 0L; private set

    fun feed(buf: CharArray, len: Int) {
        for (i in 0 until len) process(buf[i])
        version++
    }

    fun feed(s: String) = feed(s.toCharArray(), s.length)

    /** Text of the row the cursor is on, used to detect password prompts. */
    fun cursorLineText(): String = screen[cursorY].text()

    // region parser

    private fun process(c: Char) {
        when (state) {
            State.GROUND -> ground(c)
            State.ESC -> escape(c)
            State.CSI -> csi(c)
            State.OSC -> when (c) {
                '\u0007' -> state = State.GROUND
                '\u001b' -> state = State.OSC_ESC
                else -> {}
            }
            State.OSC_ESC -> state = if (c == '\\') State.GROUND else State.OSC
            State.DCS -> if (c == '\u001b') state = State.DCS_ESC
            State.DCS_ESC -> state = if (c == '\\') State.GROUND else State.DCS
            State.CHARSET0 -> { g0Graphics = c == '0'; state = State.GROUND }
            State.CHARSET1 -> { g1Graphics = c == '0'; state = State.GROUND }
            State.SKIP_ONE -> state = State.GROUND
        }
    }

    private fun ground(c: Char) {
        when (c) {
            '\u001b' -> state = State.ESC
            '\r' -> { cursorX = 0; wrapPending = false }
            '\n', '\u000b', '\u000c' -> lineFeed()
            '\b' -> { if (cursorX > 0) cursorX--; wrapPending = false }
            '\t' -> tab()
            '\u0007' -> {}
            '\u000e' -> shiftOut = true
            '\u000f' -> shiftOut = false
            else -> if (c >= ' ' && c != '\u007f') put(c)
        }
    }

    private fun escape(c: Char) {
        state = State.GROUND
        when (c) {
            '[' -> { state = State.CSI; params.setLength(0); csiPrivate = null }
            ']' -> state = State.OSC
            'P' -> state = State.DCS
            '(' -> state = State.CHARSET0
            ')' -> state = State.CHARSET1
            '*', '+', '#', '%', ' ' -> state = State.SKIP_ONE
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            'M' -> reverseIndex()
            'H' -> if (cursorX < cols) tabStops[cursorX] = true
            'c' -> reset()
            '=', '>', '\\' -> {}
        }
    }

    private fun csi(c: Char) {
        when {
            c in '0'..'9' || c == ';' || c == ':' -> params.append(c)
            c == '?' || c == '>' || c == '<' || c == '=' -> csiPrivate = c
            c in ' '..'/' -> {} // intermediates: ignored
            c in '@'..'~' -> { state = State.GROUND; dispatchCsi(c) }
            c == '\u001b' -> state = State.ESC
            else -> {} // C0 inside CSI: ignore
        }
    }

    private fun param(list: List<Int>, i: Int, default: Int): Int {
        val v = list.getOrNull(i) ?: return default
        return if (v <= 0) default else v
    }

    private fun dispatchCsi(c: Char) {
        val p = if (params.isEmpty()) emptyList() else
            params.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        val n = param(p, 0, 1)
        wrapPending = false
        if (csiPrivate == '?') {
            when (c) {
                'h' -> p.forEach { setPrivateMode(it, true) }
                'l' -> p.forEach { setPrivateMode(it, false) }
            }
            return
        }
        if (csiPrivate != null) {
            if (c == 'c' && csiPrivate == '>') reply("\u001b[>0;276;0c")
            return
        }
        when (c) {
            '@' -> insertChars(n)
            'A' -> cursorY = (cursorY - n).coerceAtLeast(if (cursorY >= scrollTop) scrollTop else 0)
            'B', 'e' -> cursorY = (cursorY + n).coerceAtMost(if (cursorY <= scrollBottom) scrollBottom else rows - 1)
            'C', 'a' -> cursorX = (cursorX + n).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - n).coerceAtLeast(0)
            'E' -> { cursorY = (cursorY + n).coerceAtMost(scrollBottom); cursorX = 0 }
            'F' -> { cursorY = (cursorY - n).coerceAtLeast(scrollTop); cursorX = 0 }
            'G', '`' -> cursorX = (n - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val top = if (originMode) scrollTop else 0
                cursorY = (top + param(p, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorX = (param(p, 1, 1) - 1).coerceIn(0, cols - 1)
            }
            'I' -> repeat(n) { tab() }
            'Z' -> repeat(n) { backTab() }
            'J' -> eraseDisplay(p.getOrElse(0) { 0 })
            'K' -> eraseLine(p.getOrElse(0) { 0 })
            'L' -> insertLines(n)
            'M' -> deleteLines(n)
            'P' -> deleteChars(n)
            'S' -> repeat(n) { scrollUp(scrollTop, scrollBottom) }
            'T' -> repeat(n) { scrollDown(scrollTop, scrollBottom) }
            'X' -> screen[cursorY].clear(cursorX, cursorX + n, blankAttr())
            'b' -> { val ch = lastChar; repeat(n.coerceAtMost(cols)) { put(ch) } }
            'd' -> cursorY = (n - 1).coerceIn(0, rows - 1)
            'g' -> when (p.getOrElse(0) { 0 }) {
                0 -> if (cursorX < cols) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h' -> if (p.contains(4)) insertMode = true
            'l' -> if (p.contains(4)) insertMode = false
            'm' -> sgr(p)
            'n' -> when (p.getOrElse(0) { 0 }) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${cursorY + 1};${cursorX + 1}R")
            }
            'c' -> reply("\u001b[?62;22c")
            'r' -> {
                val top = param(p, 0, 1) - 1
                val bottom = param(p, 1, rows) - 1
                if (top < bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorX = 0
                    cursorY = if (originMode) scrollTop else 0
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> {}
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> appCursorKeys = on
            6 -> { originMode = on; cursorX = 0; cursorY = if (on) scrollTop else 0 }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, clear = false)
            1049 -> {
                if (on) { saveCursor(); switchScreen(true, clear = true) }
                else { switchScreen(false, clear = false); restoreCursor() }
            }
            2004 -> bracketedPaste = on
        }
    }

    private fun switchScreen(toAlt: Boolean, clear: Boolean) {
        if (toAlt == usingAlt) return
        usingAlt = toAlt
        if (toAlt && clear) alt.forEach { it.clear(0, cols, DEFAULT_ATTR) }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    // endregion

    // region SGR

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) { attr = DEFAULT_ATTR; return }
        var i = 0
        while (i < p.size) {
            when (val v = p[i]) {
                0 -> attr = DEFAULT_ATTR
                1 -> attr = attr or BOLD
                2 -> attr = attr or DIM
                3 -> attr = attr or ITALIC
                4 -> attr = attr or UNDERLINE
                7 -> attr = attr or INVERSE
                8 -> attr = attr or INVISIBLE
                9 -> attr = attr or STRIKE
                21, 22 -> attr = attr and (BOLD or DIM).inv()
                23 -> attr = attr and ITALIC.inv()
                24 -> attr = attr and UNDERLINE.inv()
                27 -> attr = attr and INVERSE.inv()
                28 -> attr = attr and INVISIBLE.inv()
                29 -> attr = attr and STRIKE.inv()
                in 30..37 -> attr = withFg(attr, v - 30)
                39 -> attr = withFg(attr, DEFAULT_COLOR)
                in 40..47 -> attr = withBg(attr, v - 40)
                49 -> attr = withBg(attr, DEFAULT_COLOR)
                in 90..97 -> attr = withFg(attr, v - 90 + 8)
                in 100..107 -> attr = withBg(attr, v - 100 + 8)
                38, 48 -> {
                    val color: Int?
                    when (p.getOrNull(i + 1)) {
                        5 -> { color = p.getOrNull(i + 2)?.coerceIn(0, 255); i += 2 }
                        2 -> {
                            val r = p.getOrElse(i + 2) { 0 }
                            val g = p.getOrElse(i + 3) { 0 }
                            val b = p.getOrElse(i + 4) { 0 }
                            color = rgbTo256(r, g, b); i += 4
                        }
                        else -> color = null
                    }
                    if (color != null) attr = if (v == 38) withFg(attr, color) else withBg(attr, color)
                }
            }
            i++
        }
    }

    // endregion

    // region screen operations

    private var lastChar = ' '

    private fun put(ch0: Char) {
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        val ch = if (graphics) DEC_GRAPHICS[ch0] ?: ch0 else ch0
        lastChar = ch
        if (wrapPending) {
            if (autoWrap) { cursorX = 0; lineFeed() }
            wrapPending = false
        }
        val line = screen[cursorY]
        if (insertMode) {
            System.arraycopy(line.chars, cursorX, line.chars, cursorX + 1, cols - cursorX - 1)
            System.arraycopy(line.attrs, cursorX, line.attrs, cursorX + 1, cols - cursorX - 1)
        }
        line.chars[cursorX] = ch
        line.attrs[cursorX] = attr
        line.version++
        if (cursorX == cols - 1) wrapPending = true else cursorX++
    }

    private fun blankAttr() = withFg(attr and FLAGS_MASK.inv(), DEFAULT_COLOR)

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == scrollBottom) scrollUp(scrollTop, scrollBottom)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == scrollTop) scrollDown(scrollTop, scrollBottom)
        else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(top: Int, bottom: Int) {
        val buf = if (usingAlt) alt else main
        val removed = buf.removeAt(top)
        if (!usingAlt && top == 0) {
            // Reuse the renderer's up-to-date copy if it has one, so the UI keeps its cached styled text.
            scrollback.addLast(removed.frozen?.takeIf { removed.frozenVersion == removed.version } ?: removed.also { it.frozen = null })
            while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            val blank = blankAttr()
            buf.add(bottom, newLine(cols).also { if (blank != DEFAULT_ATTR) it.clear(0, cols, blank) })
        } else {
            removed.clear(0, cols, blankAttr())
            buf.add(bottom, removed)
        }
    }

    private fun scrollDown(top: Int, bottom: Int) {
        val buf = if (usingAlt) alt else main
        val removed = buf.removeAt(bottom)
        removed.clear(0, cols, blankAttr())
        buf.add(top, removed)
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) { scrollDown(cursorY, scrollBottom) }
        cursorX = 0
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        val buf = if (usingAlt) alt else main
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            val removed = buf.removeAt(cursorY)
            removed.clear(0, cols, blankAttr())
            buf.add(scrollBottom, removed)
        }
        cursorX = 0
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        System.arraycopy(line.chars, cursorX, line.chars, cursorX + count, cols - cursorX - count)
        System.arraycopy(line.attrs, cursorX, line.attrs, cursorX + count, cols - cursorX - count)
        line.clear(cursorX, cursorX + count, blankAttr())
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        System.arraycopy(line.chars, cursorX + count, line.chars, cursorX, cols - cursorX - count)
        System.arraycopy(line.attrs, cursorX + count, line.attrs, cursorX, cols - cursorX - count)
        line.clear(cols - count, cols, blankAttr())
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY]
        when (mode) {
            0 -> line.clear(cursorX, cols, blankAttr())
            1 -> line.clear(0, cursorX + 1, blankAttr())
            2 -> line.clear(0, cols, blankAttr())
        }
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) s[y].clear(0, cols, blankAttr()) }
            1 -> { eraseLine(1); for (y in 0 until cursorY) s[y].clear(0, cols, blankAttr()) }
            2 -> {
                // Like xterm, push the visible screen into scrollback on "clear" so history isn't lost.
                if (!usingAlt) {
                    val lastUsed = s.indexOfLast { it.text().isNotEmpty() }
                    for (y in 0..lastUsed) {
                        scrollback.addLast(newLine(cols).also { l ->
                            s[y].chars.copyInto(l.chars); s[y].attrs.copyInto(l.attrs)
                        })
                    }
                    while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
                }
                s.forEach { it.clear(0, cols, blankAttr()) }
            }
            3 -> scrollback.clear()
        }
    }

    private fun tab() {
        var x = cursorX + 1
        while (x < cols - 1 && !tabStops[x]) x++
        cursorX = x.coerceAtMost(cols - 1)
    }

    private fun backTab() {
        var x = cursorX - 1
        while (x > 0 && !tabStops[x]) x--
        cursorX = x.coerceAtLeast(0)
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY; savedAttr = attr
    }

    private fun restoreCursor() {
        cursorX = savedX.coerceIn(0, cols - 1)
        cursorY = savedY.coerceIn(0, rows - 1)
        attr = savedAttr
        wrapPending = false
    }

    private fun reset() {
        main.forEach { it.clear(0, cols, DEFAULT_ATTR) }
        alt.forEach { it.clear(0, cols, DEFAULT_ATTR) }
        usingAlt = false
        attr = DEFAULT_ATTR
        cursorX = 0; cursorY = 0
        scrollTop = 0; scrollBottom = rows - 1
        appCursorKeys = false; originMode = false; insertMode = false; autoWrap = true
        cursorVisible = true; g0Graphics = false; g1Graphics = false; shiftOut = false
    }

    // endregion

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        if (newCols < 2 || newRows < 2) return
        for (buf in listOf(main, alt)) {
            buf.forEach { it.resize(newCols) }
        }
        // Scrollback rows keep their original width: they must stay immutable, and narrowing them would lose text.
        // Shrinking: move lines off the top (into scrollback) so the cursor stays visible.
        while (main.size > newRows) {
            if (cursorY > 0 && main.size - 1 >= cursorY + 1 && main.last().text().isEmpty()) {
                main.removeAt(main.size - 1)
            } else {
                scrollback.addLast(main.removeAt(0))
                if (!usingAlt) cursorY = (cursorY - 1).coerceAtLeast(0)
            }
        }
        while (main.size < newRows) main.add(newLine(newCols))
        while (alt.size > newRows) alt.removeAt(alt.size - 1)
        while (alt.size < newRows) alt.add(newLine(newCols))
        cols = newCols
        rows = newRows
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        scrollTop = 0
        scrollBottom = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        wrapPending = false
        version++
    }

    companion object {
        const val DEFAULT_COLOR = 256
        const val BOLD = 1 shl 18
        const val DIM = 1 shl 19
        const val ITALIC = 1 shl 20
        const val UNDERLINE = 1 shl 21
        const val INVERSE = 1 shl 22
        const val INVISIBLE = 1 shl 23
        const val STRIKE = 1 shl 24
        private const val FLAGS_MASK = (0x7F shl 18)
        const val DEFAULT_ATTR = DEFAULT_COLOR or (DEFAULT_COLOR shl 9)
        const val MAX_SCROLLBACK = 3000

        fun fg(attr: Int) = attr and 0x1FF
        fun bg(attr: Int) = (attr shr 9) and 0x1FF
        private fun withFg(attr: Int, c: Int) = (attr and 0x1FF.inv()) or c
        private fun withBg(attr: Int, c: Int) = (attr and (0x1FF shl 9).inv()) or (c shl 9)

        private fun rgbTo256(r: Int, g: Int, b: Int): Int {
            fun q(v: Int) = if (v < 48) 0 else if (v < 115) 1 else (v - 35) / 40
            return 16 + 36 * q(r).coerceIn(0, 5) + 6 * q(g).coerceIn(0, 5) + q(b).coerceIn(0, 5)
        }

        /** DEC Special Graphics: line-drawing characters used by whiptail, dialog, mc. */
        private val DEC_GRAPHICS = mapOf(
            '`' to '◆', 'a' to '▒', 'f' to '°', 'g' to '±', 'j' to '┘', 'k' to '┐', 'l' to '┌',
            'm' to '└', 'n' to '┼', 'o' to '⎺', 'p' to '⎻', 'q' to '─', 'r' to '⎼', 's' to '⎽',
            't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬', 'x' to '│', 'y' to '≤', 'z' to '≥',
            '{' to 'π', '|' to '≠', '}' to '£', '~' to '·',
        )
    }
}
