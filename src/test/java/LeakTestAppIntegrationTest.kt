package xctestleaks

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test that runs the LeakTestApp on simulator and verifies
 * that LeakyManager retain cycles are detected by the server and written to artifacts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeakTestAppIntegrationTest {

    private val serverPort = 8080
    private val serverHost = "localhost"
    private var server: LeaksServer? = null

    // Path to the test app project (relative to project root)
    private val testAppProject = "tests/LeakTestApp/LeakTestApp.xcodeproj"
    private val testAppScheme = "LeakTestApp"

    // Artifacts directory for leak reports
    private lateinit var artifactsDir: Path

    // Use XcodebuildRunner for all xcodebuild operations
    private val xcodebuildRunner = XcodebuildRunner(timeoutSeconds = 30000, printOutput = true)

    // Find the simulator to use - throws NoDestinationFailure if none found
    private val simulatorDestination: String by lazy {
        xcodebuildRunner.findAvailableSimulator()
    }

    @BeforeAll
    fun setUp() {
        // Create artifacts directory
        artifactsDir = Files.createTempDirectory("leak-test-artifacts")
        println("Artifacts directory: ${artifactsDir.toAbsolutePath()}")

        startServer()
        waitForServerHealth()
    }

    @AfterAll
    fun tearDown() {
        stopServer()
        // Clean up artifacts directory
        artifactsDir.toFile().deleteRecursively()
    }

    // MARK: - Server Management

    private fun startServer() {
        // Check if server is already running (external instance)
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
    fun `build and run LeakTestApp writes leak artifacts with LeakyManager retain cycle`() {
        // Verify the test project exists
        val projectFile = File(testAppProject)
        assertTrue(projectFile.exists(), "Test project should exist at $testAppProject")

        // Step 1: Verify server is healthy BEFORE running xcodebuild
        println("Step 1: Verifying server is healthy before xcodebuild...")
        assertTrue(isServerHealthy(), "Server must be healthy before running xcodebuild")
        println("✓ Server health check passed")

        // Step 2: Build the app using XcodebuildRunner
        println("Step 2: Building LeakTestApp...")
        val buildResult = xcodebuildRunner.build(
            project = testAppProject,
            scheme = testAppScheme,
            destination = simulatorDestination,
            configuration = "Debug"
        )
        assertEquals(0, buildResult.exitCode, "Build should succeed. Output:\n${buildResult.stdout}\n${buildResult.stderr}")
        println("✓ Build succeeded")

        // Step 3: Verify server is STILL healthy after build
        println("Step 3: Verifying server is still healthy after build...")
        assertTrue(isServerHealthy(), "Server must still be healthy after build")
        println("✓ Server still healthy")

        // Step 4: Run tests to trigger leaks
        // The Swift test will:
        //   1. Call triggerLeaks() to create retain cycles
        //   2. Call /leaks endpoint on the server
        //   3. Server processes the request and writes artifacts to artifactsDir
        println("Step 4: Running LeakTestApp tests (Swift test will trigger leaks and call /leaks endpoint)...")
        val testResult = xcodebuildRunner.test(
            project = testAppProject,
            scheme = testAppScheme,
            destination = simulatorDestination,
            onlyTesting = "LeakTestAppTests/LeakDetectionTests/testDetectRetainCycleLeaks"
        )
        println("Test exit code: ${testResult.exitCode}")

        // Step 5: Verify artifacts were written by the server
        println("Step 5: Checking artifacts directory for leak reports...")

        val fullReportFile = artifactsDir.resolve("full_leak_report.json").toFile()
        assertTrue(fullReportFile.exists(), "Full leak report should be created at ${fullReportFile.absolutePath}")

        val reportContent = fullReportFile.readText()
        println("=== Leak Report Content ===")
        println(reportContent)
        println("===========================")

        // Assert that our intentional leaks are detected in the artifacts
        val containsLeakyManager = reportContent.contains("LeakyManager")
        val containsLeakyWorker = reportContent.contains("LeakyWorker")
        val containsLeakyClosureHolder = reportContent.contains("LeakyClosureHolder")

        assertTrue(
            containsLeakyManager || containsLeakyWorker || containsLeakyClosureHolder,
            "Leak artifacts should contain LeakyManager, LeakyWorker, or LeakyClosureHolder retain cycle"
        )

        if (containsLeakyManager) println("✓ Detected LeakyManager in artifacts")
        if (containsLeakyWorker) println("✓ Detected LeakyWorker in artifacts")
        if (containsLeakyClosureHolder) println("✓ Detected LeakyClosureHolder in artifacts")

        // Verify per-leak artifact structure
        val leaksDir = artifactsDir.resolve("leaks").toFile()
        if (leaksDir.exists()) {
            val leakDirs = leaksDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            println("✓ Found ${leakDirs.size} individual leak artifact directories")

            for (leakDir in leakDirs) {
                val infoFile = File(leakDir, "info.txt")
                val rawFile = File(leakDir, "raw.txt")
                val jsonFile = File(leakDir, "leak.json")

                assertTrue(infoFile.exists(), "Leak dir ${leakDir.name} should have info.txt")
                assertTrue(rawFile.exists(), "Leak dir ${leakDir.name} should have raw.txt")
                assertTrue(jsonFile.exists(), "Leak dir ${leakDir.name} should have leak.json")

                println("  ✓ ${leakDir.name}: info.txt, raw.txt, leak.json")
            }
        }
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
