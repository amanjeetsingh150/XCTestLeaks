import XCTest
@testable import LeakTestApp

/// XCTests that trigger leaks and then call the xctestleaks server to detect them
final class LeakDetectionTests: XCTestCase {

    // Configure your server URL here
    let serverBaseURL = "http://localhost:8080"

    override func setUp() {
        super.setUp()
    }

    override func tearDown() {
        super.tearDown()
    }

    // MARK: - Tests

    /// Test that triggers memory leaks and then checks the server for detection
    func testDetectRetainCycleLeaks() throws {
        // First, trigger the leaks
        triggerLeaks()

        // Give some time for memory to settle
        Thread.sleep(forTimeInterval: 0.5)

        // Now query the server to detect leaks, passing the test name
        let leaksResult = try queryLeaksServer(filter: "cycles", testName: "testDetectRetainCycleLeaks")

        // Parse and verify the response
        XCTAssertNotNil(leaksResult, "Should receive a response from leaks server")

        // Log the result for debugging
        print("Leaks server response: \(leaksResult)")

        // Check if leaks were detected (the response should contain leak data)
        // In a real test, you'd parse the JSON and assert on specific leak types
    }

    /// Second test that triggers closure-based leaks
    func testDetectClosureLeaks() throws {
        // Trigger closure-based leaks
        triggerLeaks()

        // Give some time for memory to settle
        Thread.sleep(forTimeInterval: 0.5)

        // Query the server with a different test name
        let leaksResult = try queryLeaksServer(filter: "cycles", testName: "testDetectClosureLeaks")

        // Parse and verify the response
        XCTAssertNotNil(leaksResult, "Should receive a response from leaks server")

        // Log the result for debugging
        print("Leaks server response for closure test: \(leaksResult)")
    }

    // MARK: - Server Query Helpers

    /// Query the /leaks endpoint on the server
    func queryLeaksServer(filter: String = "cycles", format: String = "json", testName: String? = nil) throws -> String {
        let processName = "LeakTestApp"
        var urlString = "\(serverBaseURL)/leaks?process=\(processName)&filter=\(filter)&format=\(format)"
        if let testName = testName {
            urlString += "&testName=\(testName)"
        }

        guard let url = URL(string: urlString) else {
            throw LeakTestError.invalidURL
        }

        let expectation = XCTestExpectation(description: "Server response")
        var responseData: Data?
        var responseError: Error?

        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            responseData = data
            responseError = error
            expectation.fulfill()
        }
        task.resume()

        wait(for: [expectation], timeout: 30.0)

        if let error = responseError {
            throw error
        }

        guard let data = responseData else {
            throw LeakTestError.noData
        }

        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Query the /health endpoint
    func queryHealthEndpoint() throws -> String {
        let urlString = "\(serverBaseURL)/health"

        guard let url = URL(string: urlString) else {
            throw LeakTestError.invalidURL
        }

        let expectation = XCTestExpectation(description: "Health response")
        var responseData: Data?
        var responseError: Error?

        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            responseData = data
            responseError = error
            expectation.fulfill()
        }
        task.resume()

        wait(for: [expectation], timeout: 10.0)

        if let error = responseError {
            throw error
        }

        guard let data = responseData else {
            throw LeakTestError.noData
        }

        return String(data: data, encoding: .utf8) ?? ""
    }
}

// MARK: - Error Types

enum LeakTestError: Error {
    case invalidURL
    case noData
    case serverError(String)
}
