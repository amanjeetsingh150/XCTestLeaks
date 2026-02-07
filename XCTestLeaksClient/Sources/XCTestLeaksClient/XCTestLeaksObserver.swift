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

    // MARK: - Registration

    private static var isRegistered = false

    /// Registers the observer with `XCTestObservationCenter`.
    /// Safe to call multiple times — only the first call takes effect.
    ///
    /// - Parameter configuration: Server connection settings. Defaults to
    ///   ``XCTestLeaksConfiguration/default``.
    public static func register(configuration: XCTestLeaksConfiguration = .default) {
        guard !isRegistered else { return }
        isRegistered = true
        shared.configuration = configuration
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
        guard testCase is LeakCheckable else { return }

        let processName = ProcessInfo.processInfo.processName
        let rawTestName = testCase.name
        let encodedTestName = rawTestName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? rawTestName

        let cfg = configuration
        let urlString = "http://\(cfg.host):\(cfg.port)/leaks?process=\(processName)&filter=\(cfg.filter)&testName=\(encodedTestName)"

        guard let url = URL(string: urlString) else {
            print("[XCTestLeaksClient] Invalid URL: \(urlString)")
            return
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
            print("[XCTestLeaksClient] Request timed out for \(rawTestName)")
            return
        }

        if let error = responseError {
            print("[XCTestLeaksClient] Error for \(rawTestName): \(error.localizedDescription)")
            return
        }

        if let status = httpStatus, status != 200 {
            print("[XCTestLeaksClient] Server returned \(status) for \(rawTestName)")
        }

        if let data = responseData, let body = String(data: data, encoding: .utf8) {
            print("[XCTestLeaksClient] \(rawTestName): \(body)")
        }
    }
}
