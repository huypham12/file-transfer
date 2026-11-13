package com.filetransfer.app.core.transfer.lan

import com.filetransfer.app.domain.model.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ReceiverRegistryTest {

    private lateinit var registry: ReceiverRegistry

    @Before
    fun setUp() {
        registry = ReceiverRegistry(maxCapacity = 5)
    }

    private fun createProfile(id: Int): DeviceProfile {
        return DeviceProfile(
            manufacturer = "Brand$id",
            model = "Model$id",
            fingerprint = "fingerprint_$id"
        )
    }

    @Test
    fun `requestAdmission admits up to 5 unique devices`() {
        for (i in 1..5) {
            val res = registry.requestAdmission(createProfile(i))
            assertTrue("Device $i should be admitted", res is AdmissionResult.Admitted)
            val admitted = res as AdmissionResult.Admitted
            assertFalse(admitted.isReconnect)
            assertEquals("fingerprint_$i", admitted.slot.fingerprint)
        }

        assertEquals(5, registry.getOccupiedCount())

        // 6th device must be rejected with SessionFull
        val sixthRes = registry.requestAdmission(createProfile(6))
        assertTrue("Device 6 must receive SessionFull", sixthRes is AdmissionResult.SessionFull)
        val full = sixthRes as AdmissionResult.SessionFull
        assertEquals(5, full.currentOccupied)
        assertEquals(5, full.maxCapacity)
    }

    @Test
    fun `reconnecting existing device increments generation and does not consume extra slot`() {
        val profile = createProfile(1)
        val firstRes = registry.requestAdmission(profile) as AdmissionResult.Admitted
        assertEquals(1L, firstRes.slot.connectionGeneration)
        assertFalse(firstRes.isReconnect)
        assertEquals(1, registry.getOccupiedCount())

        // Mark as disconnected
        registry.markDisconnected(profile.fingerprint)
        assertEquals(ReceiverSlotStatus.DISCONNECTED, registry.getSlot(profile.fingerprint)?.status)
        assertEquals(1, registry.getOccupiedCount())

        // Reconnect
        val reconnectRes = registry.requestAdmission(profile) as AdmissionResult.Admitted
        assertTrue(reconnectRes.isReconnect)
        assertEquals(2L, reconnectRes.slot.connectionGeneration)
        assertEquals(1, registry.getOccupiedCount())
    }

    @Test
    fun `slot reservation holds slots for PENDING, DISCONNECTED, and COMPLETED`() {
        // Slot 1: PENDING
        registry.requestAdmission(createProfile(1), initialStatus = ReceiverSlotStatus.PENDING)
        // Slot 2: ACTIVE
        registry.requestAdmission(createProfile(2), initialStatus = ReceiverSlotStatus.ACTIVE)
        // Slot 3: DISCONNECTED
        registry.requestAdmission(createProfile(3))
        registry.markDisconnected("fingerprint_3")
        // Slot 4: COMPLETED
        registry.requestAdmission(createProfile(4))
        registry.markCompleted("fingerprint_4")
        // Slot 5: FAILED
        registry.requestAdmission(createProfile(5))
        registry.markFailed("fingerprint_5")

        assertEquals(5, registry.getOccupiedCount())

        // 6th device must be rejected even though only 1 is currently transferring
        val sixthRes = registry.requestAdmission(createProfile(6))
        assertTrue(sixthRes is AdmissionResult.SessionFull)
    }

    @Test
    fun `revoking receiver frees slot for a new device`() {
        for (i in 1..5) {
            registry.requestAdmission(createProfile(i))
        }
        assertEquals(5, registry.getOccupiedCount())

        // Sender revokes device 3
        val revoked = registry.revoke("fingerprint_3")
        assertNotNull(revoked)
        assertEquals(ReceiverSlotStatus.REVOKED, revoked?.status)
        assertEquals(4, registry.getOccupiedCount())
        assertNull(registry.getSlot("fingerprint_3"))

        // Now device 6 can enter!
        val sixthRes = registry.requestAdmission(createProfile(6))
        assertTrue("Device 6 should be admitted after revoking device 3", sixthRes is AdmissionResult.Admitted)
        assertEquals(5, registry.getOccupiedCount())
    }

    @Test
    fun `concurrent admissions are thread-safe and never exceed max capacity`() {
        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val admittedCount = AtomicInteger(0)
        val sessionFullCount = AtomicInteger(0)

        for (i in 1..threadCount) {
            val prof = createProfile(i)
            executor.submit {
                try {
                    when (registry.requestAdmission(prof)) {
                        is AdmissionResult.Admitted -> admittedCount.incrementAndGet()
                        is AdmissionResult.SessionFull -> sessionFullCount.incrementAndGet()
                        else -> {}
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals("Exactly 5 devices must be admitted", 5, admittedCount.get())
        assertEquals("Remaining 15 devices must be rejected", 15, sessionFullCount.get())
        assertEquals(5, registry.getOccupiedCount())
    }

    @Test
    fun `isSessionInactive checks idle time when no active transfers`() {
        val profile = createProfile(1)
        registry.requestAdmission(profile)
        registry.markCompleted(profile.fingerprint)

        // With small timeout and artificially aging the last activity
        val slot = registry.getSlot(profile.fingerprint)
        assertNotNull(slot)

        // Right now, shouldn't be inactive with 60000ms timeout
        assertFalse(registry.isSessionInactive(60000L))

        // Wait small interval and verify with a smaller timeout
        Thread.sleep(10)
        assertTrue(registry.isSessionInactive(5L))
    }
}
