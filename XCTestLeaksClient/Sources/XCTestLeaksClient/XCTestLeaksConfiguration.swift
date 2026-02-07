import Foundation

/// Configuration for the XCTestLeaks server connection.
public struct XCTestLeaksConfiguration {
    /// Server host. Default: `"localhost"`.
    public var host: String

    /// Server port. Default: `8080`.
    public var port: Int

    /// Leak filter sent as the `filter` query parameter.
    /// Common values: `"cycles"`, `"leaks"`, `"all"`.
    /// Default: `"cycles"`.
    public var filter: String

    /// Response format sent as the `format` query parameter.
    /// Common values: `"json"`, `"json_pretty"`, `"raw"`.
    /// Default: `"json"`.
    public var format: String

    public init(host: String = "localhost", port: Int = 8080, filter: String = "cycles", format: String = "json") {
        self.host = host
        self.port = port
        self.filter = filter
        self.format = format
    }

    /// Zero-config default matching the XCTestLeaks server defaults.
    public static let `default` = XCTestLeaksConfiguration()
}
