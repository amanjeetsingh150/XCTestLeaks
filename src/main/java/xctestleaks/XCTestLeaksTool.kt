package xctestleaks

import kotlinx.serialization.Serializable
import xctestleaks.command.CommandRunner
import java.time.Instant

/**
 * Parameters for invoking the `leaks` tool.
 *
 * Exactly one of [pid] or [processName] must be non-null.
 */
@Serializable
data class LeaksInvocationParams(
    val pid: Int? = null,
    val processName: String? = null,
) {
    init {
        val nonNullCount = listOf(pid, processName).count { it != null }
        require(nonNullCount == 1) {
            "Exactly one of pid or processName must be provided (got pid=$pid, processName=$processName)"
        }
    }

    override fun toString(): String =
        when {
            pid != null -> "pid=$pid"
            else -> "processName=$processName"
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
 * Default implementation that expects `leaks` to be on PATH.
 *
 * Example commands used:
 *  - leaks 1234
 *  - leaks MyProcessName
 */
class XCTestLeaksTool(
    private val commandRunner: CommandRunner,
    private val leaksExecutable: String = "leaks",
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
        return when {
            params.pid != null -> listOf(leaksExecutable, params.pid.toString())
            params.processName != null -> listOf(leaksExecutable, params.processName)
            else -> error("Invalid LeaksInvocationParams: $params")
        }
    }
}
