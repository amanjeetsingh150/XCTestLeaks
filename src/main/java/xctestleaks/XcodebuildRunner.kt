package xctestleaks

import java.util.concurrent.TimeUnit

/**
 * Result of running an xcodebuild command.
 */
data class XcodebuildResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Represents an available iOS Simulator device.
 */
data class SimulatorDevice(
    val udid: String,
    val name: String,
    val osVersion: String,
    val isBooted: Boolean
)

/**
 * Exception thrown when no simulator destination can be found.
 */
class NoDestinationFailure(message: String) : RuntimeException(message)

/**
 * Runs xcodebuild commands with proper destination handling.
 *
 * This class encapsulates xcodebuild execution logic and can be used
 * both from the CLI and from tests.
 */
class XcodebuildRunner(
    private val timeoutSeconds: Long = 600,
    private val printOutput: Boolean = true
) {

    /**
     * Finds the first available iOS Simulator by querying xcrun simctl.
     * Prefers booted simulators, then sorts by OS version descending.
     *
     * @return destination string in format: platform="iOS Simulator,id=UUID"
     * @throws NoDestinationFailure if no simulator runtime or device is found
     */
    fun findAvailableSimulator(): String {
        try {
            val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "available", "-j")
                .redirectErrorStream(true)
                .start()
            process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()

            val devices = parseSimulatorDevices(output)

            if (devices.isEmpty()) {
                throw NoDestinationFailure(
                    "No iOS Simulator devices found. Run 'xcrun simctl list devices available' to check available simulators."
                )
            }

            // Prefer booted simulators, then sort by OS version descending
            val selected = devices
                .sortedWith(compareByDescending<SimulatorDevice> { it.isBooted }.thenByDescending { it.osVersion })
                .first()

            if (printOutput) {
                println("✓ Found simulator: ${selected.name} (iOS ${selected.osVersion}, ${if (selected.isBooted) "Booted" else "Shutdown"})")
            }

            // Format: platform="iOS Simulator,id=UUID"
            return """platform=iOS Simulator,id=${selected.udid}"""

        } catch (e: NoDestinationFailure) {
            throw e
        } catch (e: Exception) {
            throw NoDestinationFailure("Failed to query simulators: ${e.message}")
        }
    }

    /**
     * Parses the JSON output from 'xcrun simctl list devices available -j'
     * to extract available simulator devices.
     */
    fun parseSimulatorDevices(jsonOutput: String): List<SimulatorDevice> {
        // Parse JSON to find devices with their runtimes
        // Format: { "devices": { "com.apple.CoreSimulator.SimRuntime.iOS-17-5": [ { "udid": "...", "name": "...", "state": "..." } ] } }
        val devicesRegex = """"com\.apple\.CoreSimulator\.SimRuntime\.(iOS-\d+-\d+)"\s*:\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val deviceRegex = """"udid"\s*:\s*"([^"]+)".*?"name"\s*:\s*"([^"]+)".*?"state"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val availableDevices = mutableListOf<SimulatorDevice>()

        for (runtimeMatch in devicesRegex.findAll(jsonOutput)) {
            val runtimeId = runtimeMatch.groupValues[1] // e.g., "iOS-17-5"
            val devicesJson = runtimeMatch.groupValues[2]

            // Convert runtime ID to version (iOS-17-5 -> 17.5)
            val osVersion = runtimeId.removePrefix("iOS-").replace("-", ".")

            for (deviceMatch in deviceRegex.findAll(devicesJson)) {
                val udid = deviceMatch.groupValues[1]
                val name = deviceMatch.groupValues[2]
                val state = deviceMatch.groupValues[3]

                availableDevices.add(
                    SimulatorDevice(
                        udid = udid,
                        name = name,
                        osVersion = osVersion,
                        isBooted = state == "Booted"
                    )
                )
            }
        }

        return availableDevices
    }

    /**
     * Builds the app using xcodebuild.
     */
    fun build(
        project: String,
        scheme: String,
        destination: String,
        configuration: String = "Debug",
        additionalArgs: List<String> = emptyList()
    ): XcodebuildResult {
        val command = mutableListOf(
            "xcodebuild",
            "build",
            "-project", project,
            "-scheme", scheme,
            "-destination", destination,
            "-configuration", configuration
        )
        command.addAll(additionalArgs)

        return runCommand(command)
    }

    /**
     * Runs tests using xcodebuild.
     */
    fun test(
        project: String,
        scheme: String,
        destination: String,
        onlyTesting: String? = null,
        additionalArgs: List<String> = emptyList()
    ): XcodebuildResult {
        val command = mutableListOf(
            "xcodebuild",
            "test",
            "-project", project,
            "-scheme", scheme,
            "-destination", destination
        )
        if (onlyTesting != null) {
            command.add("-only-testing:$onlyTesting")
        }
        command.addAll(additionalArgs)

        return runCommand(command)
    }

    /**
     * Runs an arbitrary xcodebuild command.
     */
    fun runCommand(command: List<String>): XcodebuildResult {
        if (printOutput) {
            println("Running: ${command.joinToString(" ")}")
            println()
        }

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(false)

        val process = pb.start()

        // Read stdout and stderr concurrently to avoid blocking
        val stdoutLines = mutableListOf<String>()
        val stdoutReader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(stdoutLines) {
                    stdoutLines.add(line)
                }
                if (printOutput) {
                    println(line)
                }
            }
        }
        val stderrLines = mutableListOf<String>()
        val stderrReader = Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderrLines) {
                    stderrLines.add(line)
                }
                if (printOutput) {
                    System.err.println(line)
                }
            }
        }

        stdoutReader.start()
        stderrReader.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        stdoutReader.join(500000)
        stderrReader.join(500000)

        return XcodebuildResult(
            exitCode = if (completed) process.exitValue() else -1,
            stdout = synchronized(stdoutLines) { stdoutLines.joinToString("\n") },
            stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }
        )
    }
}