import Foundation

/// Marker protocol for test classes that opt in to automatic leak checking.
///
/// Conform your `XCTestCase` subclass to `LeakCheckable` and
/// `XCTestLeaksObserver` will call the leaks endpoint after each test method.
///
///     final class MyFeatureTests: XCTestCase, LeakCheckable { … }
public protocol LeakCheckable {}
