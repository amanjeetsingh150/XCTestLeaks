package xctestleaks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeaksReportJsonTest {

    private val parser = TokenBasedLeaksParser()

    private fun createRawResult(output: String) = LeaksRawResult(
        params = LeaksInvocationParams(pid = 12345),
        rawOutput = output,
        exitCode = 0,
        stderr = "",
    )

    @Test
    fun `toJson produces valid JSON`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               1 (112 bytes) logger --> <Swift closure context 0x60000291bb10> [112]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val json = report.toJson()

        // Should be valid JSON
        val parsed = Json.parseToJsonElement(json)

        assertTrue(parsed.jsonObject.containsKey("leaks"))
        assertTrue(parsed.jsonObject.containsKey("params"))
        assertTrue(parsed.jsonObject.containsKey("summary"))
    }

    @Test
    fun `toJsonPretty produces formatted JSON`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val jsonPretty = report.toJsonPretty()

        // Pretty JSON should contain newlines
        assertTrue(jsonPretty.contains("\n"))
    }

    @Test
    fun `JSON contains correct leak fields`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
               13 (1.42K) windowManager --> <MockWindowManager 0x600000c6cc90> [48]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val json = report.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject

        val leaks = parsed["leaks"]?.jsonArray
        assertNotNull(leaks, "leaks array should not be null")
        assertEquals(1, leaks!!.size, "Should have 1 leak")

        val leak = leaks[0].jsonObject
        assertEquals("ROOT_LEAK", leak["leakType"]?.jsonPrimitive?.content)
        assertEquals(32, leak["rootCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals("3.66K", leak["rootSizeHumanReadable"]?.jsonPrimitive?.content)
        assertEquals("ToolbarMiddleware", leak["rootTypeName"]?.jsonPrimitive?.content)
        assertEquals(432, leak["rootInstanceSizeBytes"]?.jsonPrimitive?.content?.toInt())

        val children = leak["children"]?.jsonArray
        assertNotNull(children, "children array should not be null")
        assertEquals(1, children!!.size, "Should have 1 child")

        val child = children[0].jsonObject
        assertEquals("windowManager", child["fieldName"]?.jsonPrimitive?.content)
        assertEquals("MockWindowManager", child["typeName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `JSON contains ROOT_CYCLE leak type`() {
        val input = """
            7 (1.25K) ROOT CYCLE: <MockNotificationCenter 0x60000331b0c0> [160]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val json = report.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject

        val leaks = parsed["leaks"]!!.jsonArray
        val leak = leaks[0].jsonObject
        assertEquals("ROOT_CYCLE", leak["leakType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `JSON contains params with pid`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val json = report.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject

        val params = parsed["params"]!!.jsonObject
        assertEquals(12345, params["pid"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `JSON contains invocationTime as ISO string`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))
        val json = report.toJson()
        val parsed = Json.parseToJsonElement(json).jsonObject

        val invocationTime = parsed["invocationTime"]!!.jsonPrimitive.content
        // Should be ISO-8601 format
        assertTrue(invocationTime.contains("T"), "invocationTime should be ISO format: $invocationTime")
    }

    @Test
    fun `filtered report JSON contains only matching leaks`() {
        val input = """
            32 (3.66K) ROOT LEAK: <ToolbarMiddleware 0x1049065c0> [432]
            7 (1.25K) ROOT CYCLE: <MockNotificationCenter 0x60000331b0c0> [160]
        """.trimIndent()

        val report = parser.parse(createRawResult(input))

        val cyclesJson = report.filterRootCycles().toJson()
        val cyclesParsed = Json.parseToJsonElement(cyclesJson).jsonObject
        val cycleLeaks = cyclesParsed["leaks"]?.jsonArray
        assertNotNull(cycleLeaks)
        assertEquals(1, cycleLeaks!!.size)
        assertEquals("ROOT_CYCLE", cycleLeaks[0].jsonObject["leakType"]?.jsonPrimitive?.content)

        val leaksJson = report.filterRootLeaks().toJson()
        val leaksParsed = Json.parseToJsonElement(leaksJson).jsonObject
        val rootLeaks = leaksParsed["leaks"]?.jsonArray
        assertNotNull(rootLeaks)
        assertEquals(1, rootLeaks!!.size)
        assertEquals("ROOT_LEAK", rootLeaks[0].jsonObject["leakType"]?.jsonPrimitive?.content)
    }
}