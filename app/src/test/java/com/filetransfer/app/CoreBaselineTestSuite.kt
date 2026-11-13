package com.filetransfer.app

import com.filetransfer.app.benchmark.BufferPoolMemoryBenchmarkTest
import com.filetransfer.app.benchmark.TransferThroughputBenchmarkTest
import com.filetransfer.app.core.platform.network.AndroidNetworkMonitorTest
import com.filetransfer.app.core.platform.service.NotificationManagerHelperTest
import com.filetransfer.app.core.platform.service.TransferForegroundServiceTest
import com.filetransfer.app.core.platform.share.ShareIntentParserTest
import com.filetransfer.app.core.platform.storage.StatFsAuditorTest
import com.filetransfer.app.core.security.crypto.CryptoEngineTest
import com.filetransfer.app.core.security.crypto.E2EEncryptionManagerTest
import com.filetransfer.app.core.security.crypto.SasVerificationHelperTest
import com.filetransfer.app.core.security.crypto.Sha256StreamCalculatorTest
import com.filetransfer.app.core.security.keystore.SoftwareKeyManagerTest
import com.filetransfer.app.core.security.sanitizer.FilenameSanitizerTest
import com.filetransfer.app.core.security.sanitizer.ZipSlipVulnerabilityTest
import com.filetransfer.app.core.storage.saf.SafeFileDeleterTest
import com.filetransfer.app.core.transfer.buffer.BufferPoolManagerTest
import com.filetransfer.app.core.transfer.checkpoint.CheckpointTrackerTest
import com.filetransfer.app.core.transfer.zip.ZipStreamIntegrationTest
import com.filetransfer.app.security.AntiForensicsWipeVerificationTest
import com.filetransfer.app.security.KeystoreHardwareSecurityTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Bộ kiểm thử cơ sở (Core Baseline Test Suite) cho Stage 0.
 * Tập hợp 20 bài Unit Test độc lập kiểm chứng các thành phần kỹ thuật cốt lõi:
 * Buffer Pool, Checkpoint, Cryptography, Anti-Forensics Wipe, Sanitizer, Network Monitor, Zip Stream, Zip Slip, StatFs.
 * Đóng vai trò chốt chặn kiểm thử hồi quy (Regression Barrier) cho toàn bộ quá trình tái cấu trúc Stage V2.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    BufferPoolManagerTest::class,
    FilenameSanitizerTest::class,
    ZipSlipVulnerabilityTest::class,
    SafeFileDeleterTest::class,
    Sha256StreamCalculatorTest::class,
    CryptoEngineTest::class,
    E2EEncryptionManagerTest::class,
    SasVerificationHelperTest::class,
    CheckpointTrackerTest::class,
    AndroidNetworkMonitorTest::class,
    NotificationManagerHelperTest::class,
    TransferForegroundServiceTest::class,
    ShareIntentParserTest::class,
    SoftwareKeyManagerTest::class,
    StatFsAuditorTest::class,
    ZipStreamIntegrationTest::class,
    AntiForensicsWipeVerificationTest::class,
    KeystoreHardwareSecurityTest::class,
    BufferPoolMemoryBenchmarkTest::class,
    TransferThroughputBenchmarkTest::class
)
class CoreBaselineTestSuite

