package xctestleaks

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xctestleaks.command.MacOSCommandRunner
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
 * CLI wrapper for the macOS `leaks` command with parsing and filtering capabilities.
 *
 * Runs leaks via: xcrun simctl spawn <device> leaks <pid|processName>
 *
 * Usage examples:
 *   xctestleaks Client                         # Analyze "Client" process on booted simulator
 *   xctestleaks -p 12345                       # Analyze by PID
 *   xctestleaks Client --device <UUID>         # Analyze on specific simulator
 *   xctestleaks Client --format json           # Output as JSON
 *   xctestleaks Client --filter cycles         # Show only retain cycles
 *   xctestleaks Client --exclude "KnownLeak"   # Exclude known leaks
 */
@Command(
    name = "xctestleaks",
    mixinStandardHelpOptions = true,
    version = ["xctestleaks 1.0.0"],
    description = [
        "A wrapper around macOS leaks command for iOS simulators.",
        "",
        "Runs: xcrun simctl spawn <device> leaks <pid|processName>",
        "Provides structured output with filtering for root leaks vs retain cycles.",
    ],
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
