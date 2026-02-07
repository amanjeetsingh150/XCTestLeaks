import Foundation

// MARK: - LeakType

/// The kind of leak detected by the `leaks` tool.
public enum LeakType: String, Codable, Sendable {
    /// A standalone memory leak (no reference cycle).
    case rootLeak = "ROOT_LEAK"
    /// A retain cycle where objects hold strong references to each other.
    case rootCycle = "ROOT_CYCLE"
}

// MARK: - RootLeakChildInfo

/// Metadata for a child object under a root leak / cycle.
public struct RootLeakChildInfo: Codable, Sendable {
    public let count: Int
    public let sizeHumanReadable: String
    public let fieldName: String
    public let typeName: String
    public let instanceSizeBytes: Int
}

// MARK: - LeakInstance

/// A single leak instance from the server report.
public struct LeakInstance: Codable, Sendable {
    // Legacy fields (may be nil for root-leak/cycle format)
    public let address: String?
    public let sizeBytes: Int?
    public let type: String?
    public let stackTrace: [String]
    public let rawLines: [String]

    // Root leak / cycle fields
    public let leakType: LeakType
    public let rootCount: Int?
    public let rootSizeHumanReadable: String?
    public let rootTypeName: String?
    public let rootInstanceSizeBytes: Int?
    public let children: [RootLeakChildInfo]
    public let testName: String?
}

// MARK: - LeaksInvocationParams

/// The parameters that were used to invoke the `leaks` tool.
public struct LeaksInvocationParams: Codable, Sendable {
    public let pid: Int?
    public let processName: String?
    public let deviceId: String
    public let excludeSymbols: [String]
}

// MARK: - LeaksReport

/// Top-level report returned by the XCTestLeaks server (`format=json`).
public struct LeaksReport: Codable, Sendable {
    public let params: LeaksInvocationParams
    public let invocationTime: String
    public let leaks: [LeakInstance]
    public let summary: [String: String]
    public let rawOutput: String
}