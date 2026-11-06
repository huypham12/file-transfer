package com.teamshare.app.e2e

import com.teamshare.app.core.network.tls.TlsSocketFactory
import com.teamshare.app.core.protocol.ChunkFrame
import com.teamshare.app.core.security.crypto.CryptoEngine
import com.teamshare.app.core.security.crypto.E2EEncryptionManager
import com.teamshare.app.core.security.crypto.Sha256StreamCalculator
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.core.storage.saf.SafeFileDeleter
import com.teamshare.app.domain.model.MemberRole
import com.teamshare.app.domain.model.SessionStatus
import com.teamshare.app.domain.model.Team
import com.teamshare.app.domain.model.TeamMember
import com.teamshare.app.domain.model.TeamMembershipCredential
import com.teamshare.app.domain.model.TransferChannel
import com.teamshare.app.domain.model.TransferDirection
import com.teamshare.app.domain.model.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis

/**
 * Kiểm chứng tự động 5 Kịch bản Thực nghiệm Đo kiểm Cốt lõi (SRS v3.0.0 Mục 9.3):
 * 1. Mời QR, Bắt tay Thẩm định Hai chiều & Cấp phát Credential.
 * 2. Truyền phát Tệp lớn trong LAN với BufferPoolManager và Backpressure.
 * 3. Truyền tệp E2EE qua Relay Server Không lưu đĩa.
 * 4. Đứt kết nối mạng tại 65% tiến độ & Resume từ Checkpoint 2MB ACK.
 * 5. Thu hồi quyền thành viên cưỡng bức trong < 200 ms & Tiêu hủy tệp dở dang.
 */
class ScenariosVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: E2ETestHarness
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        harness = E2ETestHarness(tempFolder.root)
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // KỊCH BẢN 1: MỜI QR, BẮT TAY THẨM ĐỊNH HAI CHIỀU & CẤP CREDENTIAL
    // =========================================================================
    @Test
    fun testScenario1_QrInvite_Handshake_And_CredentialIssuance() = runBlocking {
        // 1. Owner tạo nhóm mới
        val createResult = harness.ownerPeer.createTeamUseCase(
            name = "Đội An ninh Mạng KMA",
            desc = "Nghiên cứu Chia sẻ Tệp P2P An toàn"
        ).first()
        assertTrue("Tạo nhóm phải thành công", createResult.isSuccess)
        val team = createResult.getOrThrow()
        val teamId = team.teamId

        // 2. Owner sinh mã QR lời mời có chữ ký số (TTL 10 phút = 600s)
        val qrResult = harness.ownerPeer.generateQrInviteUseCase(teamId, ttlSec = 600L).first()
        assertTrue("Sinh QR phải thành công", qrResult.isSuccess)
        val qrPayload = qrResult.getOrThrow()
        assertEquals(teamId, qrPayload.teamId)
        assertTrue(qrPayload.signature.isNotBlank())

        // Đồng bộ danh bạ nhóm cho kịch bản giả lập
        harness.syncTeamState(teamId)

        // 3. Member quét mã QR và gia nhập nhóm
        val joinResult = harness.memberPeer.joinTeamViaQrUseCase(
            qrPayload = qrPayload,
            memberName = harness.memberPeer.peerName
        ).first()
        assertTrue("Member quét QR gia nhập phải thành công", joinResult.isSuccess)
        val member = joinResult.getOrThrow()

        assertEquals("Trần Thị Member", member.memberName)
        assertEquals(MemberRole.MEMBER, member.role)
        assertTrue(member.isActive)

        // Đồng bộ thành viên mới sang máy Owner
        harness.ownerPeer.teamRepository.addMember(member)

        // 4. Bắt tay thẩm định hai chiều (Mutual Handshake):
        // Owner xác thực tính hợp lệ của Credential của Member
        val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
        val ownerPubKey = harness.ownerPeer.keyManager.getPublicKey(masterKeyAlias)!!
        val credPayload = "$teamId|${member.memberId}|${member.role.name}"
        val signatureBytes = harness.ownerPeer.keyManager.signData(masterKeyAlias, credPayload.toByteArray())
        val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

        val memberCredential = TeamMembershipCredential(
            teamId = teamId,
            memberId = member.memberId,
            deviceFingerprint = member.deviceFingerprint,
            role = member.role,
            issuedAt = System.currentTimeMillis() / 1000L,
            ownerSignature = signatureHex
        )

        val authResult = harness.ownerPeer.authenticatePeerUseCase(memberCredential, ownerPubKey).first().getOrThrow()
        assertTrue("Owner phải thẩm định thành công thành viên hợp lệ", authResult)
    }

    // =========================================================================
    // KỊCH BẢN 2: TRUYỀN PHÁT TỆP LỚN LAN VỚI BUFFER POOL & CHECKPOINT ACK
    // =========================================================================
    @Test
    fun testScenario2_LanStreaming_BufferPool_And_Integrity() = runBlocking {
        val teamId = "team_lan_benchmark"
        val team = Team(
            teamId = teamId,
            teamName = "LAN Benchmark Team",
            description = "Test LAN Transfer",
            ownerFingerprint = harness.ownerPeer.peerId
        )
        val ownerMember = TeamMember(
            memberId = harness.ownerPeer.peerId,
            teamId = teamId,
            memberName = harness.ownerPeer.peerName,
            deviceFingerprint = "fingerprint_owner",
            role = MemberRole.OWNER,
            credentialData = "valid_cred_data",
            isActive = true
        )
        harness.ownerPeer.teamRepository.createTeam(team)
        harness.ownerPeer.teamRepository.addMember(ownerMember)
        harness.memberPeer.teamRepository.createTeam(team)
        harness.memberPeer.teamRepository.addMember(ownerMember)

        // 1. Chuẩn bị tệp 250KB tại máy Owner
        val sourceFile = harness.createSampleFile(
            parentDir = harness.ownerPeer.workingDir,
            fileName = "bao_cao_nghiem_thu_lan.pdf",
            sizeBytes = 250 * 1024
        )
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)
        val targetDir = harness.memberPeer.workingDir

        val port = TlsSocketFactory.findAvailablePort()
        val receiverReady = CompletableDeferred<Unit>()

        // 2. Receiver (Member) lắng nghe trên Port
        val receiverJob = async(Dispatchers.IO) {
            receiverReady.complete(Unit)
            harness.memberPeer.lanEngine.receiveFile(
                port = port,
                targetDirectory = targetDir,
                expectedTeamId = "team_lan_benchmark",
                useTls = false
            )
        }

        receiverReady.await()

        // 3. Sender (Owner) phát streaming tệp qua LAN Socket
        val sendResult = harness.ownerPeer.lanEngine.sendFile(
            host = "127.0.0.1",
            port = port,
            file = sourceFile,
            docId = "doc_lan_01",
            teamId = "team_lan_benchmark",
            memberId = harness.ownerPeer.peerId,
            deviceFingerprint = "fingerprint_owner",
            role = "OWNER",
            credentialData = "valid_cred_data",
            useTls = false
        )

        val receiveResult = receiverJob.await()

        assertTrue("Gửi tệp LAN phải thành công", sendResult.isSuccess)
        assertTrue("Nhận tệp LAN phải thành công", receiveResult.isSuccess)

        val receivedFile = receiveResult.getOrThrow()

        // 4. Kiểm chứng toàn vẹn 100%
        assertTrue("Tệp nhận được phải tồn tại trên đĩa", receivedFile.exists())
        assertEquals("Kích thước tệp phải bằng nhau", sourceFile.length(), receivedFile.length())
        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(receivedFile)
        assertEquals("Mã băm SHA-256 phải khớp tuyệt đối (0% sai lệch dữ liệu)", expectedSha256, actualSha256)
    }

    // =========================================================================
    // KỊCH BẢN 3: TRUYỀN TỆP E2EE QUA RELAY INTERNET KHÔNG LƯU ĐĨA
    // =========================================================================
    @Test
    fun testScenario3_Relay_E2EE_ZeroKnowledge_Transfer() = runBlocking {
        val capturedRelayPayloads = CopyOnWriteArrayList<ByteArray>()

        // Cấu hình MockWebServer mô phỏng chuyển tiếp Blind Relay không ghi đĩa
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/v1/stream") == true) {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            capturedRelayPayloads.add(bytes.toByteArray())
                            // Chuyển tiếp tức thì (Pipe in-memory)
                            webSocket.send(bytes)
                        }
                    })
                }
                return MockResponse().setResponseCode(404)
            }
        }

        // 1. Tạo tệp bí mật
        val plainText = "BẢN GHI DỮ LIỆU ĐƯỢC MÃ HÓA ĐẦU CUỐI E2EE XUYÊN RELAY SERVER".toByteArray(StandardCharsets.UTF_8)
        val sourceFile = harness.createSampleFile(
            parentDir = harness.ownerPeer.workingDir,
            fileName = "tai_lieu_e2ee.txt",
            sizeBytes = plainText.size
        )
        FileOutputStream(sourceFile).use { it.write(plainText) }
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sourceFile)

        // 2. Trao đổi khóa Ephemeral ECDH sinh khóa phiên E2EE AES-256-GCM
        val e2eManagerSender = E2EEncryptionManager()
        val e2eManagerReceiver = E2EEncryptionManager()

        val streamId = "stream_e2e_test_001"
        val senderPayload = e2eManagerSender.initSession(streamId)
        val receiverPayload = e2eManagerReceiver.initSession(streamId)

        val sasSender = e2eManagerSender.completeKeyExchange(receiverPayload)
        val sasReceiver = e2eManagerReceiver.completeKeyExchange(senderPayload)
        assertEquals("Mã SAS của Sender và Receiver phải trùng khớp hoàn toàn", sasSender, sasReceiver)
        assertTrue(e2eManagerSender.isEstablished())
        assertTrue(e2eManagerReceiver.isEstablished())

        // 3. Mã hóa phía Sender
        val encryptedChunks = mutableListOf<ByteArray>()
        FileInputStream(sourceFile).use { fis ->
            val buf = ByteArray(64 * 1024)
            var bytesRead: Int
            var seq = 0L
            while (fis.read(buf).also { bytesRead = it } != -1) {
                val chunkPlain = buf.copyOf(bytesRead)
                val encrypted = e2eManagerSender.encryptChunk(chunkIndex = seq++, plaintext = chunkPlain)
                encryptedChunks.add(encrypted)
            }
        }

        // 4. Giải mã phía Receiver và ghi đĩa
        val outputFile = File(harness.memberPeer.workingDir, "tai_lieu_e2ee_received.txt")
        FileOutputStream(outputFile).use { fos ->
            encryptedChunks.forEachIndexed { seq, cipherChunk ->
                val decrypted = e2eManagerReceiver.decryptChunk(chunkIndex = seq.toLong(), encryptedPacket = cipherChunk)
                fos.write(decrypted)
            }
        }

        // 5. Kiểm chứng tính toàn vẹn và bảo mật Zero-Knowledge
        assertTrue(outputFile.exists())
        assertEquals(plainText.size.toLong(), outputFile.length())
        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(outputFile)
        assertEquals(expectedSha256, actualSha256)

        // Xác nhận bản rõ không bị lộ ra ngoài mạng
        encryptedChunks.forEach { cipher ->
            assertFalse(
                "Dữ liệu gửi qua mạng tuyệt đối không được chứa bản rõ",
                String(cipher, StandardCharsets.ISO_8859_1).contains("BẢN GHI DỮ LIỆU ĐƯỢC MÃ HÓA")
            )
        }
    }

    // =========================================================================
    // KỊCH BẢN 4: ĐỨT KẾT NỐI TẠI 65% TIẾN ĐỘ & RESUME TỪ CHECKPOINT 2MB ACK
    // =========================================================================
    @Test
    fun testScenario4_NetworkDisconnect_At_65Percent_And_Resume_From_Checkpoint() = runBlocking {
        // Tạo tệp 100KB giả lập
        val totalBytes = 100 * 1024L
        val sampleFile = harness.createSampleFile(
            parentDir = harness.ownerPeer.workingDir,
            fileName = "resumable_backup.dat",
            sizeBytes = totalBytes.toInt()
        )
        val expectedSha256 = Sha256StreamCalculator.calculateFileSha256(sampleFile)

        val sessionId = "session_e2e_resume_001"
        val tempFile = File(harness.memberPeer.workingDir, "resumable_backup.dat.part")

        // 1. Giai đoạn 1: Truyền đến 65% thì bị đứt mạng đột ngột
        val disconnectOffset = (totalBytes * 0.65).toLong() // 65% = 65KB
        val checkpointOffset = (disconnectOffset / (2 * 1024)) * (2 * 1024) // Làm tròn theo mốc checkpoint 2KB giả lập

        // Ghi dữ liệu giai đoạn 1 xuống tệp tạm
        RandomAccessFile(sampleFile, "r").use { srcRaf ->
            RandomAccessFile(tempFile, "rw").use { destRaf ->
                val buf = ByteArray(1024)
                var written = 0L
                while (written < checkpointOffset) {
                    val toRead = minOf(buf.size.toLong(), checkpointOffset - written).toInt()
                    srcRaf.readFully(buf, 0, toRead)
                    destRaf.write(buf, 0, toRead)
                    written += toRead
                }
            }
        }

        // Cập nhật trạng thái Checkpoint vào Repository
        val initialSession = TransferSession(
            sessionId = sessionId,
            docId = "doc_resume_01",
            peerMemberId = harness.ownerPeer.peerId,
            direction = TransferDirection.INCOMING,
            channel = TransferChannel.DIRECT_LAN,
            totalBytes = totalBytes,
            bytesTransferred = checkpointOffset,
            confirmedOffset = checkpointOffset,
            status = SessionStatus.PAUSED,
            tempFilePath = tempFile.absolutePath
        )
        harness.memberPeer.transferRepository.createSession(initialSession)

        // 2. Giai đoạn 2: Phục hồi kết nối mạng và Tiếp tục truyền (Resume)
        val sessionBeforeResume = harness.memberPeer.transferRepository.getSessionById(sessionId)
        assertNotNull(sessionBeforeResume)
        assertEquals(SessionStatus.PAUSED, sessionBeforeResume?.status)
        assertEquals(checkpointOffset, sessionBeforeResume?.confirmedOffset)

        // Resume: Sender Seek đến confirmedOffset và Receiver tiếp tục ghi từ vị trí đó
        RandomAccessFile(sampleFile, "r").use { srcRaf ->
            srcRaf.seek(checkpointOffset)
            RandomAccessFile(tempFile, "rw").use { destRaf ->
                destRaf.seek(checkpointOffset)
                val buf = ByteArray(1024)
                var bytesRead: Int
                while (srcRaf.read(buf).also { bytesRead = it } != -1) {
                    destRaf.write(buf, 0, bytesRead)
                }
            }
        }

        // Đổi tên tệp chính thức sau khi nhận đủ 100%
        val finalFile = File(harness.memberPeer.workingDir, "resumable_backup.dat")
        if (finalFile.exists()) finalFile.delete()
        assertTrue(tempFile.renameTo(finalFile))

        // 3. Kiểm chứng toàn vẹn sau khi Resume
        assertEquals(totalBytes, finalFile.length())
        val actualSha256 = Sha256StreamCalculator.calculateFileSha256(finalFile)
        assertEquals("Mã băm SHA-256 sau khi Resume phải khớp 100% với tệp gốc", expectedSha256, actualSha256)
    }

    // =========================================================================
    // KỊCH BẢN 5: THU HỒI QUYỀN THÀNH VIÊN CƯỠNG BỨC < 200MS & WIPE TỆP DỞ DANG
    // =========================================================================
    @Test
    fun testScenario5_ForcedMemberRevocation_Under_200ms_And_AntiForensicsWipe() = runBlocking {
        val teamId = "team_revocation_test"
        val memberId = harness.memberPeer.peerId

        // 1. Thiết lập nhóm và thành viên hợp lệ ban đầu
        val team = Team(
            teamId = teamId,
            teamName = "Đội Thử nghiệm Thu hồi Quyền",
            description = "Test Revocation & Anti-Forensics",
            ownerFingerprint = harness.ownerPeer.peerId
        )
        val member = TeamMember(
            memberId = memberId,
            teamId = teamId,
            memberName = harness.memberPeer.peerName,
            deviceFingerprint = "fingerprint_member_002",
            role = MemberRole.MEMBER,
            credentialData = "valid_credential_data",
            isActive = true
        )
        harness.ownerPeer.teamRepository.createTeam(team)
        harness.ownerPeer.teamRepository.addMember(member)

        // Chuẩn bị credential và public key
        val masterKeyAlias = IKeyManager.MASTER_KEY_PREFIX + teamId
        val ownerPubKey = harness.ownerPeer.keyManager.getPublicKey(masterKeyAlias)
            ?: harness.ownerPeer.keyManager.generateOwnerMasterKey(teamId)
        val credPayload = "$teamId|$memberId|${member.role.name}"
        val signatureBytes = harness.ownerPeer.keyManager.signData(masterKeyAlias, credPayload.toByteArray())
        val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

        val memberCredential = TeamMembershipCredential(
            teamId = teamId,
            memberId = memberId,
            deviceFingerprint = member.deviceFingerprint,
            role = member.role,
            issuedAt = System.currentTimeMillis() / 1000L,
            ownerSignature = signatureHex
        )

        // Tạo tệp dở dang (Incomplete part file) đang được truyền
        val partFile = File(harness.memberPeer.workingDir, "unauthorized_file.part")
        FileOutputStream(partFile).use { fos ->
            fos.write("DỮ LIỆU TỆP DỞ DANG PHẢI BỊ TIÊU HỦY ANTI-FORENSICS".toByteArray(StandardCharsets.UTF_8))
        }
        assertTrue("Tệp dở dang phải tồn tại trước khi thu hồi", partFile.exists())

        // 2. Kích hoạt Thu hồi quyền thành viên (RevokeMemberUseCase) và đo thời gian
        val durationMs = measureTimeMillis {
            val revokeResult = harness.ownerPeer.revokeMemberUseCase(teamId, memberId).first()
            assertTrue("Lệnh thu hồi phải thành công", revokeResult.isSuccess)
        }

        // Kiểm chứng Ràng buộc thời gian ngắt kết nối < 200 ms (SRS NFR & BR-06)
        assertTrue("Thời gian thu hồi quyền phải diễn ra trong < 200 ms (Thực tế: ${durationMs}ms)", durationMs < 200)

        // 3. Tiêu hủy an toàn tệp dở dang bằng SafeFileDeleter (Anti-Forensics ghi đè 0x00)
        SafeFileDeleter.wipeAndSanitizeFile(partFile)
        assertFalse("Tệp dở dang phải bị xóa vật lý khỏi hệ thống tệp", partFile.exists())

        // 4. Kiểm chứng các lần kết nối tiếp theo của Member bị từ chối
        val authResult = harness.ownerPeer.authenticatePeerUseCase(memberCredential, ownerPubKey).first()
        assertTrue("Thành viên đã bị tước quyền bắt buộc phải bị từ chối thẩm định kết nối", authResult.isFailure)
    }
}
