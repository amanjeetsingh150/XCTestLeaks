# XCTestLeaks

A tool that integrates with your iOS testing workflow to surface memory leaks. It uses macOS's native `leaks` tool under the hood and currently integrates with XCTest unit tests.

## Features

- Memory leak detection per test case
- Filter only retain cycles
- JSON report export
- Interactive HTML reports
- Independent HTTP server (`xctestleaks serve`)

## Requirements

- macOS with Xcode installed
- JDK 11+

## Getting Started

### Step 1: Build XCTestLeaks

```bash
./gradlew installDist
```

The binary will be available at `build/install/xctestleaks/bin/xctestleaks`.

---

### Step 2: Ensure your tests are hosted on a Simulator

XCTestLeaks analyzes the host app's process for leaks, so your unit tests must run inside the app on a Simulator.

In Xcode, select your **test target** → **General** → **Testing** → set **Host Application** to your app target. This ensures tests execute within the app process rather than a standalone test runner.

---

### Step 3: Add the XCTestLeaksClient SPM package

Add `XCTestLeaksClient` as a dependency to your **test target** via Swift Package Manager:

```
https://github.com/nicklama/xctestleaks
```

In Xcode: **File → Add Package Dependencies…** → paste the repository URL → add `XCTestLeaksClient` to your test target.

Then conform your test classes to `LeakCheckable` to opt in:

```swift
import XCTestLeaksClient

final class MyFeatureTests: XCTestCase, LeakCheckable {
    // your tests — no tearDown boilerplate needed
}
```

#### Register the observer

**Option A — Programmatic (recommended):**

```swift
override class func setUp() {
    super.setUp()
    XCTestLeaksObserver.register()
}
```

**Option B — NSPrincipalClass:**

Add this to your test target's `Info.plist`:

```xml
<key>NSPrincipalClass</key>
<string>XCTestLeaksClient.XCTestLeaksObserver</string>
```

The observer auto-detects your host app's process name and pings the leaks server after each test method in any `LeakCheckable` class.

> **Tip:** To customize the server connection, pass a configuration:
> ```swift
> XCTestLeaksObserver.register(
>     configuration: .init(host: "localhost", port: 9090, filter: "all")
> )
> ```

<details>
<summary><strong>Alternative: Manual setup without SPM</strong></summary>

Add the following function to a shared test utilities file (e.g. `XCTestCase+Leaks.swift`):

```swift
func pingLeaksEndpoint(
    from testCase: XCTestCase,
    url: URL? = nil,
    timeout: TimeInterval = 10.0,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    let effectiveURL: URL = {
        if let url = url { return url }
        let testName = testCase.name
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)
            ?? testCase.name
        return URL(string:
            "http://localhost:8080/leaks?process=MyApp&filter=cycles&testName=\(testName)"
        )!
    }()

    let leaksExpectation = testCase.expectation(
        description: "Call /leaks endpoint"
    )

    let task = URLSession.shared.dataTask(with: effectiveURL) { data, response, error in
        XCTAssertNil(
            error,
            "Expected no error when calling /leaks",
            file: file,
            line: line
        )

        if let httpResponse = response as? HTTPURLResponse {
            XCTAssertEqual(
                httpResponse.statusCode,
                200,
                "Expected 200 OK from /leaks",
                file: file,
                line: line
            )
        }

        if let data = data, let body = String(data: data, encoding: .utf8) {
            print("Leaks endpoint output:\n\(body)")
        }

        leaksExpectation.fulfill()
    }

    task.resume()
    testCase.wait(for: [leaksExpectation], timeout: timeout)
}
```

Then call it from your test class:

```swift
override func tearDown() {
    super.tearDown()
    pingLeaksEndpoint(from: self)
}
```

> **Note:** Replace `MyApp` in the URL with your app's process name.

</details>

---

### Step 4: Run your tests with XCTestLeaks

Use the `run` command to start the leaks server, execute your tests, and collect results in one step:

```bash
xctestleaks run \
  --project ./MyApp.xcodeproj \
  --scheme "MyScheme" \
  --destination "platform=iOS Simulator,OS=18.6,name=iPhone 16 Pro" \
  --output-dir ./leak_artifacts \
  --html-output
```

XCTestLeaks will:
1. Start the leaks server
2. Run `xcodebuild test`
3. Collect leak reports from each test's `tearDown` call
4. Save artifacts and generate an HTML report

---

## Output Artifacts

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

### Sample: `info.txt`

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

### Sample: `leak.json`

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

## License

MIT