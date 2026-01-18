package xctestleaks.command

import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Result of running an external command.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Abstraction over running commands on the host OS.
 */
interface CommandRunner {
    /**
     * Runs a command and captures stdout/stderr.
     *
     * @param command full command + args, e.g. listOf("leaks", "1234")
     * @param workingDir optional working directory
     * @param environment additional environment variables to set/override
     */
    fun run(
        command: List<String>,
        workingDir: Path? = null,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult
}

/**
 * Default implementation of [CommandRunner] using [ProcessBuilder].
 */
class MacOSCommandRunner : CommandRunner {

    override fun run(
        command: List<String>,
        workingDir: Path?,
        environment: Map<String, String>,
    ): CommandResult {
        require(command.isNotEmpty()) { "command must not be empty" }

        val pb = ProcessBuilder(command)

        if (workingDir != null) {
            pb.directory(workingDir.toFile())
        }

        val env = pb.environment()
        environment.forEach { (k, v) -> env[k] = v }

        val process = pb.start()

        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)

        val exitCode = process.waitFor()

        return CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }
}
