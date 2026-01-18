package xctestleaks

import java.time.Instant

/**
 * Simple string tokenizer that splits by whitespace without using regex.
 * Provides sequential token access for parsing leak report lines.
 */
class StringTokenizer(private val input: String) {
    private var position = 0

    /** Skip any whitespace at the current position. */
    fun skipWhitespace() {
        while (position < input.length && input[position].isWhitespace()) {
            position++
        }
    }

    /** Check if there are more tokens available. */
    fun hasMoreTokens(): Boolean {
        skipWhitespace()
        return position < input.length
    }

    /** Read the next whitespace-delimited token. */
    fun nextToken(): String {
        skipWhitespace()
        if (position >= input.length) return ""

        val start = position
        while (position < input.length && !input[position].isWhitespace()) {
            position++
        }
        return input.substring(start, position)
    }

    /** Peek at the next token without consuming it. */
    fun peekToken(): String {
        val savedPosition = position
        val token = nextToken()
        position = savedPosition
        return token
    }

    /** Read the rest of the string from the current position. */
    fun rest(): String {
        skipWhitespace()
        return if (position < input.length) input.substring(position) else ""
    }

    /** Get the remaining unparsed portion without skipping whitespace. */
    fun remaining(): String {
        return if (position < input.length) input.substring(position) else ""
    }

    /** Reset to start. */
    fun reset() {
        position = 0
    }
}

/**
 * Splits a string by whitespace without regex.
 * Returns a list of non-empty tokens.
 */
fun String.splitByWhitespace(): List<String> {
    val tokens = mutableListOf<String>()
    val tokenizer = StringTokenizer(this)
    while (tokenizer.hasMoreTokens()) {
        tokens.add(tokenizer.nextToken())
    }
    return tokens
}

/**
 * Tokens representing lines or sections in a `leaks` output.
 * Adjust and extend as you learn more patterns from real output.
 */
sealed interface LeaksToken {
    val rawLine: String

    data class Header(
        override val rawLine: String,
        val processInfo: String,
    ) : LeaksToken

    /**
     * Generic old-style leak line (kept for future use / other formats).
     * Not used for the root-retain-cycles format.
     */
    data class LeakLine(
        override val rawLine: String,
        val address: String?,
        val sizeBytes: Long?,
        val type: String?,
    ) : LeaksToken

    data class Summary(
        override val rawLine: String,
        val key: String,
        val value: String,
    ) : LeaksToken

    data class StackFrame(
        override val rawLine: String,
        val frame: String,
    ) : LeaksToken

    /**
     * A "ROOT LEAK" entry like:
     *   32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
     */
    data class RootLeak(
        override val rawLine: String,
        val count: Int,
        val sizeHumanReadable: String,
        val typeName: String,
        val address: String,
        val instanceSizeBytes: Int,
    ) : LeaksToken

    /**
     * Child entry under a ROOT LEAK with hierarchy depth tracked.
     *   13 (1.42K) windowManager --> <MockWindowManager 0x...> [48]
     *   1 (112 bytes) logger --> <Swift closure context 0x...> [112]
     *   1 (32 bytes) 0x600000231380 [32]  (bare address, no field name)
     */
    data class RootLeakChild(
        override val rawLine: String,
        val depth: Int,
        val count: Int,
        val sizeHumanReadable: String,
        val fieldName: String,
        val fieldOffset: Int?,
        val typeName: String,
        val address: String,
        val instanceSizeBytes: Int,
    ) : LeaksToken

    /**
     * The TOTAL line: "875 (101K) << TOTAL >>"
     */
    data class TotalLine(
        override val rawLine: String,
        val count: Int,
        val sizeHumanReadable: String,
    ) : LeaksToken

    data class Unknown(
        override val rawLine: String,
    ) : LeaksToken
}

/**
 * Direct child metadata under a ROOT LEAK.
 */
data class RootLeakChildInfo(
    val count: Int,
    val sizeHumanReadable: String,
    val fieldName: String,
    val typeName: String,
    val instanceSizeBytes: Int,
)

/**
 * A single suspicious leak instance extracted from the output.
 *
 * For the root-retain-cycles format, these fields are populated:
 *  - [rootCount], [rootSizeHumanReadable], [rootTypeName], [rootInstanceSizeBytes]
 *  - [children] contains the direct children like windowManager, recentSearchProvider, logger, ...
 *
 * The original fields [address], [sizeBytes], [type], [stackTrace] are kept for backwards
 * compatibility and may be null/empty for this format.
 */
data class LeakInstance(
    val address: String?,
    val sizeBytes: Long?,
    val type: String?,
    val stackTrace: List<String>,
    val rawLines: List<String>,
    val rootCount: Int? = null,
    val rootSizeHumanReadable: String? = null,
    val rootTypeName: String? = null,
    val rootInstanceSizeBytes: Int? = null,
    val children: List<RootLeakChildInfo> = emptyList(),
)

/**
 * Structured representation of one full leaks report.
 */
data class LeaksReport(
    val params: LeaksInvocationParams,
    val invocationTime: Instant,
    val leaks: List<LeakInstance>,
    val summary: Map<String, String>,
    val rawOutput: String,
)

/**
 * Parser abstraction: takes raw `leaks` stdout and turns it into [LeaksReport].
 */
interface LeaksParser {
    fun parse(rawResult: LeaksRawResult): LeaksReport
}

/**
 * Token-based parser for `leaks` output.
 *
 * For the "root retain cycles" style output, it recognizes:
 *
 *  - ROOT LEAK lines:
 *        32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
 *  - direct child lines under a root:
 *        13 (1.42K) windowManager --> <MockWindowManager 0x600000c6cc90> [48]
 *        9 (1.39K) recentSearchProvider --> <DefaultRecentSearchProvider 0x...> [112]
 *        1 (112 bytes) logger --> <Swift closure context 0x...> [112]
 *
 * It still supports the old Header / Summary tokens for the metadata at the top.
 */
class TokenBasedLeaksParser : LeaksParser {

    override fun parse(rawResult: LeaksRawResult): LeaksReport {
        val tokens = tokenize(rawResult.rawOutput)

        val leaks = mutableListOf<LeakInstance>()
        val summary = mutableMapOf<String, String>()

        var currentRawLines = mutableListOf<String>()
        var currentChildren = mutableListOf<RootLeakChildInfo>()
        var currentRootCount: Int? = null
        var currentRootSizeHuman: String? = null
        var currentRootTypeName: String? = null
        var currentRootInstanceSize: Int? = null

        fun flushCurrentRootLeak() {
            if (currentRootCount != null && currentRootTypeName != null) {
                leaks += LeakInstance(
                    address = null,
                    sizeBytes = null,
                    type = currentRootTypeName,
                    stackTrace = emptyList(),
                    rawLines = currentRawLines.toList(),
                    rootCount = currentRootCount,
                    rootSizeHumanReadable = currentRootSizeHuman,
                    rootTypeName = currentRootTypeName,
                    rootInstanceSizeBytes = currentRootInstanceSize,
                    children = currentChildren.toList(),
                )
            }
            currentRawLines = mutableListOf()
            currentChildren = mutableListOf()
            currentRootCount = null
            currentRootSizeHuman = null
            currentRootTypeName = null
            currentRootInstanceSize = null
        }

        for (token in tokens) {
            when (token) {
                is LeaksToken.Header -> {
                    flushCurrentRootLeak()
                    currentRawLines.add(token.rawLine)
                }

                is LeaksToken.Summary -> {
                    flushCurrentRootLeak()
                    summary[token.key] = token.value
                }

                is LeaksToken.RootLeak -> {
                    flushCurrentRootLeak()
                    currentRootCount = token.count
                    currentRootSizeHuman = token.sizeHumanReadable
                    currentRootTypeName = token.typeName
                    currentRootInstanceSize = token.instanceSizeBytes
                    currentRawLines.add(token.rawLine)
                }

                is LeaksToken.RootLeakChild -> {
                    if (currentRootCount != null) {
                        currentChildren += RootLeakChildInfo(
                            count = token.count,
                            sizeHumanReadable = token.sizeHumanReadable,
                            fieldName = token.fieldName,
                            typeName = token.typeName,
                            instanceSizeBytes = token.instanceSizeBytes,
                        )
                        currentRawLines.add(token.rawLine)
                    }
                }

                is LeaksToken.LeakLine -> {
                    if (currentRootCount != null) {
                        currentRawLines.add(token.rawLine)
                    }
                }

                is LeaksToken.StackFrame -> {
                    if (currentRootCount != null) {
                        currentRawLines.add(token.rawLine)
                    }
                }

                is LeaksToken.TotalLine -> {
                    flushCurrentRootLeak()
                }

                is LeaksToken.Unknown -> {
                    if (currentRootCount != null) {
                        currentRawLines.add(token.rawLine)
                    }
                }
            }
        }

        flushCurrentRootLeak()

        return LeaksReport(
            params = rawResult.params,
            invocationTime = rawResult.invocationTime,
            leaks = leaks.toList(),
            summary = summary.toMap(),
            rawOutput = rawResult.rawOutput,
        )
    }

    private fun tokenize(output: String): List<LeaksToken> {
        val tokens = mutableListOf<LeaksToken>()

        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val token: LeaksToken = when {
                // ROOT LEAK line identified by the marker "ROOT LEAK:"
                "ROOT LEAK:" in trimmed -> parseRootLeak(line, trimmed)

                // TOTAL line: "875 (101K) << TOTAL >>"
                "<< TOTAL >>" in trimmed -> parseTotalLine(line, trimmed)

                // Child under a ROOT LEAK: has "-->" but no "ROOT LEAK:"
                "-->" in trimmed -> parseRootLeakChild(line)

                // Bare child (no arrow): starts with digits and has address pattern
                // E.g. "1 (32 bytes) 0x600000231380 [32]"
                looksLikeBareChild(trimmed) -> parseBareChild(line)

                // Header line like:
                //   Process 35988: 875 leaks for 103824 total leaked bytes.
                trimmed.startsWith("Process ") -> LeaksToken.Header(
                    rawLine = line,
                    processInfo = trimmed,
                )

                // Metadata / summary lines like:
                //   Process:         Client [35988]
                //   Physical footprint:         82.0M
                ":" in trimmed -> {
                    val idx = trimmed.indexOf(':')
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    LeaksToken.Summary(
                        rawLine = line,
                        key = key,
                        value = value,
                    )
                }

                // Old-style stack frame (kept for completeness).
                trimmed.firstOrNull()?.isDigit() == true -> LeaksToken.StackFrame(
                    rawLine = line,
                    frame = trimmed,
                )

                else -> LeaksToken.Unknown(rawLine = line)
            }

            tokens += token
        }

        return tokens
    }

    /**
     * Calculate indentation depth from leading whitespace.
     * Each 3 spaces = 1 level of depth.
     */
    private fun calculateDepth(line: String): Int {
        var spaces = 0
        for (c in line) {
            if (c == ' ') spaces++
            else break
        }
        return spaces / 3
    }

    /**
     * Check if a line looks like a bare child (no "-->" arrow).
     * E.g. "1 (32 bytes) 0x600000231380 [32]"
     */
    private fun looksLikeBareChild(trimmed: String): Boolean {
        return trimmed.firstOrNull()?.isDigit() == true &&
                '(' in trimmed && ')' in trimmed &&
                '[' in trimmed && ']' in trimmed &&
                "0x" in trimmed
    }

    /**
     * Parse a TOTAL line: "875 (101K) << TOTAL >>"
     */
    private fun parseTotalLine(rawLine: String, trimmed: String): LeaksToken.TotalLine {
        val markerIndex = trimmed.indexOf("<< TOTAL >>")
        val left = trimmed.substring(0, markerIndex).trim()
        val (count, sizeHuman) = parseCountAndSize(left)
        return LeaksToken.TotalLine(
            rawLine = rawLine,
            count = count,
            sizeHumanReadable = sizeHuman,
        )
    }

    /**
     * Parse a ROOT LEAK line using simple string operations.
     *
     * Example:
     *   "32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]"
     */
    private fun parseRootLeak(rawLine: String, trimmed: String): LeaksToken.RootLeak {
        val markerIndex = trimmed.indexOf("ROOT LEAK:")
        val left = trimmed.substring(0, markerIndex).trim()
        val right = trimmed.substring(markerIndex + "ROOT LEAK:".length).trim()

        val (count, sizeHuman) = parseCountAndSize(left)
        val (typeName, address) = parseTypeAndAddress(right)
        val instanceSizeBytes = parseInstanceSize(right)

        return LeaksToken.RootLeak(
            rawLine = rawLine,
            count = count,
            sizeHumanReadable = sizeHuman,
            typeName = typeName,
            address = address,
            instanceSizeBytes = instanceSizeBytes,
        )
    }

    /**
     * Parse a child line with "-->" arrow.
     *
     * Examples:
     *   "13 (1.42K) windowManager --> <MockWindowManager 0x600000c6cc90> [48]"
     *   "1 (112 bytes) logger --> <Swift closure context 0x60000291bb10> [112]"
     *   "2 (176 bytes) fileManager + 16 --> <Swift closure context 0x600001759600> [64]"
     */
    private fun parseRootLeakChild(rawLine: String): LeaksToken.RootLeakChild {
        val depth = calculateDepth(rawLine)
        val trimmed = rawLine.trim()
        val arrowIndex = trimmed.indexOf("-->")
        val left = trimmed.substring(0, arrowIndex).trim()
        val right = trimmed.substring(arrowIndex + "-->".length).trim()

        val (count, sizeHuman) = parseCountAndSize(left)
        val (fieldName, fieldOffset) = parseFieldNameAndOffset(left)
        val (typeName, address) = parseTypeAndAddress(right)
        val instanceSizeBytes = parseInstanceSize(right)

        return LeaksToken.RootLeakChild(
            rawLine = rawLine,
            depth = depth,
            count = count,
            sizeHumanReadable = sizeHuman,
            fieldName = fieldName,
            fieldOffset = fieldOffset,
            typeName = typeName,
            address = address,
            instanceSizeBytes = instanceSizeBytes,
        )
    }

    /**
     * Parse a bare child line (no arrow, just address).
     * E.g. "1 (32 bytes) 0x600000231380 [32]"
     */
    private fun parseBareChild(rawLine: String): LeaksToken.RootLeakChild {
        val depth = calculateDepth(rawLine)
        val trimmed = rawLine.trim()

        val (count, sizeHuman) = parseCountAndSize(trimmed)

        // Extract address (0x...)
        val addressStart = trimmed.indexOf("0x")
        val addressEnd = findAddressEnd(trimmed, addressStart)
        val address = if (addressStart != -1 && addressEnd > addressStart) {
            trimmed.substring(addressStart, addressEnd)
        } else {
            ""
        }

        val instanceSizeBytes = parseInstanceSize(trimmed)

        return LeaksToken.RootLeakChild(
            rawLine = rawLine,
            depth = depth,
            count = count,
            sizeHumanReadable = sizeHuman,
            fieldName = "",
            fieldOffset = null,
            typeName = "",
            address = address,
            instanceSizeBytes = instanceSizeBytes,
        )
    }

    /**
     * Find where an address ends (at whitespace or bracket).
     */
    private fun findAddressEnd(s: String, start: Int): Int {
        if (start == -1) return -1
        var i = start
        while (i < s.length && !s[i].isWhitespace() && s[i] != '[') {
            i++
        }
        return i
    }

    /**
     * Parse the "<count> (<sizeHuman>)" prefix using string operations.
     *
     * Examples:
     *   "32 (3.66K)"
     *   "13 (1.42K) windowManager"
     *   "1 (112 bytes) logger"
     */
    private fun parseCountAndSize(left: String): Pair<Int, String> {
        val tokenizer = StringTokenizer(left)
        val countToken = tokenizer.nextToken()
        val count = countToken.toIntOrNull() ?: 0

        val openIdx = left.indexOf('(')
        val closeIdx = left.indexOf(')', startIndex = maxOf(0, openIdx + 1))
        val sizeHuman = if (openIdx != -1 && closeIdx != -1 && closeIdx > openIdx) {
            left.substring(openIdx + 1, closeIdx).trim()
        } else {
            ""
        }

        return count to sizeHuman
    }

    /**
     * Parse field name and optional offset from the left side of "-->".
     *
     * Examples:
     *   "13 (1.42K) windowManager" -> ("windowManager", null)
     *   "11 (1.14K) __strong wrappedManager" -> ("__strong wrappedManager", null)
     *   "2 (176 bytes) fileManager + 16" -> ("fileManager", 16)
     *   "1 (112 bytes)  + 8" -> ("", 8)
     */
    private fun parseFieldNameAndOffset(left: String): Pair<String, Int?> {
        val closeParenIdx = left.indexOf(')')
        if (closeParenIdx == -1) return "" to null

        val afterParen = left.substring(closeParenIdx + 1).trim()
        if (afterParen.isEmpty()) return "" to null

        val plusIdx = afterParen.indexOf('+')
        if (plusIdx == -1) {
            // No offset, field name is everything after the closing paren
            return afterParen to null
        }

        // Has offset: "fieldName + 16" or " + 8"
        val beforePlus = afterParen.substring(0, plusIdx).trim()
        val afterPlus = afterParen.substring(plusIdx + 1).trim()

        val offset = StringTokenizer(afterPlus).nextToken().toIntOrNull()

        return beforePlus to offset
    }

    /**
     * Parse type name and address from inside angle brackets.
     * Handles nested generics like `<Swift._DictionaryStorage<A, B> 0x...>`.
     *
     * Examples:
     *   "<ToolbarMiddleware 0x1049065c0> [432]" -> ("ToolbarMiddleware", "0x1049065c0")
     *   "<Swift closure context 0x600002962ca0> [112]" -> ("Swift closure context", "0x600002962ca0")
     *   "<Swift._DictionaryStorage<Foundation.UUID, Client.AppWindowInfo> 0x600003334460> [160]"
     *       -> ("Swift._DictionaryStorage<Foundation.UUID, Client.AppWindowInfo>", "0x600003334460")
     */
    private fun parseTypeAndAddress(right: String): Pair<String, String> {
        val angleStart = right.indexOf('<')
        if (angleStart == -1) return "" to ""

        // Find matching closing bracket, accounting for nested generics
        val angleEnd = findMatchingCloseBracket(right, angleStart)
        if (angleEnd == -1 || angleEnd <= angleStart) {
            return "" to ""
        }

        val insideAngles = right.substring(angleStart + 1, angleEnd).trim()

        // Find the address (last token starting with "0x")
        val addressStart = insideAngles.lastIndexOf("0x")
        if (addressStart == -1) {
            return insideAngles to ""
        }

        val typeName = insideAngles.substring(0, addressStart).trim()
        val address = insideAngles.substring(addressStart).trim()

        return typeName to address
    }

    /**
     * Find the matching closing '>' for an opening '<' at the given position,
     * accounting for nested angle brackets in generic types.
     */
    private fun findMatchingCloseBracket(s: String, openPos: Int): Int {
        var depth = 0
        for (i in openPos until s.length) {
            when (s[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * Parse instance size from trailing "[N]".
     */
    private fun parseInstanceSize(s: String): Int {
        val bracketStart = s.lastIndexOf('[')
        val bracketEnd = s.lastIndexOf(']')
        if (bracketStart == -1 || bracketEnd == -1 || bracketEnd <= bracketStart) {
            return 0
        }
        return s.substring(bracketStart + 1, bracketEnd).trim().toIntOrNull() ?: 0
    }
}