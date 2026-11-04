package com.teamshare.app.presentation.ui.team.qr

import com.teamshare.app.domain.model.QrInvitePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrProtocolTest {

    @Test
    fun `serialize and parse QrPayload round trip succeeds`() {
        val payload = QrInvitePayload(
            teamId = "team_alpha_123",
            invitationToken = "token_hex_abcd1234",
            expiresAt = 1730000000L,
            ownerPublicKey = "pubkey_secp256r1_hex",
            signature = "sig_ecdsa_hex"
        )

        val serialized = serializeQrPayload(payload)
        assertNotNull(serialized)

        val parsed = parseQrPayload(serialized)
        assertNotNull(parsed)
        assertEquals(payload.teamId, parsed?.teamId)
        assertEquals(payload.invitationToken, parsed?.invitationToken)
        assertEquals(payload.expiresAt, parsed?.expiresAt)
        assertEquals(payload.ownerPublicKey, parsed?.ownerPublicKey)
        assertEquals(payload.signature, parsed?.signature)
    }

    @Test
    fun `parseQrPayload returns null for invalid formats`() {
        assertNull(parseQrPayload("invalid://string"))
        assertNull(parseQrPayload("https://google.com"))
        assertNull(parseQrPayload("teamshare://invite?teamId=123")) // missing token, expiresAt, etc.
    }
}

