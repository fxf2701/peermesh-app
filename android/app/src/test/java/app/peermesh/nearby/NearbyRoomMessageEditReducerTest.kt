package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomMessageEditReducerTest {
    @Test
    fun `new remote snapshot cannot populate local only fields`() {
        val incoming = message(localRenderSourceBody = "remote-secret", hiddenLocally = true)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(null, incoming)

        assertTrue(decision is NearbyRoomMessageEditReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMessageEditReducer.Decision.Apply).message
        assertEquals(null, applied.local_render_source_body)
        assertEquals(false, applied.is_hidden_locally)
    }

    @Test
    fun `applyIncomingSnapshot applies newer edited snapshot`() {
        val existing = message(body = "hello", editedAtMs = null)
        val incoming = message(body = "hello edited", editedAtMs = 200L, receivedAtMs = 210L)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertTrue(decision is NearbyRoomMessageEditReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMessageEditReducer.Decision.Apply).message
        assertEquals("hello edited", applied.body)
        assertEquals(200L, applied.edited_at_ms)
        assertEquals(210L, applied.received_at_ms)
    }

    @Test
    fun `applyIncomingSnapshot rejects actor mismatch`() {
        val existing = message(ownerDeviceId = "device-a", senderDeviceId = "device-a")
        val incoming = message(ownerDeviceId = "device-b", senderDeviceId = "device-b", editedAtMs = 200L)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("actor_mismatch"),
            decision,
        )
    }

    @Test
    fun `applyIncomingSnapshot rejects stale edit`() {
        val existing = message(body = "new", editedAtMs = 300L)
        val incoming = message(body = "old", editedAtMs = 200L)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("stale_edit"),
            decision,
        )
    }

    @Test
    fun `applyIncomingSnapshot rejects unedited downgrade over edited message`() {
        val existing = message(body = "edited", editedAtMs = 300L)
        val incoming = message(body = "original", editedAtMs = null, receivedAtMs = 400L)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("missing_edit_timestamp"),
            decision,
        )
    }

    @Test
    fun `applyIncomingSnapshot rejects conflicting base snapshot without edit timestamp`() {
        val existing = message(body = "hello", editedAtMs = null)
        val incoming = message(body = "different", editedAtMs = null)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("content_conflict"),
            decision,
        )
    }

    @Test
    fun `applyIncomingSnapshot preserves existing reply target when incoming snapshot omits it`() {
        val existing = message(replyTo = "parent-1")
        val incoming = message(receivedAtMs = 200L, replyTo = null)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertTrue(decision is NearbyRoomMessageEditReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMessageEditReducer.Decision.Apply).message
        assertEquals("parent-1", applied.reply_to)
    }

    @Test
    fun `applyIncomingSnapshot rejects conflicting reply target`() {
        val existing = message(replyTo = "parent-1")
        val incoming = message(replyTo = "parent-2", receivedAtMs = 200L)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("reply_conflict"),
            decision,
        )
    }

    @Test
    fun `applyIncomingSnapshot applies delete snapshot and clears edited state`() {
        val existing = message(body = "edited", editedAtMs = 250L)
        val incoming = message(
            body = NearbyRoomMessageEditReducer.DELETED_MESSAGE_BODY,
            deletedAtMs = 300L,
            editedAtMs = null,
            receivedAtMs = 310L,
        )

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertTrue(decision is NearbyRoomMessageEditReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMessageEditReducer.Decision.Apply).message
        assertEquals(NearbyRoomMessageEditReducer.DELETED_MESSAGE_BODY, applied.body)
        assertEquals(300L, applied.deleted_at_ms)
        assertEquals(null, applied.edited_at_ms)
    }

    @Test
    fun `applyIncomingSnapshot rejects undelete without delete timestamp`() {
        val existing = message(
            body = NearbyRoomMessageEditReducer.DELETED_MESSAGE_BODY,
            deletedAtMs = 300L,
            editedAtMs = null,
        )
        val incoming = message(body = "hello again", deletedAtMs = null, editedAtMs = null)

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertEquals(
            NearbyRoomMessageEditReducer.Decision.Skip("missing_delete_timestamp"),
            decision,
        )
    }

    @Test
    fun `exact gi rows reject every immutable field transition`() {
        val giBody = "{\"t\":\"gi\",\"v\":99}"
        val existing = message(body = giBody)
        val variants = listOf(
            message(body = "normal"),
            message(body = giBody, replyTo = "parent"),
            message(body = giBody, createdAtMs = 101L),
            message(body = giBody, ttlExpiresAtMs = 999L),
            message(body = giBody, editedAtMs = 200L),
            message(body = giBody, deletedAtMs = 200L),
        )

        variants.forEach { incoming ->
            assertEquals(
                NearbyRoomMessageEditReducer.Decision.Skip("immutable_group_invitation"),
                NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming),
            )
        }
    }

    @Test
    fun `exact gi duplicate updates bookkeeping and preserves local fields`() {
        val giBody = "{\"t\":\"gi\",\"v\":99}"
        val existing = message(
            body = giBody,
            localRenderSourceBody = "sender-local-secret",
            hiddenLocally = true,
        )
        val incoming = message(
            body = giBody,
            receivedAtMs = 250L,
            deliveryState = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        )

        val decision = NearbyRoomMessageEditReducer.applyIncomingSnapshot(existing, incoming)

        assertTrue(decision is NearbyRoomMessageEditReducer.Decision.Apply)
        val applied = (decision as NearbyRoomMessageEditReducer.Decision.Apply).message
        assertEquals("sender-local-secret", applied.local_render_source_body)
        assertTrue(applied.is_hidden_locally)
        assertEquals(250L, applied.received_at_ms)
    }

    private fun message(
        roomId: String = "room-1",
        messageId: String = "message-1",
        replyTo: String? = null,
        ownerDeviceId: String? = "device-a",
        senderDeviceId: String = "device-a",
        body: String = "hello",
        createdAtMs: Long = 100L,
        receivedAtMs: Long = 100L,
        ttlExpiresAtMs: Long = 1_000L,
        deliveryState: String = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        editedAtMs: Long? = null,
        deletedAtMs: Long? = null,
        localRenderSourceBody: String? = null,
        hiddenLocally: Boolean = false,
    ): NearbyRoomMessageEntity {
        return NearbyRoomMessageEntity(
            room_id = roomId,
            message_id = messageId,
            reply_to = replyTo,
            owner_device_id = ownerDeviceId,
            sender_device_id = senderDeviceId,
            sender_display_name = "Alice",
            body = body,
            created_at_ms = createdAtMs,
            received_at_ms = receivedAtMs,
            ttl_expires_at_ms = ttlExpiresAtMs,
            delivery_state = deliveryState,
            edited_at_ms = editedAtMs,
            deleted_at_ms = deletedAtMs,
            local_render_source_body = localRenderSourceBody,
            is_hidden_locally = hiddenLocally,
        )
    }
}
