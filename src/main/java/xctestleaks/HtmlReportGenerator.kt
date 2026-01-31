package xctestleaks

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates an HTML report from leak artifacts directory.
 */
class HtmlReportGenerator {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generate HTML report from artifacts directory.
     *
     * @param artifactsDir Directory containing leak artifacts (with full_leak_report.json and leaks/ subdirectory)
     * @param outputFile Output HTML file path
     * @return true if report was generated successfully
     */
    fun generate(artifactsDir: Path, outputFile: File): Boolean {
        val fullReportFile = artifactsDir.resolve("full_leak_report.json").toFile()
        if (!fullReportFile.exists()) {
            System.err.println("Error: No full_leak_report.json found in ${artifactsDir.toAbsolutePath()}")
            return false
        }

        val report = try {
            json.decodeFromString(LeaksReport.serializer(), fullReportFile.readText())
        } catch (e: Exception) {
            System.err.println("Error parsing leak report: ${e.message}")
            return false
        }

        val leaksDir = artifactsDir.resolve("leaks").toFile()
        val leakArtifacts = if (leaksDir.exists()) {
            leaksDir.listFiles()?.filter { it.isDirectory }?.map { dir ->
                LeakArtifact(
                    name = dir.name,
                    infoContent = dir.resolve("info.txt").takeIf { it.exists() }?.readText(),
                    rawContent = dir.resolve("raw.txt").takeIf { it.exists() }?.readText(),
                    jsonContent = dir.resolve("leak.json").takeIf { it.exists() }?.readText()
                )
            } ?: emptyList()
        } else {
            emptyList()
        }

        val html = generateHtml(report, leakArtifacts)
        outputFile.writeText(html)
        return true
    }

    private data class LeakArtifact(
        val name: String,
        val infoContent: String?,
        val rawContent: String?,
        val jsonContent: String?
    )

    private fun generateHtml(report: LeaksReport, artifacts: List<LeakArtifact>): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val rootLeaks = report.leaks.filter { it.leakType == LeakType.ROOT_LEAK }
        val rootCycles = report.leaks.filter { it.leakType == LeakType.ROOT_CYCLE }
        // Get test name from first leak (all leaks in a report come from same test)
        val testName = report.leaks.firstOrNull()?.testName

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>XCTestLeaks Report</title>
    <style>
        :root {
            --bg-primary: #1a1a2e;
            --bg-secondary: #16213e;
            --bg-card: #0f3460;
            --text-primary: #eaeaea;
            --text-secondary: #a0a0a0;
            --accent-red: #e94560;
            --accent-orange: #f39c12;
            --accent-green: #27ae60;
            --accent-blue: #3498db;
            --border-color: #2a2a4a;
        }

        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', Roboto, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 2rem;
        }

        header {
            text-align: center;
            margin-bottom: 2rem;
            padding-bottom: 1rem;
            border-bottom: 1px solid var(--border-color);
        }

        h1 {
            font-size: 2.5rem;
            font-weight: 600;
            margin-bottom: 0.5rem;
        }

        .timestamp {
            color: var(--text-secondary);
            font-size: 0.9rem;
        }

        .test-name-header {
            color: var(--accent-blue);
            font-size: 1.1rem;
            margin-bottom: 0.5rem;
        }

        .test-name-header strong {
            color: var(--text-primary);
        }

        .summary-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 1rem;
            margin-bottom: 2rem;
        }

        .summary-card {
            background: var(--bg-secondary);
            border-radius: 12px;
            padding: 1.5rem;
            text-align: center;
            border: 1px solid var(--border-color);
        }

        .summary-card .number {
            font-size: 3rem;
            font-weight: 700;
            line-height: 1;
        }

        .summary-card .label {
            color: var(--text-secondary);
            font-size: 0.9rem;
            margin-top: 0.5rem;
        }

        .summary-card.danger .number { color: var(--accent-red); }
        .summary-card.warning .number { color: var(--accent-orange); }
        .summary-card.success .number { color: var(--accent-green); }
        .summary-card.info .number { color: var(--accent-blue); }

        .section {
            margin-bottom: 2rem;
        }

        .section-title {
            font-size: 1.5rem;
            font-weight: 600;
            margin-bottom: 1rem;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }

        .section-title .badge {
            background: var(--accent-red);
            color: white;
            font-size: 0.8rem;
            padding: 0.2rem 0.6rem;
            border-radius: 12px;
        }

        .leak-card {
            background: var(--bg-secondary);
            border-radius: 12px;
            margin-bottom: 1rem;
            border: 1px solid var(--border-color);
            overflow: hidden;
        }

        .leak-header {
            background: var(--bg-card);
            padding: 1rem 1.5rem;
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
            transition: background 0.2s;
        }

        .leak-header:hover {
            background: #1a4a7a;
        }

        .leak-title {
            font-weight: 600;
            font-size: 1.1rem;
        }

        .leak-meta {
            display: flex;
            gap: 1rem;
            color: var(--text-secondary);
            font-size: 0.85rem;
        }

        .leak-type {
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
            font-size: 0.75rem;
            font-weight: 600;
        }

        .leak-type.cycle {
            background: var(--accent-orange);
            color: #000;
        }

        .leak-type.leak {
            background: var(--accent-red);
            color: #fff;
        }

        .test-name {
            background: var(--accent-blue);
            color: #fff;
            padding: 0.2rem 0.5rem;
            border-radius: 4px;
            font-size: 0.75rem;
            font-weight: 600;
            margin-left: 0.5rem;
        }

        .leak-body {
            padding: 1.5rem;
            display: none;
        }

        .leak-card.expanded .leak-body {
            display: block;
        }

        .leak-card.expanded .leak-header {
            border-bottom: 1px solid var(--border-color);
        }

        .tabs {
            display: flex;
            gap: 0.5rem;
            margin-bottom: 1rem;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 0.5rem;
        }

        .tab {
            padding: 0.5rem 1rem;
            background: transparent;
            border: none;
            color: var(--text-secondary);
            cursor: pointer;
            border-radius: 6px;
            transition: all 0.2s;
        }

        .tab:hover {
            background: var(--bg-card);
            color: var(--text-primary);
        }

        .tab.active {
            background: var(--accent-blue);
            color: white;
        }

        .tab-content {
            display: none;
        }

        .tab-content.active {
            display: block;
        }

        pre {
            background: var(--bg-primary);
            padding: 1rem;
            border-radius: 8px;
            overflow-x: auto;
            font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
            font-size: 0.85rem;
            line-height: 1.5;
            white-space: pre-wrap;
            word-break: break-word;
        }

        .children-list {
            margin-top: 1rem;
        }

        .child-item {
            background: var(--bg-primary);
            padding: 0.75rem 1rem;
            border-radius: 6px;
            margin-bottom: 0.5rem;
            font-family: 'SF Mono', monospace;
            font-size: 0.85rem;
        }

        .child-count {
            color: var(--accent-blue);
        }

        .child-size {
            color: var(--accent-orange);
        }

        .child-field {
            color: var(--accent-green);
        }

        .child-type {
            color: var(--text-primary);
        }

        .process-info {
            background: var(--bg-secondary);
            border-radius: 12px;
            padding: 1.5rem;
            border: 1px solid var(--border-color);
        }

        .process-info dl {
            display: grid;
            grid-template-columns: auto 1fr;
            gap: 0.5rem 1rem;
        }

        .process-info dt {
            color: var(--text-secondary);
            font-weight: 500;
        }

        .process-info dd {
            font-family: 'SF Mono', monospace;
        }

        .expand-icon {
            transition: transform 0.2s;
        }

        .leak-card.expanded .expand-icon {
            transform: rotate(180deg);
        }

        .no-leaks {
            text-align: center;
            padding: 3rem;
            color: var(--text-secondary);
        }

        .no-leaks .icon {
            font-size: 4rem;
            margin-bottom: 1rem;
        }

        footer {
            text-align: center;
            padding: 2rem;
            color: var(--text-secondary);
            font-size: 0.85rem;
            border-top: 1px solid var(--border-color);
            margin-top: 2rem;
        }

        @media (max-width: 768px) {
            .container {
                padding: 1rem;
            }

            h1 {
                font-size: 1.8rem;
            }

            .summary-card .number {
                font-size: 2rem;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>🔍 XCTestLeaks Report</h1>
            <p class="timestamp">Generated: $timestamp</p>
        </header>

        <div class="summary-grid">
            <div class="summary-card ${if (report.leaks.isEmpty()) "success" else "danger"}">
                <div class="number">${report.leaks.size}</div>
                <div class="label">Total Leaks</div>
            </div>
            <div class="summary-card ${if (rootCycles.isEmpty()) "success" else "warning"}">
                <div class="number">${rootCycles.size}</div>
                <div class="label">Retain Cycles</div>
            </div>
            <div class="summary-card ${if (rootLeaks.isEmpty()) "success" else "danger"}">
                <div class="number">${rootLeaks.size}</div>
                <div class="label">Root Leaks</div>
            </div>
            <div class="summary-card info">
                <div class="number">${artifacts.size}</div>
                <div class="label">Artifacts</div>
            </div>
        </div>

        ${generateProcessInfoHtml(report)}

        ${if (report.leaks.isEmpty()) {
            """
            <div class="no-leaks">
                <div class="icon">✅</div>
                <h2>No Memory Leaks Detected</h2>
                <p>Great job! Your app appears to be free of memory leaks.</p>
            </div>
            """
        } else {
            generateLeaksHtml(report, artifacts)
        }}

        <footer>
            Generated by <strong>XCTestLeaks</strong> • Memory leak detection for iOS
        </footer>
    </div>

    <script>
        // Toggle leak card expansion
        document.querySelectorAll('.leak-header').forEach(header => {
            header.addEventListener('click', () => {
                header.parentElement.classList.toggle('expanded');
            });
        });

        // Tab switching
        document.querySelectorAll('.tabs').forEach(tabContainer => {
            tabContainer.querySelectorAll('.tab').forEach(tab => {
                tab.addEventListener('click', () => {
                    const targetId = tab.dataset.tab;
                    const card = tab.closest('.leak-card');

                    // Update active tab
                    tabContainer.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                    tab.classList.add('active');

                    // Update active content
                    card.querySelectorAll('.tab-content').forEach(content => {
                        content.classList.toggle('active', content.id === targetId);
                    });
                });
            });
        });

        // Expand all leaks by default if there are few
        if (document.querySelectorAll('.leak-card').length <= 5) {
            document.querySelectorAll('.leak-card').forEach(card => {
                card.classList.add('expanded');
            });
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun generateProcessInfoHtml(report: LeaksReport): String {
        if (report.summary.isEmpty()) return ""

        val items = report.summary.entries.joinToString("\n") { (key, value) ->
            "<dt>$key</dt><dd>${escapeHtml(value)}</dd>"
        }

        return """
        <div class="section">
            <h2 class="section-title">Process Information</h2>
            <div class="process-info">
                <dl>$items</dl>
            </div>
        </div>
        """
    }

    private fun generateLeaksHtml(report: LeaksReport, artifacts: List<LeakArtifact>): String {
        val artifactsByName = artifacts.associateBy { it.name }

        val leakCards = report.leaks.mapIndexed { index, leak ->
            val typeName = leak.rootTypeName ?: "Unknown"
            val sanitizedName = sanitizeFileName(typeName)
            val artifact = artifactsByName[sanitizedName]
            val leakTypeClass = if (leak.leakType == LeakType.ROOT_CYCLE) "cycle" else "leak"
            val leakTypeLabel = if (leak.leakType == LeakType.ROOT_CYCLE) "RETAIN CYCLE" else "ROOT LEAK"
            val testNameHtml = leak.testName?.let {
                """<span class="test-name">📋 ${escapeHtml(it)}</span>"""
            } ?: ""

            val childrenHtml = if (leak.children.isNotEmpty()) {
                val childItems = leak.children.joinToString("\n") { child ->
                    """
                    <div class="child-item">
                        <span class="child-count">${child.count}</span>
                        <span class="child-size">(${child.sizeHumanReadable})</span>
                        <span class="child-field">${escapeHtml(child.fieldName)}</span> →
                        <span class="child-type">${escapeHtml(child.typeName)}</span>
                        [${child.instanceSizeBytes} bytes]
                    </div>
                    """
                }
                """
                <div class="children-list">
                    <h4>Children (${leak.children.size})</h4>
                    $childItems
                </div>
                """
            } else ""

            val rawHtml = artifact?.rawContent?.let {
                """<pre>${escapeHtml(it)}</pre>"""
            } ?: "<p>No raw output available</p>"

            val jsonHtml = artifact?.jsonContent?.let {
                """<pre>${escapeHtml(it)}</pre>"""
            } ?: "<p>No JSON available</p>"

            """
            <div class="leak-card">
                <div class="leak-header">
                    <div>
                        <span class="leak-type $leakTypeClass">$leakTypeLabel</span>
                        <span class="leak-title">${escapeHtml(typeName)}</span>
                        $testNameHtml
                    </div>
                    <div class="leak-meta">
                        <span>${leak.rootCount ?: 1} instance(s)</span>
                        <span>${leak.rootSizeHumanReadable ?: "?"}</span>
                        <span class="expand-icon">▼</span>
                    </div>
                </div>
                <div class="leak-body">
                    <div class="tabs">
                        <button class="tab active" data-tab="info-$index">Info</button>
                        <button class="tab" data-tab="raw-$index">Raw Output</button>
                        <button class="tab" data-tab="json-$index">JSON</button>
                    </div>
                    <div id="info-$index" class="tab-content active">
                        ${leak.testName?.let { "<p><strong>Test:</strong> ${escapeHtml(it)}</p>" } ?: ""}
                        <p><strong>Type:</strong> ${escapeHtml(typeName)}</p>
                        <p><strong>Instance Size:</strong> ${leak.rootInstanceSizeBytes ?: "N/A"} bytes</p>
                        $childrenHtml
                    </div>
                    <div id="raw-$index" class="tab-content">
                        $rawHtml
                    </div>
                    <div id="json-$index" class="tab-content">
                        $jsonHtml
                    </div>
                </div>
            </div>
            """
        }.joinToString("\n")

        return """
        <div class="section">
            <h2 class="section-title">
                Memory Leaks
                <span class="badge">${report.leaks.size}</span>
            </h2>
            $leakCards
        </div>
        """
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(50)
    }
}