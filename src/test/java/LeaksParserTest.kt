package xctestleaks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeaksParserTest {

    private val parser = TokenBasedLeaksParser()

    private fun createRawResult(output: String) = LeaksRawResult(
        params = LeaksInvocationParams(pid = 35988),
        rawOutput = output,
        exitCode = 0,
        stderr = "",
    )

    @Test
    fun `parse ROOT LEAK line extracts all fields correctly`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val leak = report.leaks[0]
        assertEquals(32, leak.rootCount)
        assertEquals("3.66K", leak.rootSizeHumanReadable)
        assertEquals("ToolbarMiddleware", leak.rootTypeName)
        assertEquals(432, leak.rootInstanceSizeBytes)
    }

    @Test
    fun `parse ROOT LEAK with children extracts child fields correctly`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               13 (1.42K) windowManager --> <MockWindowManager 0x600000c6cc90> [48]
               1 (112 bytes) logger --> <Swift closure context 0x60000291bb10> [112]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val leak = report.leaks[0]
        assertEquals(2, leak.children.size)

        val windowManager = leak.children[0]
        assertEquals(13, windowManager.count)
        assertEquals("1.42K", windowManager.sizeHumanReadable)
        assertEquals("windowManager", windowManager.fieldName)
        assertEquals("MockWindowManager", windowManager.typeName)
        assertEquals(48, windowManager.instanceSizeBytes)

        val logger = leak.children[1]
        assertEquals(1, logger.count)
        assertEquals("112 bytes", logger.sizeHumanReadable)
        assertEquals("logger", logger.fieldName)
        assertEquals("Swift closure context", logger.typeName)
        assertEquals(112, logger.instanceSizeBytes)
    }

    @Test
    fun `parse child with field offset extracts offset correctly`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               2 (176 bytes) fileManager + 16 --> <Swift closure context 0x600001759600> [64]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val leak = report.leaks[0]
        assertEquals(1, leak.children.size)

        val child = leak.children[0]
        assertEquals(2, child.count)
        assertEquals("176 bytes", child.sizeHumanReadable)
        assertEquals("fileManager", child.fieldName)
        assertEquals("Swift closure context", child.typeName)
        assertEquals(64, child.instanceSizeBytes)
    }

    @Test
    fun `parse multiple ROOT LEAKs creates separate leak instances`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               1 (112 bytes) logger --> <Swift closure context 0x60000291bb10> [112]

            30 (3.48K) ROOT LEAK: <ToolbarMiddleware 0x1049138a0> [432]
               9 (1.39K) recentSearchProvider --> <DefaultRecentSearchProvider 0x60000292e4c0> [112]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(2, report.leaks.size)

        assertEquals(32, report.leaks[0].rootCount)
        assertEquals("3.66K", report.leaks[0].rootSizeHumanReadable)
        assertEquals("ToolbarMiddleware", report.leaks[0].rootTypeName)
        assertEquals(1, report.leaks[0].children.size)

        assertEquals(30, report.leaks[1].rootCount)
        assertEquals("3.48K", report.leaks[1].rootSizeHumanReadable)
        assertEquals("ToolbarMiddleware", report.leaks[1].rootTypeName)
        assertEquals(1, report.leaks[1].children.size)
    }

    @Test
    fun `parse bare child line without arrow`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               2 (192 bytes) windows --> <Swift._DictionaryStorage<Foundation.UUID, Client.AppWindowInfo> 0x600003334460> [160]
                  1 (32 bytes) 0x600000231380 [32]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val leak = report.leaks[0]
        assertEquals(2, leak.children.size)

        val bareChild = leak.children[1]
        assertEquals(1, bareChild.count)
        assertEquals("32 bytes", bareChild.sizeHumanReadable)
        assertEquals("", bareChild.fieldName)
        assertEquals(32, bareChild.instanceSizeBytes)
    }

    @Test
    fun `parse TOTAL line does not create a leak`() {
        val input = """
            875 (101K) << TOTAL >>

            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        assertEquals("ToolbarMiddleware", report.leaks[0].rootTypeName)
    }

    @Test
    fun `parse header and summary lines`() {
        val input = """
            Process 35988: 875 leaks for 103824 total leaked bytes.
            Process:         Client [35988]
            Physical footprint:         82.0M

            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals("Client [35988]", report.summary["Process"])
        assertEquals("82.0M", report.summary["Physical footprint"])
    }

    @Test
    fun `parse deeply nested children`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               13 (1.42K) windowManager --> <MockWindowManager 0x600000c6cc90> [48]
                  11 (1.14K) __strong wrappedManager --> <WindowManagerImplementation 0x60000331c460> [160]
                     5 (624 bytes) tabDataStore --> <DefaultTabDataStore 0x114a06d50> [272]
                        2 (176 bytes) fileManager + 16 --> <Swift closure context 0x600001759600> [64]
                           1 (112 bytes)  + 8 --> <Swift closure context 0x600002962ca0> [112]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val leak = report.leaks[0]
        assertEquals(5, leak.children.size)

        assertEquals("windowManager", leak.children[0].fieldName)
        assertEquals("__strong wrappedManager", leak.children[1].fieldName)
        assertEquals("tabDataStore", leak.children[2].fieldName)
        assertEquals("fileManager", leak.children[3].fieldName)
        assertEquals("", leak.children[4].fieldName)
    }

    @Test
    fun `parse generic type names with angle brackets`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               2 (192 bytes) windows --> <Swift._DictionaryStorage<Foundation.UUID, Client.AppWindowInfo> 0x600003334460> [160]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        assertEquals(1, report.leaks.size)
        val child = report.leaks[0].children[0]
        assertEquals("windows", child.fieldName)
        assertEquals("Swift._DictionaryStorage<Foundation.UUID, Client.AppWindowInfo>", child.typeName)
        assertEquals(160, child.instanceSizeBytes)
    }

    @Test
    fun `parse sample file first ROOT LEAK correctly`() {
        val sampleContent = javaClass.getResource("/sample_leaks_root_retain_cycles.txt")?.readText()
            ?: error("Sample file not found")

        val report = parser.parse(createRawResult(sampleContent))

        assertTrue(report.leaks.isNotEmpty(), "Should parse at least one leak")

        val firstLeak = report.leaks[0]
        assertEquals(32, firstLeak.rootCount)
        assertEquals("3.66K", firstLeak.rootSizeHumanReadable)
        assertEquals("ToolbarMiddleware", firstLeak.rootTypeName)
        assertEquals(432, firstLeak.rootInstanceSizeBytes)

        assertTrue(firstLeak.children.isNotEmpty(), "First leak should have children")

        val windowManagerChild = firstLeak.children.find { it.fieldName == "windowManager" }
        assertNotNull(windowManagerChild, "Should find windowManager child")
        assertEquals(13, windowManagerChild!!.count)
        assertEquals("1.42K", windowManagerChild.sizeHumanReadable)
        assertEquals("MockWindowManager", windowManagerChild.typeName)
        assertEquals(48, windowManagerChild.instanceSizeBytes)
    }

    @Test
    fun `parse sample file extracts correct number of ROOT LEAKs`() {
        val sampleContent = javaClass.getResource("/sample_leaks_root_retain_cycles.txt")?.readText()
            ?: error("Sample file not found")

        val report = parser.parse(createRawResult(sampleContent))

        assertTrue(report.leaks.size > 1, "Should parse multiple ROOT LEAKs from sample")

        report.leaks.forEach { leak ->
            assertNotNull(leak.rootCount, "Each leak should have rootCount")
            assertNotNull(leak.rootSizeHumanReadable, "Each leak should have rootSizeHumanReadable")
            assertNotNull(leak.rootTypeName, "Each leak should have rootTypeName")
            assertTrue(leak.rootTypeName!!.isNotEmpty(), "rootTypeName should not be empty")
        }
    }

    @Test
    fun `child with __strong prefix preserves full field name`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               11 (1.14K) __strong wrappedManager --> <WindowManagerImplementation 0x60000331c460> [160]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        val child = report.leaks[0].children[0]
        assertEquals("__strong wrappedManager", child.fieldName)
        assertEquals("WindowManagerImplementation", child.typeName)
    }
}