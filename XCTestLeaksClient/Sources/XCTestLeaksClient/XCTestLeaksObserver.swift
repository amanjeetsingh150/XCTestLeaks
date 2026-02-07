import Foundation
import XCTest

/// Observes the XCTest lifecycle and pings the XCTestLeaks server after each
/// test method whose test class conforms to ``LeakCheckable``.
///
/// ## Registration
///
/// **Option A — Programmatic (recommended):**
/// ```swift
/// // In your test target's setUp or a shared base class:
/// override class func setUp() {
///     super.setUp()
///     XCTestLeaksObserver.register()
/// }
/// ```
///
/// **Option B — NSPrincipalClass:**
/// Add to your **test target's** Info.plist:
/// ```xml
/// <key>NSPrincipalClass</key>
/// <string>XCTestLeaksClient.XCTestLeaksObserver</string>
/// ```
public class XCTestLeaksObserver: NSObject, XCTestObservation {

    // MARK: - Singleton

    /// Shared instance used for both registration paths.
    public static let shared = XCTestLeaksObserver()

    /// Active configuration. Set before tests run via ``register(configuration:)``
    /// or left at its default.
    public var configuration: XCTestLeaksConfiguration = .default

    /// Called after each leak check completes. Set via ``register(configuration:onResult:)``.
    public var onResult: ((LeaksResult) -> Void)?

    // MARK: - Results storage

    private let resultsLock = NSLock()
    private var _results: [String: LeaksResult] = [:]

    /// Returns the leak-check result for a specific test case, if available.
    public func result(for testCase: XCTestCase) -> LeaksResult? {
        resultsLock.lock()
        defer { resultsLock.unlock() }
        return _results[testCase.name]
    }

    /// All leak-check results collected so far.
    public var allResults: [LeaksResult] {
        resultsLock.lock()
        defer { resultsLock.unlock() }
        return Array(_results.values)
    }

    /// Convenience: all leak instances across every test, flattened.
    public var allLeaks: [LeakInstance] {
        allResults.flatMap(\.leaks)
    }

    private func store(_ result: LeaksResult) {
        resultsLock.lock()
        defer { resultsLock.unlock() }
        _results[result.testName] = result
    }

    /// Stores the result and invokes the callback if set.
    private func emit(_ result: LeaksResult) {
        store(result)
        onResult?(result)
    }

    // MARK: - Registration

    private static var isRegistered = false

    /// Registers the observer with `XCTestObservationCenter`.
    /// Safe to call multiple times — only the first call takes effect.
    ///
    /// - Parameters:
    ///   - configuration: Server connection settings. Defaults to
    ///     ``XCTestLeaksConfiguration/default``.
    ///   - onResult: Optional callback invoked after each test's leak check
    ///     completes. Receives a ``LeaksResult`` with the decoded report.
    public static func register(
        configuration: XCTestLeaksConfiguration = .default,
        onResult: ((LeaksResult) -> Void)? = nil
    ) {
        guard !isRegistered else { return }
        isRegistered = true
        shared.configuration = configuration
        shared.onResult = onResult
        XCTestObservationCenter.shared.addTestObserver(shared)
    }

    // MARK: - NSPrincipalClass support

    /// When the runtime instantiates this class via `NSPrincipalClass`,
    /// it triggers registration automatically.
    public override init() {
        super.init()
        Self.register()
    }

    // MARK: - XCTestObservation

    public func testCaseDidFinish(_ testCase: XCTestCase) {
        guard let checkable = testCase as? LeakCheckable else { return }
        let result = performLeakCheck(testName: testCase.name)
        emit(result)
        checkable.leakCheckDidComplete(with: result)
    }

    // MARK: - Internal

    private func performLeakCheck(testName: String) -> LeaksResult {
        let processName = ProcessInfo.processInfo.processName
        let encodedTestName = testName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? testName

        let cfg = configuration
        let urlString = "http://\(cfg.host):\(cfg.port)/leaks?process=\(processName)&filter=\(cfg.filter)&format=\(cfg.format)&testName=\(encodedTestName)"

        guard let url = URL(string: urlString) else {
            print("[XCTestLeaksClient] Invalid URL: \(urlString)")
            return LeaksResult(testName: testName, report: nil, error: URLError(.badURL))
        }

        let semaphore = DispatchSemaphore(value: 0)
        var responseData: Data?
        var responseError: Error?
        var httpStatus: Int?

        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            responseData = data
            responseError = error
            httpStatus = (response as? HTTPURLResponse)?.statusCode
            semaphore.signal()
        }
        task.resume()

        let timeout: DispatchTimeInterval = .seconds(30)
        if semaphore.wait(timeout: .now() + timeout) == .timedOut {
            task.cancel()
            print("[XCTestLeaksClient] Request timed out for \(testName)")
            return LeaksResult(testName: testName, report: nil, error: URLError(.timedOut))
        }

        if let error = responseError {
            print("[XCTestLeaksClient] Error for \(testName): \(error.localizedDescription)")
            return LeaksResult(testName: testName, report: nil, error: error)
        }

        if let status = httpStatus, status != 200 {
            print("[XCTestLeaksClient] Server returned \(status) for \(testName)")
        }

        guard let data = responseData else {
            return LeaksResult(testName: testName, report: nil, error: nil)
        }

        do {
            let report = try JSONDecoder().decode(LeaksReport.self, from: data)
            if report.leaks.isEmpty {
                print("[XCTestLeaksClient] \(testName): No leaks detected")
            } else {
                let types = report.leaks.compactMap(\.rootTypeName).joined(separator: ", ")
                print("[XCTestLeaksClient] \(testName): \(report.leaks.count) leak(s) — \(types)")
            }
            return LeaksResult(testName: testName, report: report, error: nil)
        } catch {
            print("[XCTestLeaksClient] Failed to decode response for \(testName): \(error)")
            return LeaksResult(testName: testName, report: nil, error: error)
        }
    }
}