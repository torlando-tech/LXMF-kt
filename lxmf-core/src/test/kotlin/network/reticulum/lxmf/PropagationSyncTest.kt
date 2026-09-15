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

        // Should NOT be FAILED. With the path-request preflight in place, the
        // transient state here is PATH_REQUESTED (no real interfaces in the
        // test, so Transport has no path); previously it was LINK_ESTABLISHING.
        assertNotEquals(
            LXMRouter.PropagationTransferState.FAILED,
            state,
            "Sync should not fail when node is in map and hash is set",
        )

        router.close()
    }

    @Test
    fun `requestMessages enters PATH_REQUESTED when no path is known`() {
        // Mirror python LXMF/LXMRouter.py:514-520: when Transport has no path
        // to the active propagation node, the request must transition into
        // PR_PATH_REQUESTED so the path-wait job can resolve before linking.
        val router = LXMRouter(identity = identity)
        val nodeIdentity = Identity.create()
        val nodeDestHash =
            network.reticulum.destination.Destination.hash(
                nodeIdentity,
                "lxmf",
                "propagation",
            )
        val nodeHexHash = nodeDestHash.joinToString("") { "%02x".format(it) }

        router.setActivePropagationNode(nodeHexHash)
        router.addPropagationNode(
            LXMRouter.PropagationNode(
                destHash = nodeDestHash,
                identity = nodeIdentity,
                isActive = true,
            ),
        )
        router.start()

        // Test precondition: with enableTransport=false there are no interfaces,
        // so Transport cannot have a path to a freshly-generated identity.
        check(!Transport.hasPath(nodeDestHash)) { "Transport unexpectedly knew a path" }

        router.requestMessagesFromPropagationNode()

        assertEquals(
            LXMRouter.PropagationTransferState.PATH_REQUESTED,
            router.propagationTransferState,
            "Should enter PATH_REQUESTED when no path is known",
        )

        router.close()
    }

    @Test
    fun `duplicate requestMessages during PATH_REQUESTED is a no-op`() {
        // Concurrent callers (manual sync racing the periodic timer, etc.)
        // must not spawn a second path-wait job. The first call sets state
        // to PATH_REQUESTED; the second call must early-return so we don't
        // race two `requestMessagesPathJob` coroutines that both retry when
        // the path arrives.
        val router = LXMRouter(identity = identity)
        val nodeIdentity = Identity.create()
        val nodeDestHash =
            network.reticulum.destination.Destination.hash(
                nodeIdentity,
                "lxmf",
                "propagation",
            )
        val nodeHexHash = nodeDestHash.joinToString("") { "%02x".format(it) }

        router.setActivePropagationNode(nodeHexHash)
        router.addPropagationNode(
            LXMRouter.PropagationNode(
                destHash = nodeDestHash,
                identity = nodeIdentity,
                isActive = true,
            ),
        )
        router.start()
        check(!Transport.hasPath(nodeDestHash)) { "Transport unexpectedly knew a path" }

        router.requestMessagesFromPropagationNode()
        assertEquals(
            LXMRouter.PropagationTransferState.PATH_REQUESTED,
            router.propagationTransferState,
            "First call should enter PATH_REQUESTED",
        )

        // Second call while the wait is in progress — must not advance state
        // or change the path-wait deadline.
        router.requestMessagesFromPropagationNode()
        assertEquals(
            LXMRouter.PropagationTransferState.PATH_REQUESTED,
            router.propagationTransferState,
            "Duplicate call must not transition out of PATH_REQUESTED",
        )

        router.close()
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

        router.close()
    }
}
