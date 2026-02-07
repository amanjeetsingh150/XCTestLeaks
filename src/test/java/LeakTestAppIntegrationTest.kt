package xctestleaks

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test that runs the LeakTestApp on simulator and validates
 * that the server correctly writes leak report artifacts (JSON, HTML, per-leak files).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeakTestAppIntegrationTest {

    private val serverPort = 8080
    private val serverHost = "localhost"
    private var server: LeaksServer? = null

    private val testAppProject = "tests/LeakTestApp/LeakTestApp.xcodeproj"
    private val testAppScheme = "LeakTestApp"

    private lateinit var artifactsDir: Path

    private val xcodebuildRunner = XcodebuildRunner(timeoutSeconds = 30000, printOutput = true)

    private val simulatorDestination: String by lazy {
        xcodebuildRunner.findAvailableSimulator()
    }

    @BeforeAll
    fun setUp() {
        artifactsDir = Files.createTempDirectory("leak-test-artifacts")
        println("Artifacts directory: ${artifactsDir.toAbsolutePath()}")

        startServer()
        waitForServerHealth()
    }

    @AfterAll
    fun tearDown() {
        stopServer()
    }

    // MARK: - Server Management

    private fun startServer() {
        if (isServerHealthy()) {
            println("✓ Server already running on http://$serverHost:$serverPort (external)")
            return
        }

        println("Starting leaks server on http://$serverHost:$serverPort with artifacts at ${artifactsDir.toAbsolutePath()}...")
        try {
            server = LeaksServer(port = serverPort, host = serverHost, artifactsDir = artifactsDir)
            server?.start()
        } catch (e: Exception) {
            println("Warning: Could not start server (may already be running): ${e.message}")
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
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

    private fun waitForServerHealth(): Boolean {
        val healthUrl = "http://$serverHost:$serverPort/health"
        for (i in 1..30) {
            try {
                val connection = URI(healthUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                if (connection.responseCode == 200) {
                    println("✓ Server is healthy")
                    return true
                }
            } catch (_: Exception) {
                // Server not ready yet
            }
            Thread.sleep(500)
        }
        fail<Unit>("Server did not become healthy within 15 seconds")
        return false
    }

    // MARK: - Tests

    @Test
    fun `server health check returns ok`() {
        val response = queryServer("/health")
        assertTrue(response.contains("ok"), "Health check should return ok")
    }

    @Test
    fun `build and run LeakTestApp then validate report artifacts`() {
        val projectFile = File(testAppProject)
        assertTrue(projectFile.exists(), "Test project should exist at $testAppProject")

        // Step 1: Build
        println("Step 1: Building LeakTestApp...")
        val buildResult = xcodebuildRunner.build(
            project = testAppProject,
            scheme = testAppScheme,
            destination = simulatorDestination,
            configuration = "Debug"
        )
        assertEquals(0, buildResult.exitCode, "Build should succeed. Output:\n${buildResult.stdout}\n${buildResult.stderr}")
        println("✓ Build succeeded")

        // Step 2: Run tests (Swift tests trigger leaks and hit the server)
        println("Step 2: Running LeakTestApp tests...")
        val testResult = xcodebuildRunner.test(
            project = testAppProject,
            scheme = testAppScheme,
            destination = simulatorDestination
        )
        println("Test exit code: ${testResult.exitCode}")

        // Step 3: Validate full_leak_report.json exists and contains expected data
        println("Step 3: Validating full_leak_report.json...")
        val fullReportFile = artifactsDir.resolve("full_leak_report.json").toFile()
        assertTrue(fullReportFile.exists(), "Full leak report should exist at ${fullReportFile.absolutePath}")

        val reportContent = fullReportFile.readText()
        assertTrue(reportContent.isNotBlank(), "Report should not be empty")

        val containsLeakyManager = reportContent.contains("LeakyManager")
        val containsLeakyWorker = reportContent.contains("LeakyWorker")
        val containsLeakyClosureHolder = reportContent.contains("LeakyClosureHolder")
        assertTrue(
            containsLeakyManager || containsLeakyWorker || containsLeakyClosureHolder,
            "Report should contain LeakyManager, LeakyWorker, or LeakyClosureHolder"
        )
        println("✓ full_leak_report.json contains expected leak types")

        // Step 4: Validate testName is present in the report
        println("Step 4: Validating testName in report...")
        val hasTestName = reportContent.contains("testDetectClosureLeaks") ||
                reportContent.contains("testDetectRetainCycleLeaks")
        assertTrue(hasTestName, "Report should contain a testName from the Swift tests")
        println("✓ testName present in report")

        // Step 5: Validate per-leak artifact directories
        println("Step 5: Validating per-leak artifact directories...")
        val leaksDir = artifactsDir.resolve("leaks").toFile()
        if (leaksDir.exists()) {
            val leakDirs = leaksDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            assertTrue(leakDirs.isNotEmpty(), "Should have at least one leak artifact directory")
            println("✓ Found ${leakDirs.size} leak artifact directories")

            for (leakDir in leakDirs) {
                val infoFile = File(leakDir, "info.txt")
                val rawFile = File(leakDir, "raw.txt")
                val jsonFile = File(leakDir, "leak.json")

                assertTrue(infoFile.exists(), "Leak dir ${leakDir.name} should have info.txt")
                assertTrue(rawFile.exists(), "Leak dir ${leakDir.name} should have raw.txt")
                assertTrue(jsonFile.exists(), "Leak dir ${leakDir.name} should have leak.json")

                val leakJson = jsonFile.readText()
                assertTrue(leakJson.contains("\"testName\""), "leak.json should contain testName field")
                assertTrue(leakJson.contains("\"leakType\""), "leak.json should contain leakType field")

                println("  ✓ ${leakDir.name}: info.txt, raw.txt, leak.json present")
            }
        }

        // Step 6: Generate and validate HTML report
        println("Step 6: Generating HTML report...")
        val htmlGenerator = HtmlReportGenerator()
        val htmlReportFile = artifactsDir.resolve("report.html").toFile()

        val htmlGenerated = htmlGenerator.generate(artifactsDir, htmlReportFile)
        assertTrue(htmlGenerated, "HTML report should be generated successfully")
        assertTrue(htmlReportFile.exists(), "HTML report file should exist")
        assertTrue(htmlReportFile.length() > 0, "HTML report should not be empty")

        val htmlContent = htmlReportFile.readText()
        assertTrue(htmlContent.contains("XCTestLeaks Report"), "HTML should contain report title")
        assertTrue(
            htmlContent.contains("LeakyManager") || htmlContent.contains("LeakyWorker") || htmlContent.contains("LeakyClosureHolder"),
            "HTML report should contain leak type names"
        )
        println("✓ HTML report validated: ${htmlReportFile.absolutePath}")
    }

    // MARK: - Helpers

    private fun queryServer(path: String): String {
        val url = "http://$serverHost:$serverPort$path"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 60000

        return if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            fail("Server returned ${connection.responseCode}: $error")
        }
    }
}