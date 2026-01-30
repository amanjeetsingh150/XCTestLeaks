import Foundation

// MARK: - Intentional Memory Leak via Retain Cycle

/// A class that intentionally creates a retain cycle for testing leak detection.
/// The cycle is: LeakyManager -> LeakyWorker -> LeakyManager (strong reference cycle)
class LeakyManager {
    var worker: LeakyWorker?
    var name: String

    init(name: String) {
        self.name = name
    }

    /// Creates a retain cycle that will be detected by the leaks tool
    func createRetainCycle() {
        worker = LeakyWorker(manager: self)
    }

    deinit {
        print("LeakyManager deinit - this won't be called due to retain cycle!")
    }
}

/// Worker class that holds a strong reference back to its manager, creating a cycle
class LeakyWorker {
    // BUG: This should be `weak` to avoid retain cycle
    var manager: LeakyManager?
    var data: [String] = []

    init(manager: LeakyManager) {
        self.manager = manager
        // Allocate some data to make the leak more visible
        for i in 0..<100 {
            data.append("Leaked data item \(i)")
        }
    }

    deinit {
        print("LeakyWorker deinit - this won't be called due to retain cycle!")
    }
}

// MARK: - Closure Capture Leak

/// Another leak pattern: closure capturing self strongly
class LeakyClosureHolder {
    var callback: (() -> Void)?
    var value: Int = 42

    func setupLeakyClosure() {
        // BUG: Capturing self strongly in closure that's stored on self
        callback = {
            print("Value is \(self.value)")
        }
    }

    deinit {
        print("LeakyClosureHolder deinit - won't be called!")
    }
}

// MARK: - Leak Trigger Functions

/// Call this to create memory leaks that can be detected
func triggerLeaks() {
    // Create retain cycle leak
    let manager = LeakyManager(name: "TestManager")
    manager.createRetainCycle()
    // manager goes out of scope but won't be deallocated due to cycle

    // Create closure capture leak
    let holder = LeakyClosureHolder()
    holder.setupLeakyClosure()
    // holder goes out of scope but won't be deallocated
}