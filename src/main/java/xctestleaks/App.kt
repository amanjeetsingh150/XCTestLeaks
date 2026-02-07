package xctestleaks

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xctestleaks.command.MacOSCommandRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Output format for the leak report.
 */
enum class OutputFormat {
    /** Raw output from the leaks command. */
    RAW,
    /** Parsed report as compact JSON. */
    JSON,
    /** Parsed report as pretty-printed JSON. */
    JSON_PRETTY,
}

/**
 * Filter for which leak types to include.
 */
enum class LeakFilter {
    /** Include all leaks (both ROOT_LEAK and ROOT_CYCLE). */
    ALL,
    /** Include only ROOT_LEAK entries. */
    LEAKS,
    /** Include only ROOT_CYCLE entries. */
    CYCLES,
}

/**
 * HTTP server subcommand - starts a server that can be called from XCTest.
 *
 * Usage:
 *   xctestleaks serve                          # Start server on localhost:8080
 *   xctestleaks serve --port 9090              # Start on custom port
 *
 * Then call from XCTest:
 *   GET http://localhost:8080/leaks?process=Client&filter=cycles
 */
@Command(
    name = "serve",
    mixinStandardHelpOptions = true,
    description = ["Start HTTP server for XCTest integration."],
)
class ServeCommand : Callable<Int> {

    @Option(
        names = ["--port"],
        description = ["Port to listen on (default: \${DEFAULT-VALUE})."],
        defaultValue = "8080",
    )
    var port: Int = 8080

    @Option(
        names = ["--host"],
        description = ["Host to bind to (default: \${DEFAULT-VALUE})."],
        defaultValue = "localhost",
    )
    var host: String = "localhost"

    override fun call(): Int {
        val server = LeaksServer(port = port, host = host)

        // Handle shutdown gracefully
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
            server.stop()
        })

        server.start()

        // Block until interrupted
        try {
            server.awaitTermination()
        } catch (e: InterruptedException) {
            // Normal shutdown
        }

        return 0
    }
}

/**
 * Run XCTest with automatic leak detection and artifact collection.
 *
 * This command:
 *   1. Starts the leaks HTTP server
 *   2. Waits for the server to be ready
 *   3. Runs xcodebuild test with the given parameters
 *   4. Saves per-leak artifacts to the output directory
 *
 * Usage:
 *   xctestleaks run --project ./MyApp.xcodeproj --scheme "MyScheme" --destination "platform=iOS Simulator,OS=17.5,name=iPhone 15 Pro"
 */
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = ["Run XCTest with automatic leak detection and artifact collection."],
)
class RunTestsCommand : Callable<Int> {

    @Option(
        names = ["--project", "-p"],
        description = ["Path to the Xcode project (.xcodeproj)."],
        required = true,
    )
    lateinit var project: String

    @Option(
        names = ["--scheme", "-s"],
        description = ["The scheme to build and test."],
        required = true,
    )
    lateinit var scheme: String

    @Option(
        names = ["--destination", "-d"],
        description = ["The destination specifier (e.g., 'platform=iOS Simulator,OS=17.5,name=iPhone 15 Pro')."],
        required = true,
    )
    lateinit var destination: String

    @Option(
        names = ["--output-dir", "-o"],
        description = ["Directory to save leak artifacts (default: ./leak_artifacts)."],
        defaultValue = "./leak_artifacts",
    )
    var outputDir: String = "./leak_artifacts"

    @Option(
        names = ["--server-port"],
        description = ["Port for the leaks server (default: 8080)."],
        defaultValue = "8080",
    )
    var serverPort: Int = 8080

    @Option(
        names = ["--server-host"],
        description = ["Host for the leaks server (default: localhost)."],
        defaultValue = "localhost",
    )
    var serverHost: String = "localhost"

    @Option(
        names = ["--server-timeout"],
        description = ["Timeout in seconds waiting for server to start (default: 30)."],
        defaultValue = "30",
    )
    var serverTimeout: Int = 30

    @Option(
        names = ["--xcodebuild-args"],
        description = ["Additional arguments to pass to xcodebuild (can be repeated)."],
    )
    var xcodebuildArgs: List<String> = emptyList()

    @Option(
        names = ["--process-name"],
        description = ["Name of the app process to monitor for leaks (default: derived from scheme)."],
    )
    var processName: String? = null

    @Option(
        names = ["--no-start-server"],
        description = ["Don't start the server, assume it's already running."],
    )
    var noStartServer: Boolean = false

    @Option(
        names = ["--keep-server"],
        description = ["Keep the server running after tests complete."],
    )
    var keepServer: Boolean = false

    @Option(
        names = ["--html-output"],
        description = ["Generate HTML report in artifacts directory after tests complete."],
    )
    var htmlOutput: Boolean = false

    private var server: LeaksServer? = null
    private val xcodebuildRunner = XcodebuildRunner()
    private lateinit var outputPath: Path

    override fun call(): Int {
        // Create output directory
        outputPath = Path.of(outputDir)
        Files.createDirectories(outputPath)

        println("=== XCTestLeaks Test Runner ===")
        println("Project: $project")
        println("Scheme: $scheme")
        println("Destination: $destination")
        println("Output directory: ${outputPath.toAbsolutePath()}")
        println()

        // Start server with artifacts directory - leaks will be written automatically
        if (!noStartServer) {
            if (!startServer()) {
                return 1
            }
        }

        // Wait for server to be ready BEFORE running xcodebuild
        println("Waiting for server to be ready...")
        if (!waitForServerHealth()) {
            System.err.println("Error: Server failed to become healthy within ${serverTimeout}s")
            stopServer()
            return 1
        }
        println("✓ Server is ready at http://$serverHost:$serverPort")

        // Verify server health one more time before xcodebuild
        if (!isServerHealthy()) {
            System.err.println("Error: Server health check failed just before xcodebuild")
            stopServer()
            return 1
        }
        println("✓ Server health verified - starting xcodebuild")
        println()

        // Run xcodebuild (server is guaranteed healthy at this point)
        // Leaks will be written to artifacts directory automatically when detected
        println("Starting xcodebuild test...")
        val xcodebuildResult = xcodebuildRunner.test(
            project = project,
            scheme = scheme,
            destination = destination,
            additionalArgs = xcodebuildArgs
        )

        // Save xcodebuild output
        val xcodebuildOutputFile = outputPath.resolve("xcodebuild_output.txt").toFile()
        xcodebuildOutputFile.writeText(xcodebuildResult.stdout + "\n" + xcodebuildResult.stderr)
        println("Xcodebuild output saved to: ${xcodebuildOutputFile.absolutePath}")

        // Generate HTML report if requested
        if (htmlOutput) {
            generateHtmlReport()
        }

        // Print summary
        println()
        println("=== Summary ===")
        println("Xcodebuild exit code: ${xcodebuildResult.exitCode}")
        println("Artifacts directory: ${outputPath.toAbsolutePath()}")
        if (htmlOutput) {
            println("HTML report: ${outputPath.resolve("report.html").toAbsolutePath()}")
        }

        // Stop server unless --keep-server is specified
        if (!keepServer) {
            stopServer()
        } else {
            println("Server kept running at http://$serverHost:$serverPort")
        }

        return if (xcodebuildResult.exitCode == 0) 0 else 1
    }

    private fun startServer(): Boolean {
        println("Starting leaks server on http://$serverHost:$serverPort...")
        println("Leak artifacts will be written to: ${outputPath.toAbsolutePath()}")
        return try {
            server = LeaksServer(port = serverPort, host = serverHost, artifactsDir = outputPath)
            server?.start()
            true
        } catch (e: Exception) {
            System.err.println("Error starting server: ${e.message}")
            false
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun waitForServerHealth(): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMs = serverTimeout * 1000L
        val healthUrl = "http://$serverHost:$serverPort/health"

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val connection = URI(healthUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    return true
                }
            } catch (_: Exception) {
                // Server not ready yet
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val connection = URI("http://$serverHost:$serverPort/health").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun generateHtmlReport() {
        println()
        println("Generating HTML report...")
        val generator = HtmlReportGenerator()
        val reportFile = outputPath.resolve("report.html").toFile()

        if (generator.generate(outputPath, reportFile)) {
            println("✓ HTML report generated: ${reportFile.absolutePath}")
        } else {
            System.err.println("Warning: Failed to generate HTML report")
        }
    }
}

/**
 * CLI wrapper for the macOS `leaks` command with parsing and filtering capabilities.
 *
 * Runs leaks via: xcrun simctl spawn <device> leaks <pid|processName>
 *
 * Usage examples:
 *   xctestleaks Client # Analyze "Client" process on booted simulator
 *   xctestleaks -p 12345 # Analyze by PID
 *   xctestleaks Client --device <UUID> # Analyze on specific simulator
 *   xctestleaks Client --format JSON # Output as JSON
 *   xctestleaks Client --filter cycles # Show only retain cycles
 *   xctestleaks Client --exclude "KnownLeak" # Exclude known leaks
 *   xctestleaks serve # Start HTTP server for XCTest
 */
@Command(
    name = "xctestleaks",
    mixinStandardHelpOptions = true,
    version = ["xctestleaks 0.1.2"],
    description = [
        "A wrapper around macOS leaks command for iOS simulators.",
        "",
        "Runs: xcrun simctl spawn <device> leaks <pid|processName>",
        "Provides structured output with filtering for root leaks vs retain cycles.",
    ],
    subcommands = [ServeCommand::class, RunTestsCommand::class],
)
class App : Callable<Int> {

    @Parameters(
        index = "0",
        arity = "0..1",
        description = ["Process name to analyze (e.g., 'Client'). Use -p for PID instead."],
    )
    var processName: String? = null

    @Option(
        names = ["-p", "--pid"],
        description = ["Process ID to analyze (alternative to process name)."],
    )
    var pid: Int? = null

    @Option(
        names = ["-d", "--device"],
        description = ["Simulator device ID or 'booted' (default: \${DEFAULT-VALUE})."],
        defaultValue = "booted",
    )
    var deviceId: String = "booted"

    @Option(
        names = ["-f", "--format"],
        description = ["Output format: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})."],
        defaultValue = "RAW",
    )
    var format: OutputFormat = OutputFormat.RAW

    @Option(
        names = ["--filter"],
        description = ["Filter leaks: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE})."],
        defaultValue = "ALL",
    )
    var filter: LeakFilter = LeakFilter.ALL

    @Option(
        names = ["-e", "--exclude"],
        description = ["Exclude leaks matching symbol name (can be repeated)."],
    )
    var excludeSymbols: List<String> = emptyList()

    @Option(
        names = ["--summary"],
        description = ["Show summary statistics only."],
    )
    var summaryOnly: Boolean = false

    override fun call(): Int {
        // Validate input
        if (pid == null && processName == null) {
            System.err.println("Error: Either process name or --pid must be provided.")
            System.err.println("Use --help for usage information.")
            return 1
        }

        if (pid != null && processName != null) {
            System.err.println("Error: Cannot specify both process name and --pid.")
            return 1
        }

        // Build params
        val params = LeaksInvocationParams(
            pid = pid,
            processName = processName,
            deviceId = deviceId,
            excludeSymbols = excludeSymbols,
        )

        // Run leaks command
        val commandRunner = MacOSCommandRunner()
        val leaksTool = XCTestLeaksTool(commandRunner)

        System.err.println("Running leaks on $params...")

        val rawResult = try {
            leaksTool.runLeaks(params)
        } catch (e: Exception) {
            System.err.println("Error running leaks: ${e.message}")
            return 1
        }

        // Check for errors
        if (rawResult.stderr.isNotEmpty() && rawResult.exitCode != 0 && rawResult.exitCode != 1) {
            System.err.println("leaks stderr: ${rawResult.stderr}")
        }

        // For RAW format, just output the raw result
        if (format == OutputFormat.RAW && filter == LeakFilter.ALL && !summaryOnly) {
            println(rawResult.rawOutput)
            return if (rawResult.exitCode == 0) 0 else 1
        }

        // Parse the output
        val parser = TokenBasedLeaksParser()
        val report = parser.parse(rawResult)

        // Apply filter
        val filteredReport = when (filter) {
            LeakFilter.ALL -> report
            LeakFilter.LEAKS -> report.filterRootLeaks()
            LeakFilter.CYCLES -> report.filterRootCycles()
        }

        // Output based on format
        if (summaryOnly) {
            printSummary(filteredReport)
        } else {
            when (format) {
                OutputFormat.RAW -> printRawFiltered(filteredReport)
                OutputFormat.JSON -> println(filteredReport.toJson())
                OutputFormat.JSON_PRETTY -> println(filteredReport.toJsonPretty())
            }
        }

        // Return non-zero if leaks were found
        return if (filteredReport.leaks.isEmpty()) 0 else 1
    }

    private fun printSummary(report: LeaksReport) {
        val totalLeaks = report.leaks.size
        val rootLeaks = report.rootLeaksOnly().size
        val rootCycles = report.rootCyclesOnly().size

        println("=== Leak Summary ===")
        println("Total entries: $totalLeaks")
        println("  Root leaks:  $rootLeaks")
        println("  Root cycles: $rootCycles")

        if (report.summary.isNotEmpty()) {
            println()
            println("Process info:")
            report.summary.forEach { (key, value) ->
                println("  $key: $value")
            }
        }

        if (report.leaks.isNotEmpty()) {
            println()
            println("Leak types found:")
            report.leaks
                .groupBy { it.rootTypeName ?: "Unknown" }
                .entries
                .sortedByDescending { it.value.size }
                .take(10)
                .forEach { (typeName, instances) ->
                    println("  $typeName: ${instances.size}")
                }
        }
    }

    private fun printRawFiltered(report: LeaksReport) {
        // Print summary info from original output
        report.summary.forEach { (key, value) ->
            println("$key: $value")
        }

        if (report.summary.isNotEmpty()) {
            println()
        }

        // Print raw lines for each leak
        report.leaks.forEach { leak ->
            leak.rawLines.forEach { line ->
                println(line)
            }
            println()
        }
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(App()).execute(*args)
    exitProcess(exitCode)
}
