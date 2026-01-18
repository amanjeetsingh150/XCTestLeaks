package xctestleaks

import java.time.Instant

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

    data class Unknown(
        override val rawLine: String,
    ) : LeaksToken
}

/**
 * A single suspicious leak instance extracted from the output.
 */
data class LeakInstance(
    val address: String?,
    val sizeBytes: Long?,
    val type: String?,
    val stackTrace: List<String>,
    val rawLines: List<String>,
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
 * This is intentionally conservative and extensible:
 *  - First tokenizes each line.
 *  - Then groups tokens into [LeakInstance] and summary map.
 */
class TokenBasedLeaksParser : LeaksParser {

    override fun parse(rawResult: LeaksRawResult): LeaksReport {
        val tokens = tokenize(rawResult.rawOutput)

        val leaks = mutableListOf<LeakInstance>()
        val summary = mutableMapOf<String, String>()

        var currentLeakRawLines = mutableListOf<String>()
        var currentStack = mutableListOf<String>()
        var currentAddress: String? = null
        var currentSize: Long? = null
        var currentType: String? = null

        fun flushCurrentLeak() {
            if (currentLeakRawLines.isNotEmpty()) {
                leaks += LeakInstance(
                    address = currentAddress,
                    sizeBytes = currentSize,
                    type = currentType,
                    stackTrace = currentStack.toList(),
                    rawLines = currentLeakRawLines.toList(),
                )
            }
            currentLeakRawLines = mutableListOf()
            currentStack = mutableListOf()
            currentAddress = null
            currentSize = null
            currentType = null
        }

        for (token in tokens) {
            when (token) {
                is LeaksToken.Header -> {
                    // New header usually implies a new section; flush current leak.
                    flushCurrentLeak()
                    currentLeakRawLines.add(token.rawLine)
                }

                is LeaksToken.LeakLine -> {
                    // Starting a new leak; flush previous.
                    flushCurrentLeak()
                    currentAddress = token.address
                    currentSize = token.sizeBytes
                    currentType = token.type
                    currentLeakRawLines.add(token.rawLine)
                }

                is LeaksToken.StackFrame -> {
                    currentStack.add(token.frame)
                    currentLeakRawLines.add(token.rawLine)
                }

                is LeaksToken.Summary -> {
                    flushCurrentLeak()
                    summary[token.key] = token.value
                }

                is LeaksToken.Unknown -> {
                    // Heuristics: sometimes unknown lines belong to current leak.
                    if (currentLeakRawLines.isNotEmpty()) {
                        currentLeakRawLines.add(token.rawLine)
                    }
                }
            }
        }

        // Flush any trailing leak.
        flushCurrentLeak()

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

            val token = when {
                // Extremely rough examples; adjust to real leaks output patterns.

                // Example header line matcher:
                // "Process 1234: 123 nodes malloced ..."
                trimmed.startsWith("Process ") -> LeaksToken.Header(
                    rawLine = line,
                    processInfo = trimmed,
                )

                // Example leak line matcher (very approximate):
                // "0x1001234  32 bytes  SomeType"
                // Tokenize by whitespace and try to interpret.
                looksLikeLeakLine(trimmed) -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    val address = parts.getOrNull(0)
                    val size = parts.getOrNull(1)?.toLongOrNull()
                    val type = parts.drop(2).joinToString(" ").ifBlank { null }
                    LeaksToken.LeakLine(
                        rawLine = line,
                        address = address,
                        sizeBytes = size,
                        type = type,
                    )
                }

                // Example summary line matcher:
                // "Total leaks: 3"
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

                // Example stack frame line matcher:
                // "1   MyApp        0x1000abcd SomeFunction + 42"
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

    private fun looksLikeLeakLine(trimmed: String): Boolean {
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return false
        // address usually looks like "0x..." and size like an integer
        val addressLooksLikePtr = parts[0].startsWith("0x")
        val sizeIsNumber = parts[1].toLongOrNull() != null
        return addressLooksLikePtr && sizeIsNumber
    }
}
