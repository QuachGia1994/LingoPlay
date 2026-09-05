import XCTest
@testable import LingoPlay

final class Stage22RuntimeIntegrityTests: XCTestCase {
    func testRecoverySchemaMigratesLegacyAndRejectsUnknownFutureVersion() {
        XCTAssertTrue(ProcessingRecoverySchema.isSupported(nil))
        XCTAssertTrue(ProcessingRecoverySchema.isSupported(0))
        XCTAssertTrue(ProcessingRecoverySchema.isSupported(ProcessingRecoverySchema.currentVersion))
        XCTAssertFalse(ProcessingRecoverySchema.isSupported(-1))
        XCTAssertFalse(ProcessingRecoverySchema.isSupported(ProcessingRecoverySchema.currentVersion + 1))
    }
}
