package com.teamshare.app

import com.teamshare.app.benchmark.BufferPoolMemoryBenchmarkTest
import com.teamshare.app.benchmark.TransferThroughputBenchmarkTest
import com.teamshare.app.core.platform.device.DeviceIdentityManagerTest
import com.teamshare.app.core.platform.network.AndroidNetworkMonitorTest
import com.teamshare.app.core.platform.service.NotificationManagerHelperTest
import com.teamshare.app.core.platform.service.TransferForegroundServiceTest
import com.teamshare.app.core.platform.share.ShareCodeHelperTest
import com.teamshare.app.core.platform.share.ShareIntentParserTest
import com.teamshare.app.core.platform.storage.StatFsAuditorTest
import com.teamshare.app.core.platform.window.WindowSecurityManagerTest
import com.teamshare.app.core.protocol.BinaryFrameProtocolTest
import com.teamshare.app.core.security.crypto.CryptoEngineTest
import com.teamshare.app.core.security.crypto.E2EEncryptionManagerTest
import com.teamshare.app.core.security.crypto.SasVerificationHelperTest
import com.teamshare.app.core.security.crypto.Sha256StreamCalculatorTest
import com.teamshare.app.core.security.keystore.KeystoreHardeningTest
import com.teamshare.app.core.security.keystore.SoftwareKeyManagerTest
import com.teamshare.app.core.security.sanitizer.FilenameSanitizerTest
import com.teamshare.app.core.security.sanitizer.SafeLoggerTest
import com.teamshare.app.core.security.sanitizer.ZipSlipVulnerabilityTest
import com.teamshare.app.core.storage.saf.FileProviderGatewayTest
import com.teamshare.app.core.storage.saf.SafeFileDeleterTest
import com.teamshare.app.core.transfer.TransferCoordinatorTest
import com.teamshare.app.core.transfer.buffer.BufferPoolManagerTest
import com.teamshare.app.core.transfer.checkpoint.CheckpointTrackerTest
import com.teamshare.app.core.transfer.lan.LanTransferIntegrationTest
import com.teamshare.app.core.transfer.lan.SessionApprovalFlowIntegrationTest
import com.teamshare.app.core.transfer.relay.RelayClientEngineIntegrationTest
import com.teamshare.app.core.transfer.zip.ZipStreamIntegrationTest
import com.teamshare.app.data.local.db.AppDatabaseCallbackTest
import com.teamshare.app.data.local.db.dao.AuditLogDaoTest
import com.teamshare.app.data.local.db.dao.TransferSessionDaoTest
import com.teamshare.app.data.local.db.mapper.MapperTest
import com.teamshare.app.data.repository.TransferAndAuditRepositoryTest
import com.teamshare.app.domain.model.TransferSessionTest
import com.teamshare.app.domain.statemachine.SessionStateMachineTest
import com.teamshare.app.domain.usecase.SessionUseCasesTest
import com.teamshare.app.domain.usecase.platform.OpenFileSecurelyUseCaseTest
import com.teamshare.app.e2e.LanTransferE2ETest
import com.teamshare.app.e2e.PerformanceResourceE2ETest
import com.teamshare.app.e2e.RelayTransferE2ETest
import com.teamshare.app.e2e.ResumeTransferE2ETest
import com.teamshare.app.e2e.SystemSecurityHardeningE2ETest
import com.teamshare.app.presentation.viewmodel.AuditViewModelTest
import com.teamshare.app.presentation.viewmodel.ReceiverViewModelTest
import com.teamshare.app.presentation.viewmodel.SenderViewModelTest
import com.teamshare.app.presentation.viewmodel.TransferViewModelTest
import com.teamshare.app.security.AntiForensicsWipeVerificationTest
import com.teamshare.app.security.KeystoreHardwareSecurityTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * BỘ KIỂM THỬ TỔNG HỢP TOÀN HỆ THỐNG TEAMSHARE (STAGE 8 FINAL ACCEPTANCE TEST SUITE).
 * Tập hợp toàn bộ 49 lớp kiểm thử đơn vị, kiểm chuẩn hiệu năng, kiểm thử an ninh chuyên sâu và End-to-End
 * trải dài trên toàn bộ các tầng kiến trúc Clean Architecture:
 * - Benchmark & Performance (NFR-PERF-01, NFR-PERF-02, NFR-PERF-03)
 * - Security Hardening & Cryptography (STRIDE / OWASP MASVS)
 * - Core Platform, Protocol & Storage (BufferPool, Checkpoint, ZipStream, StatFs, FLAG_SECURE)
 * - Data Layer (Room Encrypted SQLCipher, DAOs, Repositories)
 * - Domain Layer (UseCases, StateMachine, Models)
 * - Presentation Layer (ViewModels Jetpack Compose M3)
 * - End-to-End Integration (LAN TLS 1.3, Go Relay E2EE, Resume Checkpoint)
 *
 * Chốt chặn chất lượng tối cao khẳng định hệ thống đạt 100% tiêu chuẩn nghiệm thu đề tài tốt nghiệp KMA.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    // 1. Benchmarks & Performance
    BufferPoolMemoryBenchmarkTest::class,
    TransferThroughputBenchmarkTest::class,
    PerformanceResourceE2ETest::class,

    // 2. Core Security & Cryptography
    CryptoEngineTest::class,
    E2EEncryptionManagerTest::class,
    SasVerificationHelperTest::class,
    Sha256StreamCalculatorTest::class,
    SoftwareKeyManagerTest::class,
    KeystoreHardeningTest::class,
    KeystoreHardwareSecurityTest::class,
    AntiForensicsWipeVerificationTest::class,
    FilenameSanitizerTest::class,
    SafeLoggerTest::class,
    ZipSlipVulnerabilityTest::class,

    // 3. Platform & Services
    DeviceIdentityManagerTest::class,
    AndroidNetworkMonitorTest::class,
    NotificationManagerHelperTest::class,
    TransferForegroundServiceTest::class,
    ShareCodeHelperTest::class,
    ShareIntentParserTest::class,
    StatFsAuditorTest::class,
    WindowSecurityManagerTest::class,

    // 4. Protocols, Buffer & Storage
    BinaryFrameProtocolTest::class,
    BufferPoolManagerTest::class,
    CheckpointTrackerTest::class,
    FileProviderGatewayTest::class,
    SafeFileDeleterTest::class,
    ZipStreamIntegrationTest::class,

    // 5. Transfer Engines (LAN & Relay)
    LanTransferIntegrationTest::class,
    SessionApprovalFlowIntegrationTest::class,
    RelayClientEngineIntegrationTest::class,
    TransferCoordinatorTest::class,

    // 6. Data Layer (Room SQLCipher & Repositories)
    AppDatabaseCallbackTest::class,
    AuditLogDaoTest::class,
    TransferSessionDaoTest::class,
    MapperTest::class,
    TransferAndAuditRepositoryTest::class,

    // 7. Domain Layer
    TransferSessionTest::class,
    SessionStateMachineTest::class,
    SessionUseCasesTest::class,
    OpenFileSecurelyUseCaseTest::class,

    // 8. Presentation Layer ViewModels
    SenderViewModelTest::class,
    ReceiverViewModelTest::class,
    TransferViewModelTest::class,
    AuditViewModelTest::class,

    // 9. Stage 8 End-to-End Test Suite
    LanTransferE2ETest::class,
    RelayTransferE2ETest::class,
    ResumeTransferE2ETest::class,
    SystemSecurityHardeningE2ETest::class
)
class FinalSystemTestSuite

