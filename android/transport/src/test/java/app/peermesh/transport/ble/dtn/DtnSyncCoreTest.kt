package app.peermesh.transport.ble.dtn

import app.peermesh.core.types.EventTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtnSyncCoreTest {
    @Test
    fun dependencyRepairWantCanBeBuiltBeforeRemoteInventoryArrives() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val packet = engine.buildDependencyRepairWantPacket(
            remotePeerId = "remote",
            gid = "g",
            sessionId = "s",
            candidateRootIds = engine.loadDependencyRepairWantIds("g"),
        )

        requireNotNull(packet)
        assertEquals(DtnControlTypes.INV_WANT, packet.type)
        assertEquals("local", packet.peerId)
        assertEquals("g", packet.gid)
        assertEquals("s", packet.sessionId)
        assertEquals(DtnInventoryModes.ID_LIST, packet.mode)
        assertNull(packet.inventoryTier)
        assertEquals(listOf("missing-add"), packet.ids)
        assertEquals(2_000L, packet.ts)
    }

    @Test
    fun dependencyRepairPacketBuilderDoesNotReadDurableStore() {
        val store = FakeDtnInventoryStore(
            dependencyRepairWants = listOf("store-root"),
            failOnDependencyRepairRead = true,
        )
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = store,
            clockSeconds = { 2_000 },
        )

        val packet = engine.buildDependencyRepairWantPacket(
            remotePeerId = "remote",
            gid = "g",
            sessionId = "s",
            candidateRootIds = listOf("preloaded-root"),
        )

        requireNotNull(packet)
        assertEquals(listOf("preloaded-root"), packet.ids)
        assertEquals(0, store.dependencyRepairReadCount)
    }

    @Test
    fun dependencyRepairWantIsNullWhenNoDurableRootIsPending() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            clockSeconds = { 2_000 },
        )

        assertNull(
            engine.buildDependencyRepairWantPacket(
                remotePeerId = "remote",
                gid = "g",
                sessionId = "s",
                candidateRootIds = engine.loadDependencyRepairWantIds("g"),
            )
        )
    }

    @Test
    fun dependencyRepairWantIsSentOnlyOncePerPeerSession() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val first = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))
        val duplicate = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))

        assertEquals(listOf("missing-add"), first?.ids)
        assertNull(duplicate)
    }

    @Test
    fun dependencyRepairWantRetriesRootForEveryNewPeerOrSession() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val firstPeer = engine.buildDependencyRepairWantPacket("peer-one", "g", "session-one", engine.loadDependencyRepairWantIds("g"))
        val secondPeer = engine.buildDependencyRepairWantPacket("peer-two", "g", "session-two", engine.loadDependencyRepairWantIds("g"))
        val laterSession = engine.buildDependencyRepairWantPacket("peer-one", "g", "session-three", engine.loadDependencyRepairWantIds("g"))

        assertEquals(listOf("missing-add"), firstPeer?.ids)
        assertEquals(listOf("missing-add"), secondPeer?.ids)
        assertEquals(listOf("missing-add"), laterSession?.ids)
    }

    @Test
    fun dependencyRepairWantCanRetryAfterPeerDisconnectClearsSessionGuard() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val first = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))
        engine.onPeerDisconnected(peerId = "remote", gid = "g", sessionId = "s")
        val retried = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))

        assertEquals(listOf("missing-add"), first?.ids)
        assertEquals(listOf("missing-add"), retried?.ids)
    }

    @Test
    fun dependencyRepairWantCanRetrySameSessionAfterEnqueueFailureReleasesReservation() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val first = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))
        requireNotNull(first)
        engine.releaseDependencyRepairWantReservation(
            remotePeerId = "remote",
            gid = "g",
            sessionId = "s",
            eventIds = first.ids,
        )
        val retried = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))

        assertEquals(listOf("missing-add"), retried?.ids)
    }

    @Test
    fun dependencyRepairWantCapsAndDeduplicatesRoots() {
        val roots = (1..20).map { "root-$it" } + listOf("root-1", "root-2")
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = roots),
            clockSeconds = { 2_000 },
        )

        val packet = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))

        requireNotNull(packet)
        assertEquals(16, packet.ids.size)
        assertEquals(16, packet.ids.distinct().size)
        assertEquals((1..16).map { "root-$it" }, packet.ids)
    }

    @Test
    fun onHaveDoesNotRepeatDependencyRootAlreadyReservedByProactiveWant() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )

        val proactive = engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g"))
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            mode = DtnInventoryModes.ID_LIST,
            inventoryTier = DtnInventoryTier.NORMAL.wireValue,
            ids = emptyList(),
            ts = 2_000,
        )

        assertEquals(listOf("missing-add"), proactive?.ids)
        val fallback = engine.onControlPacket(have).single()
        assertEquals(DtnControlTypes.INV_WANT, fallback.type)
        assertTrue(fallback.ids.isEmpty())
    }

    @Test
    fun onHaveFallbackStillClaimsDependencyRootWhenProactiveWantWasNotBuilt() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            mode = DtnInventoryModes.ID_LIST,
            inventoryTier = DtnInventoryTier.NORMAL.wireValue,
            ids = emptyList(),
            ts = 2_000,
        )

        val fallback = engine.onControlPacket(have).single()

        assertEquals(DtnControlTypes.INV_WANT, fallback.type)
        assertEquals(listOf("missing-add"), fallback.ids)
        assertNull(engine.buildDependencyRepairWantPacket("remote", "g", "s", engine.loadDependencyRepairWantIds("g")))
    }

    @Test
    fun dependencyRootIsRequestedFromEveryNewPeerSessionEvenWhenNotAdvertised() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(dependencyRepairWants = listOf("missing-add")),
            clockSeconds = { 2_000 },
        )
        fun have(peerId: String, sessionId: String) = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = peerId,
            gid = "g",
            sessionId = sessionId,
            mode = DtnInventoryModes.ID_LIST,
            inventoryTier = DtnInventoryTier.NORMAL.wireValue,
            ids = emptyList(),
            ts = 2_000,
        )

        val first = engine.onControlPacket(have("peer-one", "session-one")).single()
        assertEquals(DtnControlTypes.INV_WANT, first.type)
        assertEquals(listOf("missing-add"), first.ids)

        val second = engine.onControlPacket(have("peer-two", "session-two")).single()
        assertEquals(DtnControlTypes.INV_WANT, second.type)
        assertEquals(listOf("missing-add"), second.ids)
    }

    @Test
    fun buildHelloPacketUsesLocalPeerAndGroup() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            clockSeconds = { 2_000 },
        )

        val packet = engine.buildHelloPacket(gid = "g", sessionId = "s")

        assertEquals(DtnControlTypes.HELLO, packet.type)
        assertEquals("local", packet.peerId)
        assertEquals("g", packet.gid)
        assertEquals("s", packet.sessionId)
        assertEquals(2_000, packet.ts)
    }

    @Test
    fun buildHelloPacketIncludesCloudStatusHint() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            localCloudStatusProvider = DtnLocalCloudStatusProvider { nowSeconds ->
                assertEquals(2_000L, nowSeconds)
                DtnCloudStatus(
                    state = DtnCloudStates.STALE,
                    lastSuccessAtSeconds = 1_900L,
                )
            },
            clockSeconds = { 2_000 },
        )

        val packet = engine.buildHelloPacket(gid = "g", sessionId = "s")

        assertEquals(DtnCloudStates.STALE, packet.cloudStatus?.state)
        assertEquals(1_900L, packet.cloudStatus?.lastSuccessAtSeconds)
    }

    @Test
    fun onHelloReportsPeerCloudStatus() {
        val observed = ArrayList<String>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            peerHelloObserver = DtnPeerHelloObserver { peerId, gid, sessionId, cloudStatus, seenAtSeconds ->
                observed += "$peerId:$gid:$sessionId:${cloudStatus?.state}:${cloudStatus?.lastSuccessAtSeconds}:$seenAtSeconds"
            },
            clockSeconds = { 2_000 },
        )

        engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                cloudStatus = DtnCloudStatus(
                    state = DtnCloudStates.DISABLED,
                    lastSuccessAtSeconds = null,
                ),
                ts = 1_000,
            )
        )

        assertEquals(listOf("remote:g:s:disabled:null:2000"), observed)
    }

    @Test
    fun syncEngineAdvertisesLocalManifestKeyOnHello() {
        val keys = ArrayList<String>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            localManifestKeyProvider = DtnLocalManifestKeyProvider {
                DtnPeerManifestKey(
                    peerId = "local",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "local-public-key",
                )
            },
            peerManifestKeyStore = DtnPeerManifestKeyStore { peerId, algorithm, publicKey, nowSeconds ->
                keys += "$peerId:$algorithm:$publicKey:$nowSeconds"
                true
            },
            clockSeconds = { 2_000 },
        )

        val hello = engine.buildHelloPacket(gid = "g", sessionId = "s")
        assertEquals("local-public-key", hello.manifestKey?.publicKey)

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestKey = DtnPeerManifestKey(
                    peerId = "remote",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "remote-public-key",
                ),
                ts = 1_000,
            )
        )

        assertEquals(listOf(DtnControlTypes.MANIFEST_KEY), responses.map { it.type })
        val followUp = engine.onSessionTaskCompleted(peerId = "remote", gid = "g", sessionId = "s")
        assertNull(followUp)
        assertEquals("local-public-key", responses.single().manifestKey?.publicKey)
        assertEquals(listOf("remote:ECDSA_P256_SHA256:remote-public-key:2000"), keys)
    }

    @Test
    fun syncEngineStoresRemoteManifestKey() {
        val keys = ArrayList<String>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            peerManifestKeyStore = DtnPeerManifestKeyStore { peerId, algorithm, publicKey, nowSeconds ->
                keys += "$peerId:$algorithm:$publicKey:$nowSeconds"
                true
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_KEY,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestKey = DtnPeerManifestKey(
                    peerId = "remote",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "remote-public-key",
                ),
                ts = 1_000,
            )
        )

        assertTrue(responses.isEmpty())
        assertEquals(listOf("remote:ECDSA_P256_SHA256:remote-public-key:2000"), keys)
    }

    @Test
    fun syncEngineOffersCarrierBatchesAfterReceivingManifestKey() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            peerManifestKeyStore = DtnPeerManifestKeyStore { _, _, _, _ -> true },
            carrierManifestOfferSource = object : DtnCarrierManifestOfferSource {
                override fun loadOfferBatches(
                    gid: String,
                    nowSeconds: Long,
                    maxBatches: Int,
                    maxBytes: Long,
                ): List<DtnCarrierOfferBatch> {
                    assertEquals("g", gid)
                    assertEquals(2_000, nowSeconds)
                    return listOf(
                        DtnCarrierOfferBatch(
                            batchId = "batch-1",
                            payloadSha256 = "a".repeat(64),
                            payloadBytes = 512,
                            kind = "app_batch",
                            createdAtSeconds = 1_900,
                            expiresAtSeconds = 2_100,
                            priority = 3,
                        )
                    )
                }
            },
            carrierManifestSigner = DtnCarrierManifestSigner { packet, manifest ->
                assertEquals(DtnControlTypes.MANIFEST_OFFER, packet.type)
                assertEquals("local", manifest.senderPeerId)
                assertEquals("remote", manifest.receiverPeerId)
                "signed"
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_KEY,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestKey = DtnPeerManifestKey(
                    peerId = "remote",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "remote-public-key",
                ),
                ts = 1_000,
            )
        )
        val offer = responses.single()
        val manifest = offer.manifest!!

        assertEquals(DtnControlTypes.MANIFEST_OFFER, offer.type)
        assertEquals("g", offer.gid)
        assertEquals("s", offer.sessionId)
        assertEquals("remote", manifest.receiverPeerId)
        assertEquals("signed", manifest.signature)
        assertEquals(listOf("batch-1"), manifest.batches.map { it.batchId })
    }

    @Test
    fun syncEngineBuildsSignedManifestOfferOnHelloWhenCarrierBatchesExist() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            carrierManifestOfferSource = object : DtnCarrierManifestOfferSource {
                override fun loadOfferBatches(
                    gid: String,
                    nowSeconds: Long,
                    maxBatches: Int,
                    maxBytes: Long,
                ): List<DtnCarrierOfferBatch> {
                    assertEquals("g", gid)
                    assertEquals(2_000, nowSeconds)
                    return listOf(
                        DtnCarrierOfferBatch(
                            batchId = "batch-1",
                            payloadSha256 = "a".repeat(64),
                            payloadBytes = 512,
                            kind = "app_batch",
                            createdAtSeconds = 1_900,
                            expiresAtSeconds = 2_100,
                            priority = 3,
                        )
                    )
                }
            },
            carrierManifestSigner = DtnCarrierManifestSigner { packet, manifest ->
                assertEquals(DtnControlTypes.MANIFEST_OFFER, packet.type)
                assertEquals("local", manifest.senderPeerId)
                assertEquals("remote", manifest.receiverPeerId)
                "signed"
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.MANIFEST_OFFER), responses.map { it.type })
        val offer = responses.single()
        assertTrue(engine.onSessionTaskCompleted(peerId = "remote", gid = "g", sessionId = "s") == null)
        val manifest = offer.manifest!!

        assertEquals("local", offer.peerId)
        assertEquals("g", offer.gid)
        assertEquals("s", offer.sessionId)
        assertEquals("remote", manifest.receiverPeerId)
        assertEquals("signed", manifest.signature)
        assertEquals(listOf("batch-1"), manifest.batches.map { it.batchId })
    }

    @Test
    fun syncEngineSendsCarrierDataForAcceptedManifestBatches() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            carrierDataSource = object : DtnCarrierDataSource {
                override fun loadCarrierData(
                    gid: String,
                    manifestId: String,
                    batchIds: Collection<String>,
                    nowSeconds: Long,
                    maxBytes: Long,
                ): List<DtnCarrierDataItem> {
                    assertEquals("g", gid)
                    assertEquals("manifest-1", manifestId)
                    assertEquals(listOf("batch-1"), batchIds.toList())
                    assertEquals(2_000, nowSeconds)
                    return listOf(
                        DtnCarrierDataItem(
                            batchId = "batch-1",
                            payload = "payload",
                            payloadSha256 = "a".repeat(64),
                            payloadBytes = 128,
                        )
                    )
                }
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_ACCEPT,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestId = "manifest-1",
                acceptedBatchIds = listOf("batch-1"),
                ts = 1_999,
            )
        )
        val data = responses.single()

        assertEquals(DtnControlTypes.DATA, data.type)
        assertEquals("manifest-1", data.manifestId)
        assertEquals(listOf("batch-1"), data.carrierData.map { it.batchId })
        assertTrue(data.data.isEmpty())
    }

    @Test
    fun syncEngineAcksOnlyAcceptedCarrierDataItems() {
        val accepted = ArrayList<String>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            carrierDataReceiver = DtnCarrierDataReceiver { gid, peerId, manifestId, item, nowSeconds ->
                assertEquals("g", gid)
                assertEquals("remote", peerId)
                assertEquals("manifest-1", manifestId)
                assertEquals(2_000, nowSeconds)
                val ok = item.batchId == "batch-1"
                if (ok) accepted += item.batchId
                ok
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestId = "manifest-1",
                carrierData = listOf(
                    DtnCarrierDataItem("batch-1", "payload-1", "a".repeat(64), 128),
                    DtnCarrierDataItem("batch-2", "payload-2", "b".repeat(64), 128),
                ),
                ts = 1_999,
            )
        )
        val ack = responses.single()

        assertEquals(listOf("batch-1"), accepted)
        assertEquals(DtnControlTypes.ACK, ack.type)
        assertEquals("manifest-1", ack.manifestId)
        assertEquals(listOf("batch-1"), ack.ids)
    }

    @Test
    fun inventoryBuilderSortsByPriorityThenAge() {
        val store = FakeDtnInventoryStore(
            inventory = listOf(
                item("g", "chat-new", "CHAT.MSG", createdAt = 300),
                item("g", "alert-old", "SYS.ALERT", createdAt = 100),
                item("g", "map-mid", "MAP.MARKER.ADD", createdAt = 200),
            )
        )
        val builder = DtnInventoryBuilder(store)

        val packet = builder.buildHavePacket(
            localPeerId = "local",
            gid = "g",
            sessionId = "s",
            nowSeconds = 5_000,
        )

        assertEquals(DtnControlTypes.INV_HAVE, packet.type)
        assertEquals(DtnInventoryTier.NORMAL.wireValue, packet.inventoryTier)
        assertEquals(listOf("alert-old", "map-mid", "chat-new"), packet.ids)
    }

    @Test
    fun inventoryBuilderPrioritizesRecentChatInHighTier() {
        val store = FakeDtnInventoryStore(
            inventory = listOf(
                item("g", "chat-older", "CHAT.MSG", createdAt = 3_300),
                item("g", "chat-newer", "CHAT.EDIT", createdAt = 3_500),
                item("g", "map-mid", "MAP.MARKER.ADD", createdAt = 200),
            )
        )
        val builder = DtnInventoryBuilder(store)

        val window = builder.buildHaveWindow(
            localPeerId = "local",
            remotePeerId = "remote",
            gid = "g",
            sessionId = "s",
            nowSeconds = 3_600,
            tier = DtnInventoryTier.HIGH,
            cursor = null,
            limit = 10,
        )

        assertEquals(DtnInventoryTier.HIGH.wireValue, window.packet.inventoryTier)
        assertEquals(listOf("chat-newer", "chat-older"), window.packet.ids)
    }

    @Test
    fun inventoryBuilderUsesCursorWindowAndReturnsAdvancedCursor() {
        val store = FakeDtnInventoryStore(
            inventory = listOf(
                item("g", "event-a", "CHAT.MSG", createdAt = 100),
                item("g", "event-b", "CHAT.MSG", createdAt = 200),
                item("g", "event-c", "CHAT.MSG", createdAt = 300),
            )
        )
        val builder = DtnInventoryBuilder(store)

        val window = builder.buildHaveWindow(
            localPeerId = "local",
            remotePeerId = "remote",
            gid = "g",
            sessionId = "s",
            nowSeconds = 5_000,
            tier = DtnInventoryTier.NORMAL,
            cursor = DtnSyncCursor(lastInsertedAt = 100, lastEventId = "event-a"),
            limit = 10,
        )

        assertEquals(listOf("event-b", "event-c"), window.packet.ids)
        assertEquals(DtnSyncCursor(lastInsertedAt = 300, lastEventId = "event-c"), window.cursor)
    }

    @Test
    fun diffResolverRequestsOnlyMissingDistinctIds() {
        val store = FakeDtnInventoryStore(existing = setOf("have-1"))
        val resolver = DtnDiffResolver(store)

        val packet = resolver.buildWantPacket(
            localPeerId = "local",
            havePacket = DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                inventoryTier = DtnInventoryTier.NORMAL.wireValue,
                ids = listOf("have-1", "missing-1", "missing-1", "missing-2"),
                ts = 1_000,
            ),
            nowSeconds = 1_001,
            limit = 10,
        )

        assertEquals(DtnControlTypes.INV_WANT, packet?.type)
        assertEquals(DtnInventoryTier.NORMAL.wireValue, packet?.inventoryTier)
        assertEquals(listOf("missing-1", "missing-2"), packet?.ids)
    }

    @Test
    fun syncEngineRespondsToHaveWithWant() {
        val store = FakeDtnInventoryStore(existing = setOf("event-a"))
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = store,
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-a", "event-b"),
                ts = 1_000,
            )
        )

        assertEquals(1, responses.size)
        assertEquals(DtnControlTypes.INV_WANT, responses.first().type)
        assertEquals(listOf("event-b"), responses.first().ids)
    }

    @Test
    fun syncEngineAdvancesToNormalTierWhenHighTierHasNothingToWant() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(
                inventory = listOf(
                    item("g", "normal-1", "MAP.MARKER.ADD", createdAt = 100),
                ),
                existing = emptySet(),
            ),
            clockSeconds = { 4_000 },
        )

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_HAVE), helloResponses.map { it.type })
        assertEquals(DtnInventoryTier.NORMAL.wireValue, helloResponses.first().inventoryTier)
        assertEquals(listOf("normal-1"), helloResponses.first().ids)
    }

    @Test
    fun syncEngineAdvancesToNextTierAfterEmptyWant() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(
                inventory = listOf(
                    item("g", "chat-1", "CHAT.MSG", createdAt = 3_500),
                    item("g", "normal-1", "MAP.MARKER.ADD", createdAt = 100),
                ),
            ),
            clockSeconds = { 3_600 },
        )

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )
        assertEquals(listOf("chat-1"), helloResponses.first().ids)
        assertEquals(DtnInventoryTier.HIGH.wireValue, helloResponses.first().inventoryTier)

        val next = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                mode = DtnInventoryModes.ID_LIST,
                inventoryTier = DtnInventoryTier.HIGH.wireValue,
                ids = emptyList(),
                ts = 1_100,
            )
        )

        assertEquals(listOf(DtnControlTypes.INV_HAVE), next.map { it.type })
        assertEquals(DtnInventoryTier.NORMAL.wireValue, next.first().inventoryTier)
        assertEquals(listOf("normal-1"), next.first().ids)
    }

    @Test
    fun syncEngineReturnsNoHaveWhenAllTiersAreEmpty() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            clockSeconds = { 4_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )

        assertNull(responses.firstOrNull())
    }

    @Test
    fun syncEngineDefersManifestOfferWhileLocalWantIsOutstanding() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = emptySet()),
            carrierManifestOfferSource = object : DtnCarrierManifestOfferSource {
                override fun loadOfferBatches(
                    gid: String,
                    nowSeconds: Long,
                    maxBatches: Int,
                    maxBytes: Long,
                ): List<DtnCarrierOfferBatch> {
                    return listOf(
                        DtnCarrierOfferBatch(
                            batchId = "batch-1",
                            payloadSha256 = "a".repeat(64),
                            payloadBytes = 512,
                            kind = "app_batch",
                            createdAtSeconds = 1_900,
                            expiresAtSeconds = 2_100,
                            priority = 3,
                        )
                    )
                }
            },
            carrierManifestSigner = DtnCarrierManifestSigner { _, _ -> "signed" },
            clockSeconds = { 2_000 },
        )

        val wantResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-b"),
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_WANT), wantResponses.map { it.type })

        val manifestKeyResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_KEY,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifestKey = DtnPeerManifestKey(
                    peerId = "remote",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "remote-public-key",
                ),
                ts = 1_500,
            )
        )

        assertTrue(manifestKeyResponses.isEmpty())
    }

    @Test
    fun syncEngineAdvertisesOutstandingWantOnSubsequentHello() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = emptySet()),
            clockSeconds = { 2_000 },
        )

        val wantResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "peer-a",
                gid = "g",
                sessionId = "s-a",
                ids = listOf("event-b"),
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_WANT), wantResponses.map { it.type })

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "peer-b",
                gid = "g",
                sessionId = "s-b",
                ts = 1_500,
            )
        )

        assertTrue(helloResponses.isEmpty())
    }

    @Test
    fun syncEngineUsesKnownMissingOnlyWhenPeerAdvertisesOverlap() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = emptySet()),
            clockSeconds = { 2_000 },
        )

        val initialWantResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "peer-a",
                gid = "g",
                sessionId = "s-a",
                ids = listOf("event-b"),
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_WANT), initialWantResponses.map { it.type })
        assertEquals(listOf("event-b"), initialWantResponses.single().ids)

        val nonOverlapResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "peer-b",
                gid = "g",
                sessionId = "s-b",
                ids = listOf("event-c"),
                ts = 1_500,
            )
        )

        assertEquals(listOf(DtnControlTypes.INV_WANT), nonOverlapResponses.map { it.type })
        assertEquals(listOf("event-c"), nonOverlapResponses.single().ids)

        val overlapResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "peer-c",
                gid = "g",
                sessionId = "s-c",
                ids = listOf("event-b", "event-d"),
                ts = 1_700,
            )
        )

        assertEquals(listOf(DtnControlTypes.INV_WANT), overlapResponses.map { it.type })
        assertEquals(listOf("event-b", "event-d"), overlapResponses.single().ids)
    }

    @Test
    fun syncEngineMarksSuccessWhenHaveHasNoMissingIds() {
        val stateStore = FakeDtnSyncStateStore()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = setOf("event-a")),
            syncStateStore = stateStore,
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-a"),
                ts = 1_000,
            )
        )

        assertTrue(responses.isEmpty())
        assertEquals(listOf("remote:g:2000"), stateStore.successes)
    }

    @Test
    fun dependencyRepairWantServesExactDataWithoutAdvertisedInventoryWindow() {
        val rootId = "canonical-add-root"
        val store = FakeDtnInventoryStore(
            inventory = listOf(
                item("g", "newer-advertised-event", EventTypes.CHAT_MSG, createdAt = 2_000),
            ),
            data = mapOf(
                rootId to DtnDataItem(eventId = rootId, envelope = """{"mid":"$rootId"}"""),
            ),
        )
        val stateStore = FakeDtnSyncStateStore(
            cursors = linkedMapOf(
                "remote:g" to DtnSyncCursor(lastInsertedAt = 1_900, lastEventId = "cursor-event"),
            )
        )
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = store,
            diagnostics = DtnDiagnosticsSink { diagnostics += it },
            syncStateStore = stateStore,
            clockSeconds = { 2_000 },
        )

        // No HELLO/INV_HAVE is processed first: the requested canonical ADD is outside any
        // advertised inventory window and must still be served by exact durable event ID.
        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = "remote",
                gid = "g",
                sessionId = "repair-session",
                ids = listOf(rootId),
                ts = 1_999,
            )
        )

        assertEquals(1, responses.size)
        assertEquals(DtnControlTypes.DATA, responses.single().type)
        assertEquals(listOf(rootId), responses.single().data.map { it.eventId })
        assertEquals(0, store.inventoryReadCount)
        assertTrue(stateStore.savedCursors.isEmpty())
        assertEquals(DtnSyncCursor(lastInsertedAt = 1_900, lastEventId = "cursor-event"), stateStore.cursors["remote:g"])
        val dependencyRecv = diagnostics.single { it.label == "DTN dependency want recv" }
        assertEquals(listOf(rootId), dependencyRecv.idSample)
        val dependencyServed = diagnostics.single { it.label == "DTN dependency data served" }
        assertEquals("serve", dependencyServed.direction)
        assertEquals(listOf(rootId), dependencyServed.idSample)
        assertEquals(listOf(rootId), dependencyServed.dataIdSample)
    }

    @Test
    fun dependencyRepairDataReceiveDiagnosticCarriesAcceptedRootId() {
        val rootId = "canonical-add-root"
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            diagnostics = DtnDiagnosticsSink { diagnostics += it },
            dataReceiver = DtnDataReceiver { _, _ -> true },
            clockSeconds = { 2_000 },
        )

        val want = engine.buildDependencyRepairWantPacket(
            remotePeerId = "remote",
            gid = "g",
            sessionId = "repair-session",
            candidateRootIds = listOf(rootId),
            nowSeconds = 1_999,
        )
        assertEquals(listOf(rootId), want?.ids)

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "remote",
                gid = "g",
                sessionId = "repair-session",
                data = listOf(DtnDataItem(eventId = rootId, envelope = "{}")),
                ts = 2_000,
            )
        )

        assertEquals(DtnControlTypes.ACK, responses.single().type)
        val received = diagnostics.single { it.label == "DTN dependency data recv" }
        assertEquals("accepted", received.direction)
        assertEquals(listOf(rootId), received.idSample)
        assertEquals(listOf(rootId), received.dataIdSample)
    }

    @Test
    fun syncEngineRespondsToWantWithData() {
        val store = FakeDtnInventoryStore(
            data = mapOf(
                "event-a" to DtnDataItem(eventId = "event-a", envelope = """{"mid":"event-a"}"""),
            )
        )
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = store,
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-a", "missing"),
                ts = 1_000,
            )
        )

        assertEquals(1, responses.size)
        assertEquals(DtnControlTypes.DATA, responses.first().type)
        assertEquals(listOf("event-a"), responses.first().data.map { it.eventId })
    }

    @Test
    fun syncEngineRespondsToDataWithAck() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            dataReceiver = DtnDataReceiver { _, item -> item.eventId != "event-b" },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                data = listOf(
                    DtnDataItem(eventId = "event-a", envelope = "{}"),
                    DtnDataItem(eventId = "event-b", envelope = "{}"),
                ),
                ts = 1_000,
            )
        )

        assertEquals(1, responses.size)
        assertEquals(DtnControlTypes.ACK, responses.first().type)
        assertEquals(listOf("event-a"), responses.first().ids)
    }

    @Test
    fun syncEngineDefersManifestAcceptUntilOutstandingWantIsSatisfied() {
        val key = "remote-manifest-key".toByteArray(Charsets.UTF_8)
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = emptySet()),
            dataReceiver = DtnDataReceiver { _, _ -> true },
            carrierManifestStore = DtnCarrierManifestStore { _, _, _, _, _ -> },
            carrierManifestVerifier = HmacDtnCarrierManifestVerifier(
                DtnCarrierManifestKeyResolver { peerId ->
                    if (peerId == "remote") key else null
                }
            ),
            clockSeconds = { 2_000 },
        )

        val wantResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-b"),
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_WANT), wantResponses.map { it.type })

        val unsignedPacket = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_OFFER,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            manifest = manifest(senderPeerId = "remote", receiverPeerId = "local", signature = ""),
            ts = 1_999,
        )
        val unsignedManifest = unsignedPacket.manifest!!
        val signedManifest = unsignedManifest.copy(
            signature = DtnCarrierManifestCrypto.sign(unsignedPacket, unsignedManifest, key),
        )

        val deferredResponses = engine.onControlPacket(unsignedPacket.copy(manifest = signedManifest))
        assertTrue(deferredResponses.isEmpty())

        val dataResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                data = listOf(DtnDataItem(eventId = "event-b", envelope = "{}")),
                ts = 2_000,
            )
        )

        assertEquals(listOf(DtnControlTypes.ACK), dataResponses.map { it.type })
        val deferredManifestAccept = engine.onSessionTaskCompleted(peerId = "remote", gid = "g", sessionId = "s")
        assertEquals(DtnControlTypes.MANIFEST_ACCEPT, deferredManifestAccept?.type)
        assertEquals("manifest-1", deferredManifestAccept?.manifestId)
    }

    @Test
    fun syncEngineClearsOutstandingWantAfterDataAndDoesNotRepeatOnNextHello() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(existing = emptySet()),
            dataReceiver = DtnDataReceiver { _, _ -> true },
            clockSeconds = { 2_000 },
        )

        val wantResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = "peer-a",
                gid = "g",
                sessionId = "s-a",
                ids = listOf("event-b"),
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.INV_WANT), wantResponses.map { it.type })

        val dataResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "peer-a",
                gid = "g",
                sessionId = "s-a",
                data = listOf(DtnDataItem(eventId = "event-b", envelope = "{}")),
                ts = 1_500,
            )
        )
        assertEquals(listOf(DtnControlTypes.ACK), dataResponses.map { it.type })

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "peer-b",
                gid = "g",
                sessionId = "s-b",
                ts = 1_800,
            )
        )

        assertTrue(helloResponses.isEmpty())
    }

    @Test
    fun syncEngineDoesNotAckRejectedData() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            dataReceiver = DtnDataReceiver { _, _ -> false },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                data = listOf(DtnDataItem(eventId = "event-a", envelope = "{}")),
                ts = 1_000,
            )
        )

        assertTrue(responses.isEmpty())
    }

    @Test
    fun syncEngineIgnoresOwnPackets() {
        val engine = DtnSyncEngine(localPeerId = "local", store = FakeDtnInventoryStore())

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "local",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )

        assertTrue(responses.isEmpty())
    }

    @Test
    fun syncEngineDoesNotPersistCursorOnHelloUntilWindowCompletes() {
        val stateStore = FakeDtnSyncStateStore(
            cursors = mutableMapOf("remote:g#normal" to DtnSyncCursor(lastInsertedAt = 100, lastEventId = "event-a"))
        )
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(
                inventory = listOf(
                    item("g", "event-a", "CHAT.MSG", createdAt = 100),
                    item("g", "event-b", "CHAT.MSG", createdAt = 200),
                )
            ),
            syncStateStore = stateStore,
            clockSeconds = { 5_000 },
        )

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )

        assertEquals(listOf("remote:g:s:5000"), stateStore.seen)
        assertEquals(listOf("event-b"), helloResponses.single().ids)
        assertEquals(DtnSyncCursor(lastInsertedAt = 100, lastEventId = "event-a"), stateStore.cursors["remote:g#normal"])
        assertFalse(stateStore.savedCursors.any { it.startsWith("remote:g#normal:") })

        val nextResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                inventoryTier = DtnInventoryTier.NORMAL.wireValue,
                ids = emptyList(),
                ts = 5_001,
            )
        )

        assertTrue(nextResponses.isEmpty())
        assertEquals(DtnSyncCursor(lastInsertedAt = 200, lastEventId = "event-b"), stateStore.cursors["remote:g#normal"])
        assertTrue(stateStore.savedCursors.contains("remote:g#normal:200:event-b:5000"))
    }

    @Test
    fun syncEngineMarksSuccessOnAck() {
        val stateStore = FakeDtnSyncStateStore()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            syncStateStore = stateStore,
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.ACK,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-a"),
                ts = 1_000,
            )
        )

        assertTrue(responses.isEmpty())
        assertEquals(listOf("remote:g:2000"), stateStore.successes)
    }

    @Test
    fun syncEngineCommitsCursorOnlyAfterAckCompletesWantedWindow() {
        val stateStore = FakeDtnSyncStateStore()
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(
                inventory = listOf(
                    item("g", "event-a", "NOTE", createdAt = 100),
                ),
                data = mapOf(
                    "event-a" to DtnDataItem(eventId = "event-a", envelope = "{}"),
                ),
            ),
            diagnostics = DtnDiagnosticsSink { diagnostics += it },
            syncStateStore = stateStore,
            clockSeconds = { 2_000 },
        )

        val helloResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )
        assertEquals(listOf("event-a"), helloResponses.single().ids)
        assertFalse(stateStore.savedCursors.any { it.startsWith("remote:g#normal:") })

        val dataResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                inventoryTier = DtnInventoryTier.NORMAL.wireValue,
                ids = listOf("event-a"),
                ts = 1_100,
            )
        )
        assertEquals(DtnControlTypes.DATA, dataResponses.single().type)
        assertFalse(stateStore.savedCursors.any { it.startsWith("remote:g#normal:") })

        val ackResponses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.ACK,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ids = listOf("event-a"),
                ts = 1_200,
            )
        )

        assertTrue(ackResponses.isEmpty())
        assertEquals(DtnSyncCursor(lastInsertedAt = 100, lastEventId = "event-a"), stateStore.cursors["remote:g#normal"])
        assertTrue(stateStore.savedCursors.contains("remote:g#normal:100:event-a:2000"))
        assertTrue(
            diagnostics.any { event ->
                event.label == "DTN cursor commit" &&
                    event.reason?.contains("tier=normal") == true &&
                    event.reason?.contains("have_ids=event-a") == true &&
                    event.reason?.contains("want_ids=event-a") == true &&
                    event.reason?.contains("cursor_after=100:event-a") == true
            }
        )
    }

    @Test
    fun syncEngineAcceptsValidManifestOffer() {
        val saved = ArrayList<String>()
        val key = "remote-manifest-key".toByteArray(Charsets.UTF_8)
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            carrierManifestStore = DtnCarrierManifestStore { peerId, sessionId, manifest, acceptedBatchIds, nowSeconds ->
                saved += "$peerId:$sessionId:${manifest.manifestId}:${acceptedBatchIds.joinToString(",")}:$nowSeconds"
            },
            carrierManifestVerifier = HmacDtnCarrierManifestVerifier(
                DtnCarrierManifestKeyResolver { peerId ->
                    if (peerId == "remote") key else null
                }
            ),
            clockSeconds = { 2_000 },
        )
        val unsignedPacket = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_OFFER,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            manifest = manifest(senderPeerId = "remote", receiverPeerId = "local", signature = ""),
            ts = 1_999,
        )
        val unsignedManifest = unsignedPacket.manifest!!
        val signedManifest = unsignedManifest.copy(
            signature = DtnCarrierManifestCrypto.sign(unsignedPacket, unsignedManifest, key),
        )

        val responses = engine.onControlPacket(
            unsignedPacket.copy(manifest = signedManifest)
        )

        assertEquals(1, responses.size)
        assertEquals(DtnControlTypes.MANIFEST_ACCEPT, responses.single().type)
        assertEquals("manifest-1", responses.single().manifestId)
        assertEquals(listOf("batch-1"), responses.single().acceptedBatchIds)
        assertEquals(listOf("remote:s:manifest-1:batch-1:2000"), saved)
    }

    @Test
    fun syncEngineRejectsManifestOfferWithMismatchedHash() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_OFFER,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifest = manifest(
                    senderPeerId = "remote",
                    receiverPeerId = "local",
                    payloadSha256 = "not-a-sha",
                ),
                ts = 1_999,
            )
        )

        assertTrue(responses.isEmpty())
    }

    @Test
    fun syncEngineRejectsManifestOfferWhenVerifierFails() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            carrierManifestVerifier = DtnCarrierManifestVerifier { _, _ -> false },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_OFFER,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                manifest = manifest(senderPeerId = "remote", receiverPeerId = "local"),
                ts = 1_999,
            )
        )

        assertTrue(responses.isEmpty())
    }

    @Test
    fun forwardPolicyBlocksExpiredOrMaxHopItems() {
        assertTrue(
            DtnForwardPolicy.canForward(
                DtnForwardMeta(createdAtSeconds = 1_000, ttlSeconds = 60, hopCount = 1, maxHops = 3),
                nowSeconds = 1_060,
            )
        )
        assertFalse(
            DtnForwardPolicy.canForward(
                DtnForwardMeta(createdAtSeconds = 1_000, ttlSeconds = 60, hopCount = 1, maxHops = 3),
                nowSeconds = 1_061,
            )
        )
        assertFalse(
            DtnForwardPolicy.canForward(
                DtnForwardMeta(createdAtSeconds = 1_000, ttlSeconds = 60, hopCount = 3, maxHops = 3),
                nowSeconds = 1_010,
            )
        )
    }

    @Test
    fun syncEngineQueuesOneSessionTaskAtATime() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            localManifestKeyProvider = DtnLocalManifestKeyProvider {
                DtnPeerManifestKey(
                    peerId = "local",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "local-public-key",
                )
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )

        assertEquals(listOf(DtnControlTypes.MANIFEST_KEY), responses.map { it.type })
        assertTrue(engine.onSessionTaskCompleted(peerId = "remote", gid = "g", sessionId = "s") == null)
    }

    @Test
    fun syncEngineClearsSessionQueueOnPeerDisconnect() {
        val engine = DtnSyncEngine(
            localPeerId = "local",
            store = FakeDtnInventoryStore(),
            localManifestKeyProvider = DtnLocalManifestKeyProvider {
                DtnPeerManifestKey(
                    peerId = "local",
                    algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                    publicKey = "local-public-key",
                )
            },
            clockSeconds = { 2_000 },
        )

        val responses = engine.onControlPacket(
            DtnControlPacket(
                type = DtnControlTypes.HELLO,
                peerId = "remote",
                gid = "g",
                sessionId = "s",
                ts = 1_000,
            )
        )
        assertEquals(listOf(DtnControlTypes.MANIFEST_KEY), responses.map { it.type })

        engine.onPeerDisconnected(peerId = "remote", gid = "g", sessionId = "s")

        assertTrue(engine.onSessionTaskCompleted(peerId = "remote", gid = "g", sessionId = "s") == null)
    }

    private fun item(gid: String, eventId: String, type: String, createdAt: Long): DtnInventoryItem {
        return DtnInventoryItem(
            gid = gid,
            eventId = eventId,
            type = type,
            createdAtSeconds = createdAt,
            insertedAtSeconds = createdAt,
        )
    }

    private fun manifest(
        senderPeerId: String,
        receiverPeerId: String?,
        payloadSha256: String = "a".repeat(64),
        signature: String = "sig",
    ): DtnCarrierManifest {
        return DtnCarrierManifest(
            manifestId = "manifest-1",
            senderPeerId = senderPeerId,
            receiverPeerId = receiverPeerId,
            gidHash = "gid-hash",
            maxBatches = 2,
            maxBytes = 2_048,
            expiresAt = 2_100,
            nonce = "nonce-1",
            batches = listOf(
                DtnCarrierManifestBatch(
                    batchId = "batch-1",
                    payloadSha256 = payloadSha256,
                    payloadBytes = 512,
                    kind = "app_batch",
                    expiresAt = 2_050,
                    priority = 3,
                ),
            ),
            signature = signature,
        )
    }

    private class FakeDtnInventoryStore(
        private val inventory: List<DtnInventoryItem> = emptyList(),
        private val existing: Set<String> = emptySet(),
        private val data: Map<String, DtnDataItem> = emptyMap(),
        private val dependencyRepairWants: List<String> = emptyList(),
        private val failOnDependencyRepairRead: Boolean = false,
    ) : DtnInventoryStore {
        var dependencyRepairReadCount: Int = 0
            private set
        var inventoryReadCount: Int = 0
            private set
        override fun listInventory(gid: String, limit: Int): List<DtnInventoryItem> {
            inventoryReadCount += 1
            return inventory.filter { it.gid == gid }.take(limit)
        }

        override fun listInventoryAfter(
            gid: String,
            lastInsertedAt: Long,
            lastEventId: String,
            limit: Int,
        ): List<DtnInventoryItem> {
            inventoryReadCount += 1
            return inventory
                .filter { it.gid == gid }
                .filter { it.insertedAtSeconds > lastInsertedAt || (it.insertedAtSeconds == lastInsertedAt && it.eventId > lastEventId) }
                .sortedWith(compareBy<DtnInventoryItem> { it.insertedAtSeconds }.thenBy { it.eventId })
                .take(limit)
        }

        override fun existingEventIds(gid: String, eventIds: Collection<String>): Set<String> {
            return eventIds.filter { it in existing }.toSet()
        }

        override fun loadData(gid: String, eventIds: Collection<String>, limit: Int): List<DtnDataItem> {
            return eventIds.mapNotNull { data[it] }.take(limit)
        }

        override fun listDependencyRepairWants(gid: String, limit: Int): List<String> {
            dependencyRepairReadCount += 1
            check(!failOnDependencyRepairRead) { "dependency repair store read was not expected" }
            return dependencyRepairWants.take(limit)
        }
    }

    private class FakeDtnSyncStateStore(
        val cursors: MutableMap<String, DtnSyncCursor> = LinkedHashMap(),
    ) : DtnSyncStateStore {
        val seen = ArrayList<String>()
        val successes = ArrayList<String>()
        val failures = ArrayList<String>()
        val savedCursors = ArrayList<String>()

        override fun markPeerSeen(peerId: String, gid: String, sessionId: String?, nowSeconds: Long) {
            seen += "$peerId:$gid:${sessionId.orEmpty()}:$nowSeconds"
        }

        override fun loadCursor(peerId: String, gid: String): DtnSyncCursor? {
            return cursors["$peerId:$gid"]
        }

        override fun saveCursor(peerId: String, gid: String, cursor: DtnSyncCursor, nowSeconds: Long) {
            cursors["$peerId:$gid"] = cursor
            savedCursors += "$peerId:$gid:${cursor.lastInsertedAt}:${cursor.lastEventId}:$nowSeconds"
        }

        override fun markSyncSuccess(peerId: String, gid: String, nowSeconds: Long) {
            successes += "$peerId:$gid:$nowSeconds"
        }

        override fun markSyncFailure(peerId: String, gid: String, backoffUntilSeconds: Long?) {
            failures += "$peerId:$gid:${backoffUntilSeconds ?: 0}"
        }
    }
}
