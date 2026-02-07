import XCTest
import XCTestLeaksClient

@testable import LeakTestApp

/// XCTests that trigger intentional leaks and verify they are correctly surfaced.
final class LeakDetectionTests: XCTestCase, LeakCheckable {

    override class func setUp() {
        super.setUp()
        XCTestLeaksObserver.register()
    }

    // MARK: - LeakCheckable

    func leakCheckDidComplete(with result: LeaksResult) {
        NSLog("Leaked instance type: \(result.leaks.map( { $0.type } ))")
        
        XCTAssertNotNil(result.report, "Report should be decoded successfully")
        XCTAssertTrue(result.hasLeaks, "Intentional leaks should be detected")

        let typeNames = result.leaks.compactMap(\.rootTypeName)
        let hasExpectedLeak = typeNames.contains(where: {
            $0.contains("LeakyManager") || $0.contains("LeakyWorker") || $0.contains("LeakyClosureHolder")
        })
        XCTAssertTrue(hasExpectedLeak, "Should detect LeakyManager, LeakyWorker, or LeakyClosureHolder — got: \(typeNames)")
    }

    // MARK: - Tests

    func testDetectRetainCycleLeaks() throws {
        triggerLeaks()
        Thread.sleep(forTimeInterval: 2)
    }

    func testDetectClosureLeaks() throws {
        triggerLeaks()
        Thread.sleep(forTimeInterval: 0.5)
    }
}
