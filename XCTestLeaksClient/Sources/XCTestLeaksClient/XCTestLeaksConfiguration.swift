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

    public init(host: String = "localhost", port: Int = 8080, filter: String = "cycles") {
        self.host = host
        self.port = port
        self.filter = filter
    }

    /// Zero-config default matching the XCTestLeaks server defaults.
    public static let `default` = XCTestLeaksConfiguration()
}
