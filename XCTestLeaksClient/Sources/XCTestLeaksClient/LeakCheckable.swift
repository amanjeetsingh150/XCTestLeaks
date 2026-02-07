import Foundation

/// Conform your `XCTestCase` subclass to `LeakCheckable` to opt in to
/// automatic leak checking after each test method.
///
/// You **must** implement ``leakCheckDidComplete(with:)`` — the compiler
/// enforces this so you always handle the result:
///
///     final class MyFeatureTests: XCTestCase, LeakCheckable {
///         func leakCheckDidComplete(with result: LeaksResult) {
///             XCTAssertFalse(result.hasLeaks)
///         }
///     }
public protocol LeakCheckable: AnyObject {
    /// Called by the observer after the leak check completes for each test.
    func leakCheckDidComplete(with result: LeaksResult)
}