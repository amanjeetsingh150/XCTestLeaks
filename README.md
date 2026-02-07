# XCTestLeaks

A memory leak detection tool that integrates directly with XCTest. Run your existing unit tests and get per-test leak reports powered by the macOS native `leaks` tool.

## Features

- Integrates with XCTest-leak checks run automatically after each test
- Per-test leak detection with retain cycle identification
- Interactive HTML reports and JSON export
- Single command to run tests and collect results

## Requirements

- macOS with Xcode installed
- JDK 11+

## Getting Started

### Step 1: Install XCTestLeaks

```bash
brew install amanjeetsingh150/tap/xctestleaks
```

Or build from source:

```bash
./gradlew installDist
# Binary: build/install/xctestleaks/bin/xctestleaks
```

### Step 2: Add the Swift package to your Xcode project

In Xcode: **File → Add Package Dependencies…** → paste the URL:

```
https://github.com/amanjeetsingh150/XCTestLeaks
```

Add `XCTestLeaksClient` to your **test target**.

### Step 3: Set up your test target

Make sure your tests run inside the app on a Simulator. In Xcode, select your **test target** → **General** → **Testing** → set **Host Application** to your app target.

### Step 4: Opt in your test classes

Conform test classes to `LeakCheckable` and register the observer:

```swift
import XCTestLeaksClient

final class MyFeatureTests: XCTestCase, LeakCheckable {

    override class func setUp() {
        super.setUp()
        XCTestLeaksObserver.register()
    }

    func leakCheckDidComplete(with result: LeaksResult) {
        for leak in result.leaks {
            print("Leak: \(leak.rootTypeName ?? "Unknown") (\(leak.leakType))")
        }
        // Uncomment to fail the test when leaks are detected:
        // XCTAssertFalse(result.hasLeaks)
    }

    func testSomething() {
        // your test — leak check runs automatically after each test
    }
}
```

### Step 5: Run your tests

There are two ways to use XCTestLeaks:

**Option A: Local development (Xcode)**

Start the server in a terminal, then run your tests from Xcode as usual:

```bash
xctestleaks serve
```

Hit Cmd+U in Xcode — any test class conforming to `LeakCheckable` will automatically check for leaks after each test.

**Option B: CI / Automation**

Use the `run` command to do everything in one shot — starts the server, runs tests, and collects artifacts:

```bash
xctestleaks run \
  --project ./MyApp.xcodeproj \
  --scheme "MyScheme" \
  --destination "platform=iOS Simulator,OS=18.6,name=iPhone 16 Pro" \
  --output-dir ./leak_artifacts \
  --html-output
```

## Output

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
```

### Example output

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

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.