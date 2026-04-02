package network.reticulum.lxmf

import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Test propagation sync flow — reproduces the startup race condition
 * where PropagationNodeManager sets the relay hash BEFORE the router
 * is created, so LXMRouter's activePropagationNodeHash stays null.
 */
class PropagationSyncTest {
    private lateinit var identity: Identity

    @BeforeEach
    fun setup() {
        identity = Identity.create()
        try {
            Transport.start(identity, enableTransport = false)
        } catch (_: Exception) {
            // already started
        }
    }

    @AfterEach
    fun teardown() {
    }

    @Test
    fun `sync fails when active node hash set before start`() {
        // Reproduce: hash set on LXMRouter BEFORE start() loads propagation nodes
        val router = LXMRouter(identity = identity)

        // Create and add a propagation node manually (simulates what loadPropagationNodes does)
        val nodeIdentity = Identity.create()
        val nodeDestHash =
            network.reticulum.destination.Destination.hash(
                nodeIdentity,
                "lxmf",
                "propagation",
            )
        val nodeHexHash = nodeDestHash.joinToString("") { "%02x".format(it) }

        // Step 1: Set active BEFORE the node is in the map (simulates PropagationNodeManager racing)
        // This saves activePropagationNodeHash but node isn't in propagationNodes yet
        val setResult = router.setActivePropagationNode(nodeHexHash)
        println("setActivePropagationNode (before add): $setResult")

        // Step 2: NOW add the node (simulates loadPropagationNodes in start())
        val node =
            LXMRouter.PropagationNode(
                destHash = nodeDestHash,
                identity = nodeIdentity,
                displayName = "TestNode",
                isActive = true,
            )
        router.addPropagationNode(node)

        // Step 3: Verify getActivePropagationNode finds it
        val active = router.getActivePropagationNode()
        println("getActivePropagationNode: ${active?.hexHash}")
        assertNotNull(active, "Node should be findable after being added to map")

        // Step 4: Start and request sync
        router.start()
        router.requestMessagesFromPropagationNode()
        val state = router.propagationTransferState
        println("State after sync: $state")

        // Should NOT be FAILED (should be LINK_ESTABLISHING)
        assertNotEquals(
            LXMRouter.PropagationTransferState.FAILED,
            state,
            "Sync should not fail when node is in map and hash is set",
        )

        router.stop()
    }

    @Test
    fun `sync fails when hash never forwarded to router`() {
        // Reproduce EXACT bug: hash set on NativeReticulumProtocol but router is null,
        // so LXMRouter never gets the hash
        val router = LXMRouter(identity = identity)

        // Add node to map but DON'T set activePropagationNodeHash
        val nodeIdentity = Identity.create()
        val nodeDestHash =
            network.reticulum.destination.Destination.hash(
                nodeIdentity,
                "lxmf",
                "propagation",
            )
        val node =
            LXMRouter.PropagationNode(
                destHash = nodeDestHash,
                identity = nodeIdentity,
                isActive = true,
            )
        router.addPropagationNode(node)
        router.start()

        // activePropagationNodeHash is null — getActivePropagationNode returns null
        val active = router.getActivePropagationNode()
        assertNull(active, "No active node when hash not set")

        router.requestMessagesFromPropagationNode()
        assertEquals(
            LXMRouter.PropagationTransferState.FAILED,
            router.propagationTransferState,
            "Should fail when no active node hash is set",
        )

        router.stop()
    }
}
