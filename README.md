# XCTestLeaks

A Kotlin CLI tool that detects memory leaks in iOS apps during XCTest execution. It wraps macOS's native `leaks` tool, parses structured leak reports, and generates HTML reports — making it easy to catch retain cycles in your CI or local testing workflow.

## Features

- **Leak detection** via `xcrun simctl spawn` on iOS Simulators
- **Structured parsing** — raw leak output parsed into typed data (root leaks vs. retain cycles)
- **Filtering** — by leak type (`leaks`, `cycles`, `all`) and symbol exclusion
- **Output formats** — `RAW`, `JSON`, `JSON_PRETTY`
- **HTTP server mode** — REST API for on-demand leak analysis from XCTest
- **Automated test runner** — runs `xcodebuild test`, detects leaks, collects artifacts
- **HTML reports** — interactive dark-themed reports with expandable leak cards and object hierarchy

## Requirements

- macOS with Xcode installed
- JDK 11+
- An iOS Simulator (booted)

## Build

```bash
./gradlew installDist
```

The binary will be available at `build/install/xctestleaks/bin/xctestleaks`.

## Usage

### Direct Leak Analysis

```bash
# Analyze a process by name on the booted simulator
xctestleaks MyApp

# Analyze by PID
xctestleaks -p 12345

# Target a specific simulator
xctestleaks MyApp --device <UDID>

# JSON output, only retain cycles
xctestleaks MyApp --format JSON_PRETTY --filter cycles

# Exclude known leaks
xctestleaks MyApp --exclude "KnownLeakyClass"

# Summary only
xctestleaks MyApp --summary
```

### HTTP Server Mode

Start a REST API server so XCTest can request leak checks on demand:

```bash
xctestleaks serve --port 8080
```

Then from your XCTest:

```swift
let url = URL(string: "http://localhost:8080/leaks?process=MyApp&filter=cycles&format=json_pretty")!
let (data, _) = try await URLSession.shared.data(from: url)
```

**Endpoints:**

| Endpoint | Description |
|----------|-------------|
| `GET /leaks` | Run leak analysis |
| `GET /health` | Server health check |

**Query parameters:** `process`, `pid`, `device`, `filter`, `format`, `testName`, `exclude`

### Automated Test Runner

Run your XCTest suite with integrated leak detection and artifact collection:

```bash
xctestleaks run \
  --project ./MyApp.xcodeproj \
  --scheme "MyScheme" \
  --destination "platform=iOS Simulator,OS=18.6,name=iPhone 16 Pro" \
  --output-dir ./leak_artifacts \
  --html-output
```

## Output Artifacts

When leaks are detected, the `run` command produces structured artifacts:

```
leak_artifacts/
  report.html              # Interactive HTML report
  full_leak_report.json    # Complete JSON report
  xcodebuild_output.txt    # Raw xcodebuild log
  leaks/
    PPOProjectCardViewModel/
      info.txt             # Human-readable summary
      raw.txt              # Raw leak output
      leak.json            # Structured leak data
    AnyCancellable/
      info.txt
      raw.txt
      leak.json
```

### Sample Artifact — `info.txt`

From a run against [Kickstarter's iOS app](https://github.com/kickstarter/ios-oss):

```
=== Leak Instance ===
Test: -[PPOProjectCardTests testFinalizeYourPledge]
Type: PPOProjectCardViewModel
Leak Type: ROOT_CYCLE
Count: 29
Size: 1.81K
Instance Size: 320 bytes

=== Children ===
  8 (384 bytes) __strong rewardToggleTappedSubject --> Combine.PassthroughSubject<Swift.Bool, Swift.Never> [64]
  6 (304 bytes) __strong downstreams.single --> ...Conduit<...Debounce<...>> [64]
  5 (304 bytes) __strong cancellables._variant --> Swift._SetStorage<Combine.AnyCancellable> [80]
  ...
```

### Sample Artifact — `leak.json`

```json
{
    "leakType": "ROOT_CYCLE",
    "rootCount": 29,
    "rootSizeHumanReadable": "1.81K",
    "rootTypeName": "PPOProjectCardViewModel",
    "rootInstanceSizeBytes": 320,
    "children": [
        {
            "count": 8,
            "sizeHumanReadable": "384 bytes",
            "fieldName": "__strong rewardToggleTappedSubject",
            "typeName": "Combine.PassthroughSubject<Swift.Bool, Swift.Never>",
            "instanceSizeBytes": 64
        }
    ],
    "testName": "-[PPOProjectCardTests testFinalizeYourPledge]"
}
```

## How It Works

1. **`xctestleaks`** invokes `xcrun simctl spawn <device> leaks <process>` to capture memory leak data from an iOS Simulator process
2. A **token-based parser** extracts structured information — leak types, object hierarchies, sizes, and addresses
3. Leaks are classified as **ROOT_LEAK** (standalone) or **ROOT_CYCLE** (retain cycle)
4. In `run` mode, an internal HTTP server coordinates with XCTest — tests call the `/leaks` endpoint after each test case to snapshot leaks while context is fresh
5. Results are saved as **per-leak artifacts** and optionally rendered into an **HTML report**

## Tech Stack

- **Kotlin 2.1.0** (JVM 11+)
- **PicoCLI** — CLI framework
- **Kotlinx Serialization** — JSON serialization
- **JUnit 5** — testing

## License

MIT