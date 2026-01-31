package xctestleaks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import xctestleaks.command.MacOSCommandRunner
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * HTTP server that exposes the leaks functionality via REST API.
 *
 * Endpoint: GET /leaks
 *
 * Query parameters:
 *   - process: Process name to analyze (required, or use pid)
 *   - pid: Process ID to analyze (alternative to process)
 *   - device: Simulator device ID (default: "booted")
 *   - filter: Filter type - all, leaks, cycles (default: "cycles")
 *   - format: Output format - json, json_pretty, raw (default: "json")
 *   - exclude: Symbol to exclude (can be repeated)
 *
 * When artifactsDir is set, leaks are automatically written to that directory.
 *
 * Example:
 *   GET http://localhost:8080/leaks?process=Client&filter=cycles
 *   GET http://localhost:8080/leaks?process=Client&filter=all&format=json_pretty
 */
class LeaksServer(
    private val port: Int = 8080,
    private val host: String = "localhost",
    private val artifactsDir: Path? = null,
) {
    private var server: HttpServer? = null

    fun start() {
        val address = InetSocketAddress(host, port)
        server = HttpServer.create(address, 0).apply {
            createContext("/leaks", ::handleLeaksRequest)
            createContext("/health", ::handleHealthCheck)
            executor = null
            start()
        }
        println("LeaksServer started on http://$host:$port")
        println("Endpoints:")
        println("  GET /leaks?process=<name>&filter=cycles")
        println("  GET /health")
    }

    fun stop() {
        server?.stop(0)
        println("LeaksServer stopped")
    }

    fun awaitTermination() {
        // Keep running until interrupted
        Thread.currentThread().join()
    }

    private fun handleHealthCheck(exchange: HttpExchange) {
        val response = """{"status":"ok"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    private fun handleLeaksRequest(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                sendError(exchange, 405, "Method not allowed")
                return
            }

            val queryParams = parseQueryParams(exchange.requestURI.query ?: "")

            // Parse parameters
            val processName = queryParams["process"]
            val pidStr = queryParams["pid"]
            val pid = pidStr?.toIntOrNull()
            val deviceId = queryParams["device"] ?: "booted"
            val filterStr = queryParams["filter"] ?: "cycles"
            val formatStr = queryParams["format"] ?: "raw"
            val excludeSymbols = queryParams.entries
                .filter { it.key == "exclude" }
                .map { it.value }

            // Validate
            if (processName == null && pid == null) {
                sendError(exchange, 400, "Missing required parameter: 'process' or 'pid'")
                return
            }

            if (processName != null && pid != null) {
                sendError(exchange, 400, "Cannot specify both 'process' and 'pid'")
                return
            }

            val filter = when (filterStr.lowercase()) {
                "all" -> LeakFilter.ALL
                "leaks" -> LeakFilter.LEAKS
                "cycles" -> LeakFilter.CYCLES
                else -> {
                    sendError(exchange, 400, "Invalid filter: $filterStr. Use: all, leaks, cycles")
                    return
                }
            }

            val format = when (formatStr.lowercase()) {
                "json" -> OutputFormat.JSON
                "json_pretty" -> OutputFormat.JSON_PRETTY
                "raw" -> OutputFormat.RAW
                else -> {
                    sendError(exchange, 400, "Invalid format: $formatStr. Use: json, json_pretty, raw")
                    return
                }
            }

            // Run leaks
            val params = LeaksInvocationParams(
                pid = pid,
                processName = processName,
                deviceId = deviceId,
                excludeSymbols = excludeSymbols,
            )

            println("Processing request: $params, filter=$filter, format=$format")

            val commandRunner = MacOSCommandRunner()
            val leaksTool = XCTestLeaksTool(commandRunner)
            val rawResult = leaksTool.runLeaks(params)

            // Parse and filter
            val parser = TokenBasedLeaksParser()
            val report = parser.parse(rawResult)
            val filteredReport = when (filter) {
                LeakFilter.ALL -> report
                LeakFilter.LEAKS -> report.filterRootLeaks()
                LeakFilter.CYCLES -> report.filterRootCycles()
            }

            // Format response
            val (contentType, response) = when (format) {
                OutputFormat.JSON -> "application/json" to filteredReport.toJson()
                OutputFormat.JSON_PRETTY -> "application/json" to filteredReport.toJsonPretty()
                OutputFormat.RAW -> "text/plain" to buildRawResponse(filteredReport)
            }

            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }

            println("Response sent: ${filteredReport.leaks.size} leaks found")

            // Write artifacts if directory is configured and leaks were found
            if (artifactsDir != null && filteredReport.leaks.isNotEmpty()) {
                writeLeakArtifacts(filteredReport, processName ?: "pid_$pid")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            sendError(exchange, 500, "Internal error: ${e.message}")
        }
    }

    private fun buildRawResponse(report: LeaksReport): String {
        val sb = StringBuilder()
        report.summary.forEach { (key, value) ->
            sb.appendLine("$key: $value")
        }
        if (report.summary.isNotEmpty()) {
            sb.appendLine()
        }
        report.leaks.forEach { leak ->
            leak.rawLines.forEach { line ->
                sb.appendLine(line)
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()

        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    key to value
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun sendError(exchange: HttpExchange, code: Int, message: String) {
        val response = """{"error":"$message"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    private fun writeLeakArtifacts(report: LeaksReport, processName: String) {
        val outputPath = artifactsDir ?: return

        try {
            Files.createDirectories(outputPath)

            // Save full report
            val fullReportFile = outputPath.resolve("full_leak_report.json").toFile()
            fullReportFile.writeText(report.toJsonPretty())
            println("✓ Leak report written to: ${fullReportFile.absolutePath}")

            // Create per-leak artifacts
            val leaksDir = outputPath.resolve("leaks")
            Files.createDirectories(leaksDir)

            val json = Json { prettyPrint = true }

            report.leaks.forEachIndexed { index, leak ->
                val leakName = sanitizeFileName(leak.rootTypeName ?: "unknown_$index")
                val leakDir = leaksDir.resolve("${index}_$leakName")
                Files.createDirectories(leakDir)

                // Save leak info
                val infoFile = leakDir.resolve("info.txt").toFile()
                infoFile.writeText(buildLeakInfo(leak))

                // Save raw output
                val rawFile = leakDir.resolve("raw.txt").toFile()
                rawFile.writeText(leak.rawLines.joinToString("\n"))

                // Save JSON
                val jsonFile = leakDir.resolve("leak.json").toFile()
                jsonFile.writeText(json.encodeToString(LeakInstance.serializer(), leak))
            }

            println("✓ Created ${report.leaks.size} leak artifacts in: ${leaksDir.toAbsolutePath()}")

        } catch (e: Exception) {
            System.err.println("Warning: Failed to write leak artifacts: ${e.message}")
        }
    }

    private fun buildLeakInfo(leak: LeakInstance): String {
        return buildString {
            appendLine("=== Leak Instance ===")
            appendLine("Type: ${leak.rootTypeName ?: "Unknown"}")
            appendLine("Leak Type: ${leak.leakType}")
            appendLine("Count: ${leak.rootCount ?: "N/A"}")
            appendLine("Size: ${leak.rootSizeHumanReadable ?: "N/A"}")
            appendLine("Instance Size: ${leak.rootInstanceSizeBytes ?: "N/A"} bytes")
            appendLine()
            appendLine("=== Children ===")
            leak.children.forEach { child ->
                appendLine("  ${child.count} (${child.sizeHumanReadable}) ${child.fieldName} --> ${child.typeName} [${child.instanceSizeBytes}]")
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(50)
    }
}