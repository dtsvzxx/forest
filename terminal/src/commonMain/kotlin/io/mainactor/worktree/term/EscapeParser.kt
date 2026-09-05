package io.mainactor.worktree.term

/**
 * The parameters of one control sequence.
 *
 * Handed to the sink and reused for the next sequence — a terminal under load parses thousands of
 * these a second, and none of them should reach the collector. Read what you need during the call;
 * do not keep it.
 *
 * Sub-parameters are the modern part. `CSI 38:2::255:0:0 m` writes one colour as several values
 * joined by colons, and `CSI 4:3 m` is a curly underline, so a flat list would lose which values
 * belong together. They are stored flat all the same, with [continues] marking the ones that
 * carried on the value before them — which is how a consumer walks them without allocating.
 */
class Params internal constructor() {

    private val values = IntArray(MAX)
    private val subParam = BooleanArray(MAX)

    var size: Int = 0
        private set

    /** True when parameter [index] was joined to the one before it with a colon. */
    fun continues(index: Int): Boolean = index in 1 until size && subParam[index]

    /**
     * Parameter [index], or [ifAbsent] where it was left out.
     *
     * Not an operator: `CSI ; 5 H` means "row default, column 5", so almost every caller has a
     * default to pass, and a named argument reads better at the call site than a bare second index.
     */
    fun at(index: Int, ifAbsent: Int = 0): Int =
        if (index < 0 || index >= size || values[index] == ABSENT) ifAbsent else values[index]

    /** True when the sequence carried no parameters at all, as in `CSI m`. */
    val isEmpty: Boolean get() = size == 0

    internal fun clear() {
        size = 0
    }

    /**
     * A separator: the parameter before it is finished, whether or not it had any digits.
     *
     * The empty slot matters. `CSI ; 5 H` is "row default, column 5" and `38:2::255:0:0` has an
     * empty third field — a separator that merely armed the *next* value would collapse both into
     * something the sequence does not say.
     */
    internal fun separator(isSub: Boolean): Boolean {
        if (size == 0 && !push(isSub = false)) return false
        return push(isSub)
    }

    private fun push(isSub: Boolean): Boolean {
        if (size >= MAX) return false
        values[size] = ABSENT
        subParam[size] = isSub
        size++
        return true
    }

    internal fun addDigit(digit: Int): Boolean {
        if (size == 0 && !push(isSub = false)) return false
        val current = if (values[size - 1] == ABSENT) 0 else values[size - 1]
        // xterm clamps rather than wrapping; a pasted number must not turn into a small one.
        values[size - 1] = if (current > MAX_VALUE / 10) MAX_VALUE else minOf(current * 10 + digit, MAX_VALUE)
        return true
    }

    override fun toString(): String = buildString {
        for (index in 0 until size) {
            if (index > 0) append(if (continues(index)) ':' else ';')
            if (values[index] != ABSENT) append(values[index])
        }
    }

    internal companion object {
        /** xterm stops at 30; a few more costs nothing and a sequence past this is garbage anyway. */
        const val MAX = 32
        const val MAX_VALUE = 65535
        const val ABSENT = -1
    }
}

/**
 * What a parsed stream turns into.
 *
 * Everything here is called from the parser's own thread with no allocation, and none of the
 * arguments outlive the call.
 */
interface ParserSink {

    /** A character to put on the screen. */
    fun print(codePoint: Int)

    /** A C0 or C1 control: line feed, carriage return, bell, tab. */
    fun execute(control: Int)

    /** `ESC` with optional intermediates, as in `ESC ( 0` or `ESC =`. */
    fun escape(intermediates: Int, intermediateCount: Int, final: Int)

    /** `CSI`, the bulk of a terminal's vocabulary. [private] is `?`, `>` or `=`, or 0. */
    fun csi(params: Params, intermediates: Int, intermediateCount: Int, private: Int, final: Int)

    /** An operating-system command: the window title, a hyperlink, the palette. */
    fun osc(data: String)

    /** A device-control string has begun; its body arrives through [dcsPut]. */
    fun dcsHook(params: Params, intermediates: Int, intermediateCount: Int, final: Int)

    fun dcsPut(codePoint: Int)

    fun dcsUnhook()
}

/**
 * The escape-sequence state machine, as described by Paul Williams' table for the DEC parsers.
 *
 * Written to the table rather than to intuition, and that is the whole point: the table's value is
 * not that it parses `CSI 1 m` — anything does — but that it *defines* what happens to malformed
 * input, which is what a terminal actually spends its ill-defined moments on. A shell that cats a
 * binary, a program killed halfway through a sequence, a stream that resumes mid-escape: each one
 * has a defined resting place here, and none of them can leave the parser in a state where ordinary
 * text stops appearing.
 *
 * Two deliberate departures from the 1970s table, both matching xterm and every modern terminal:
 *
 * - **A colon inside CSI parameters is a separator, not an error.** The table sends `3A` to
 *   CSI_IGNORE; that would throw away every truecolour SGR written in the `38:2:…` form.
 * - **`BEL` ends an OSC string**, as well as `ST`. Nothing writes the standards-compliant form
 *   alone, and a terminal that waits for `ST` hangs on the first `\e]0;title\a` a shell prompt
 *   emits.
 */
class EscapeParser {

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        DCS_ENTRY,
        DCS_PARAM,
        DCS_INTERMEDIATE,
        DCS_PASSTHROUGH,
        DCS_IGNORE,
        OSC_STRING,
        SOS_PM_APC_STRING,
    }

    private var state = State.GROUND
    private val params = Params()

    /** Packed into an Int: at most two intermediates, one byte each, and neither is ever zero. */
    private var intermediates = 0
    private var intermediateCount = 0
    private var private = 0
    private var overflowed = false

    private val string = StringBuilder()

    /** Feeds one code point. Everything the sink is told happens inside this call. */
    fun advance(codePoint: Int, sink: ParserSink) {
        // The "anywhere" transitions, which outrank whatever state we are in — this is what makes
        // an interrupted sequence recoverable rather than a terminal that stops responding.
        when (codePoint) {
            0x18, 0x1A -> {
                abortString(sink)
                sink.execute(codePoint)
                state = State.GROUND
                return
            }
            0x1B -> {
                // Not an abort: `ESC \` is `ST`, the standard terminator, so leaving an OSC or a
                // DCS by way of ESC has to hand over what was collected. The table says the same —
                // the string states' exit action runs however they are left.
                endString(sink)
                clear()
                state = State.ESCAPE
                return
            }
            0x9C -> {
                endString(sink)
                state = State.GROUND
                return
            }
            0x90 -> { abortString(sink); clear(); state = State.DCS_ENTRY; return }
            0x9B -> { abortString(sink); clear(); state = State.CSI_ENTRY; return }
            0x9D -> { abortString(sink); clear(); string.clear(); state = State.OSC_STRING; return }
            0x98, 0x9E, 0x9F -> { abortString(sink); state = State.SOS_PM_APC_STRING; return }
            in 0x80..0x8F, in 0x91..0x97, 0x99, 0x9A -> {
                if (state != State.OSC_STRING && state != State.DCS_PASSTHROUGH &&
                    state != State.SOS_PM_APC_STRING
                ) {
                    sink.execute(codePoint)
                    state = State.GROUND
                    return
                }
            }
        }

        when (state) {
            State.GROUND -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                else -> sink.print(codePoint)
            }

            State.ESCAPE -> when (codePoint) {
                in 0x20..0x2F -> { collect(codePoint); state = State.ESCAPE_INTERMEDIATE }
                0x50 -> { clear(); state = State.DCS_ENTRY }
                0x58, 0x5E, 0x5F -> state = State.SOS_PM_APC_STRING
                0x5B -> { clear(); state = State.CSI_ENTRY }
                0x5D -> { clear(); string.clear(); state = State.OSC_STRING }
                0x7F -> Unit
                else -> when {
                    codePoint.isC0Control -> sink.execute(codePoint)
                    codePoint in 0x30..0x7E -> {
                        sink.escape(intermediates, intermediateCount, codePoint)
                        state = State.GROUND
                    }
                    else -> state = State.GROUND
                }
            }

            State.ESCAPE_INTERMEDIATE -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                codePoint in 0x20..0x2F -> collect(codePoint)
                codePoint == 0x7F -> Unit
                codePoint in 0x30..0x7E -> {
                    sink.escape(intermediates, intermediateCount, codePoint)
                    state = State.GROUND
                }
                else -> state = State.GROUND
            }

            State.CSI_ENTRY -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                codePoint == 0x7F -> Unit
                codePoint in 0x20..0x2F -> { collect(codePoint); state = State.CSI_INTERMEDIATE }
                codePoint in 0x3C..0x3F -> { private = codePoint; state = State.CSI_PARAM }
                codePoint in 0x30..0x39 || codePoint == 0x3B || codePoint == 0x3A -> {
                    state = State.CSI_PARAM
                    parameter(codePoint)
                }
                codePoint in 0x40..0x7E -> dispatchCsi(sink, codePoint)
                else -> state = State.CSI_IGNORE
            }

            State.CSI_PARAM -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                codePoint == 0x7F -> Unit
                codePoint in 0x30..0x39 || codePoint == 0x3B || codePoint == 0x3A -> parameter(codePoint)
                // A private marker after the parameters have started is malformed.
                codePoint in 0x3C..0x3F -> state = State.CSI_IGNORE
                codePoint in 0x20..0x2F -> { collect(codePoint); state = State.CSI_INTERMEDIATE }
                codePoint in 0x40..0x7E -> dispatchCsi(sink, codePoint)
                else -> state = State.CSI_IGNORE
            }

            State.CSI_INTERMEDIATE -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                codePoint in 0x20..0x2F -> collect(codePoint)
                codePoint == 0x7F -> Unit
                codePoint in 0x30..0x3F -> state = State.CSI_IGNORE
                codePoint in 0x40..0x7E -> dispatchCsi(sink, codePoint)
                else -> state = State.CSI_IGNORE
            }

            State.CSI_IGNORE -> when {
                codePoint.isC0Control -> sink.execute(codePoint)
                codePoint in 0x40..0x7E -> state = State.GROUND
                else -> Unit
            }

            State.DCS_ENTRY -> when {
                codePoint.isC0Control || codePoint == 0x7F -> Unit
                codePoint in 0x20..0x2F -> { collect(codePoint); state = State.DCS_INTERMEDIATE }
                codePoint in 0x3C..0x3F -> { private = codePoint; state = State.DCS_PARAM }
                codePoint in 0x30..0x39 || codePoint == 0x3B || codePoint == 0x3A -> {
                    state = State.DCS_PARAM
                    parameter(codePoint)
                }
                codePoint in 0x40..0x7E -> hook(sink, codePoint)
                else -> state = State.DCS_IGNORE
            }

            State.DCS_PARAM -> when {
                codePoint.isC0Control || codePoint == 0x7F -> Unit
                codePoint in 0x30..0x39 || codePoint == 0x3B || codePoint == 0x3A -> parameter(codePoint)
                codePoint in 0x3C..0x3F -> state = State.DCS_IGNORE
                codePoint in 0x20..0x2F -> { collect(codePoint); state = State.DCS_INTERMEDIATE }
                codePoint in 0x40..0x7E -> hook(sink, codePoint)
                else -> state = State.DCS_IGNORE
            }

            State.DCS_INTERMEDIATE -> when {
                codePoint.isC0Control || codePoint == 0x7F -> Unit
                codePoint in 0x20..0x2F -> collect(codePoint)
                codePoint in 0x30..0x3F -> state = State.DCS_IGNORE
                codePoint in 0x40..0x7E -> hook(sink, codePoint)
                else -> state = State.DCS_IGNORE
            }

            State.DCS_PASSTHROUGH -> when {
                codePoint == 0x7F -> Unit
                else -> sink.dcsPut(codePoint)
            }

            State.DCS_IGNORE -> Unit

            State.OSC_STRING -> when (codePoint) {
                // xterm's terminator, and in practice the only one a shell prompt uses.
                0x07 -> { endString(sink); state = State.GROUND }
                else -> if (!codePoint.isC0Control && string.length < MAX_STRING) {
                    string.appendCodePoint(codePoint)
                }
            }

            State.SOS_PM_APC_STRING -> Unit
        }
    }

    /** Where the parser is, for a test that wants to prove a malformed sequence was recovered from. */
    internal val inGroundState: Boolean get() = state == State.GROUND

    private fun parameter(codePoint: Int) {
        if (overflowed) return
        val accepted = when (codePoint) {
            0x3B -> params.separator(isSub = false)
            0x3A -> params.separator(isSub = true)
            else -> params.addDigit(codePoint - 0x30)
        }
        // Past the limit the sequence is meaningless; keep consuming it but produce nothing.
        if (!accepted) overflowed = true
    }

    private fun collect(codePoint: Int) {
        if (intermediateCount < MAX_INTERMEDIATES) {
            intermediates = intermediates or (codePoint shl (8 * intermediateCount))
            intermediateCount++
        } else {
            overflowed = true
        }
    }

    private fun dispatchCsi(sink: ParserSink, final: Int) {
        if (!overflowed) sink.csi(params, intermediates, intermediateCount, private, final)
        state = State.GROUND
    }

    private fun hook(sink: ParserSink, final: Int) {
        if (!overflowed) {
            sink.dcsHook(params, intermediates, intermediateCount, final)
            state = State.DCS_PASSTHROUGH
        } else {
            state = State.DCS_IGNORE
        }
    }

    /** `ST` or `BEL`: hands over whatever string state we are in and leaves it. */
    private fun endString(sink: ParserSink) {
        when (state) {
            State.OSC_STRING -> sink.osc(string.toString())
            State.DCS_PASSTHROUGH -> sink.dcsUnhook()
            else -> Unit
        }
        string.clear()
    }

    /** `CAN` and `SUB` mean cancel, so they throw a half-collected string away rather than deliver it. */
    private fun abortString(sink: ParserSink) {
        if (state == State.DCS_PASSTHROUGH) sink.dcsUnhook()
        string.clear()
    }

    private fun clear() {
        params.clear()
        intermediates = 0
        intermediateCount = 0
        private = 0
        overflowed = false
    }

    private companion object {
        const val MAX_INTERMEDIATES = 2

        /** A title or a hyperlink past this is not one; the cap is what stops a garbage flood. */
        const val MAX_STRING = 8192
    }
}

/**
 * The C0 range, minus the two the "anywhere" rules already took.
 *
 * `ESC` (0x1B), `CAN` (0x18) and `SUB` (0x1A) never reach here — they are handled before the state
 * is looked at, because a sequence has to be interruptible from any state.
 */
private val Int.isC0Control: Boolean
    get() = this in 0x00..0x17 || this == 0x19 || this in 0x1C..0x1F
