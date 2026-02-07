import Foundation

/// The outcome of a single test case's leak check.
public struct LeaksResult: Sendable {
    /// The XCTest name, e.g. `"-[MyTests testSomething]"`.
    public let testName: String

    /// The decoded server report, if the request succeeded.
    public let report: LeaksReport?

    /// The error, if the request or decoding failed.
    public let error: (any Error)?

    /// Convenience: the leak instances from the report (empty on failure).
    public var leaks: [LeakInstance] { report?.leaks ?? [] }

    /// Whether any leaks were detected.
    public var hasLeaks: Bool { !leaks.isEmpty }
}