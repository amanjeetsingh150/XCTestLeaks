package xctestleaks

import kotlinx.serialization.Serializable
import xctestleaks.command.CommandRunner
import java.time.Instant

/**
 * Parameters for invoking the `leaks` tool via xcrun simctl spawn.
 *
 * Command format: xcrun simctl spawn <deviceId> leaks <pid|processName> [--exclude ...]
 *
 * Exactly one of [pid] or [processName] must be provided.
 *
 * @param pid The process ID to analyze.
 * @param processName The name of the process to analyze (e.g., "Client").
 * @param deviceId The simulator device ID or "booted" for the currently booted simulator.
 * @param excludeSymbols Symbols to exclude from leak detection (passed as --exclude flags).
 */
@Serializable
data class LeaksInvocationParams(
    val pid: Int? = null,
    val processName: String? = null,
    val deviceId: String = "booted",
    val excludeSymbols: List<String> = emptyList(),
) {
    init {
        require((pid != null) xor (processName != null)) {
            "Exactly one of pid or processName must be provided"
        }
    }

    /** Returns the target identifier (PID or process name) for the leaks command. */
    val target: String get() = pid?.toString() ?: processName!!

    override fun toString(): String {
        val targetStr = if (pid != null) "pid=$pid" else "process=$processName"
        return "$targetStr, device=$deviceId"
    }
}

/**
 * High-level result of invoking `leaks`.
 *
 * [rawOutput] is the full stdout from the tool.
 * [invocationTime] is when the command was executed.
 */
data class LeaksRawResult(
    val params: LeaksInvocationParams,
    val rawOutput: String,
    val exitCode: Int,
    val stderr: String,
    val invocationTime: Instant = Instant.now(),
)

/**
 * Abstraction over the `leaks` command-line tool.
 */
interface LeaksTool {
    /**
     * Invokes `leaks` with the given parameters.
     *
     * Implementation must:
     *  - build the appropriate CLI command (PID or process name)
     *  - execute it via [xctestleaks.command.CommandRunner]
     *  - return stdout/stderr/exitCode
     */
    fun runLeaks(params: LeaksInvocationParams): LeaksRawResult
}

/**
 * Implementation that runs leaks via xcrun simctl spawn for iOS simulators.
 *
 * Command format: xcrun simctl spawn <deviceId> leaks <processName> [--exclude ...]
 *
 * Example:
 *   xcrun simctl spawn booted leaks Client
 *   xcrun simctl spawn booted leaks Client --exclude KnownLeak
 */
class XCTestLeaksTool(
    private val commandRunner: CommandRunner,
) : LeaksTool {

    override fun runLeaks(params: LeaksInvocationParams): LeaksRawResult {
        val cmd = buildCommand(params)
        val startedAt = Instant.now()
        val result = commandRunner.run(cmd)

        return LeaksRawResult(
            params = params,
            rawOutput = result.stdout,
            exitCode = result.exitCode,
            stderr = result.stderr,
            invocationTime = startedAt,
        )
    }

    private fun buildCommand(params: LeaksInvocationParams): List<String> {
        // xcrun simctl spawn <deviceId> leaks <pid|processName> [--exclude ...]
        val baseCommand = listOf(
            "xcrun", "simctl", "spawn",
            params.deviceId,
            "leaks",
            params.target,
        )

        val excludeArgs = params.excludeSymbols.flatMap { listOf("--exclude", it) }

        return baseCommand + excludeArgs
    }
}
