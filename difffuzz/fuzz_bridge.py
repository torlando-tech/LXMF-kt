#!/usr/bin/env python3
"""Differential-fuzz bridge: vendored reference bridge + adversarial /offer server.

Architecture (black-box, wire-level):

  VICTIM (impl under test)              ATTACKER (always this script)
  unmodified kt bridge OR               fuzz_bridge.py, PN-enabled,
  pristine reference bridge             offer_request patched class-wide.
          |                                      |
          +-- TCP client iface --> TCP server iface
          +-- initiates peer sync (/offer request over link)
          |                                      |
          |            malicious reply crafted HERE (per armed mode)
          |<-- reply + Resource transfer --------|

The victim's OFFER RESPONSE processor is the code under test (where the
PR#38 Greptile P1 lives: LXMPeer.offer_response accepting unoffered /
duplicate IDs). The attacker therefore plays the RESPONDER role.

Modes (armed via fz_set_reply_mode):
  pristine   - delegate to the untouched reference handler (default;
               lets this bridge act as the honest victim in py<->py runs)
  honest     - reference-equivalent reply, but computed by the wrapper
               (sanity baseline for the crafting logic itself)
  dup        - every wanted ID repeated 3x
  unoffered  - honest prefix + IDs that were never offered (not in store)
  mixed      - duplicates AND ghosts together (worst case)
  empty      - empty ID list (malformed "some wanted" answer)
  garbage    - undecodable msgpack junk

Every answered offer is logged (fz_get_attack_log) so the driver can
assert the attack actually happened.

Extra commands (all prefixed fz_):
  fz_seed_store       inject deterministic synthetic entries into THIS
                      node's store, marked unhandled-for a given peer
  fz_dump_state       full observable state (entries + peer counters +
                      inbox drain)
  fz_peer             create a manual peering entry toward a hash
  fz_set_reply_mode   arm/disarm the adversarial responder
  fz_get_attack_log   what was offered to us and what we answered
  fz_reset_attack_log clear the log
"""

import hashlib
import os
import sys
import threading
import time
import traceback

_RNS_PATH = os.environ.get("PYTHON_RNS_PATH")
_LXMF_PATH = os.environ.get("PYTHON_LXMF_PATH")
if _RNS_PATH:
    sys.path.insert(0, os.path.abspath(_RNS_PATH))
if _LXMF_PATH:
    sys.path.insert(0, os.path.abspath(_LXMF_PATH))

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.join(_HERE, "vendor"))

import RNS  # noqa: E402
import LXMF  # noqa: E402

import lxmf_python as base  # noqa: E402

# LXMF.LXMPeer is the SUBMODULE (package attr); the class lives inside it.
_LXMPeerCls = LXMF.LXMPeer.LXMPeer

REPLY_MODES = (
    "pristine", "honest", "dup", "unoffered", "mixed", "empty", "garbage",
)


class _AttackState:
    def __init__(self):
        self.lock = threading.Lock()
        self.mode = "pristine"
        self.log = []


ATTACK = _AttackState()


def _hex(b):
    return RNS.hexrep(b, delimit=False) if b else ""


def _ghost(seed, index):
    """Deterministic 'never offered' transient ID."""
    return RNS.Identity.truncated_hash(
        hashlib.sha256(f"fz-ghost:{seed}:{index}".encode()).digest()
    )


# ---------------------------------------------------------------------------
# Class-level /offer interception
# ---------------------------------------------------------------------------

_ORIG_OFFER_REQUEST = LXMF.LXMRouter.offer_request


def _fz_offer_request(self, path, data, request_id, link_id, remote_identity, requested_at):
    """Reference offer_request with an adversarial final answer.

    Runs the REAL handler first so validation/throttling/store bookkeeping
    stay reference-exact; then replaces only the returned payload according
    to the armed mode. With mode=pristine this is a transparent shim.
    """
    try:
        offered_hex = []
        try:
            if isinstance(data, list) and len(data) >= 2 and isinstance(data[1], list):
                offered_hex = [_hex(t) for t in data[1]]
        except Exception:
            pass

        honest = _ORIG_OFFER_REQUEST(
            self, path, data, request_id, link_id, remote_identity, requested_at
        )

        with ATTACK.lock:
            mode = ATTACK.mode

        if mode == "pristine":
            return honest

        reply, kind = _craft_reply(self, honest, offered_hex, mode)

        with ATTACK.lock:
            ATTACK.log.append({
                "mode": mode,
                "offered": offered_hex,
                "honest_reply_kind": _kind_of(honest),
                "reply_kind": kind,
            })
        return reply

    except Exception:
        traceback.print_exc(file=sys.stderr)
        return _ORIG_OFFER_REQUEST(
            self, path, data, request_id, link_id, remote_identity, requested_at
        )


def _kind_of(reply):
    if reply is True:
        return "bool_true"
    if reply is False:
        return "bool_false"
    if reply is None:
        return "none"
    if isinstance(reply, int):
        return f"error_code_{reply}"
    if isinstance(reply, list):
        return f"id_list({len(reply)})"
    if isinstance(reply, (bytes, bytearray)):
        return "binary"
    return type(reply).__name__


def _craft_reply(router, honest, offered_hex, mode):
    """Build the adversarial replacement payload (same msgpack types the
    reference handler may legally return: bool / int / list-of-bytes)."""
    import msgpack

    offered_bytes = [bytes.fromhex(h) for h in offered_hex]

    def ids_payload(ids):
        # Reference replies carry raw binary transient IDs.
        return ids

    if mode == "honest":
        return honest, _kind_of(honest)

    if mode == "dup":
        # Duplicate-heavy answer. Force the list shape even when the
        # reference would have replied True (all wanted): duplicates are
        # only meaningful in list form.
        if isinstance(honest, list):
            duped = []
            for t in honest:
                duped.extend([t, t, t])
            return ids_payload(duped), f"id_list({len(duped)})-dup"
        if honest is True:
            duped = []
            for t in offered_bytes:
                duped.extend([t, t, t])
            return ids_payload(duped), f"id_list({len(duped)})-dup-from-true"
        return honest, _kind_of(honest)

    if mode == "unoffered":
        g1, g2 = _ghost("u", 0), _ghost("u", 1)
        base_ids = honest if isinstance(honest, list) else (
            offered_bytes if honest is True else [])
        forged = list(base_ids[:1]) + [g1, g2]
        return ids_payload(forged), f"id_list({len(forged)})-unoffered"

    if mode == "mixed":
        g = _ghost("m", 0)
        base_ids = honest if isinstance(honest, list) else (
            offered_bytes if honest is True else [])
        forged = []
        for t in base_ids:
            forged.extend([t, t])
        forged.append(g)
        return ids_payload(forged), f"id_list({len(forged)})-mixed"

    if mode == "empty":
        return ids_payload([]), "id_list(0)-empty"

    if mode == "garbage":
        return b"\xc1\xff\xfa\x11", "binary-garbage"

    return honest, _kind_of(honest)


# Install the shim immediately. With mode=pristine it is transparent.
LXMF.LXMRouter.offer_request = _fz_offer_request


# ---------------------------------------------------------------------------
# fz_ commands
# ---------------------------------------------------------------------------

def cmd_fz_seed_store(params):
    """Inject deterministic synthetic entries marked unhandled-for a peer."""
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_seed_store")
    router = base._state.router
    if not router.propagation_node:
        router.enable_propagation()

    count = int(params.get("count", 4))
    size = int(params.get("size", 512))
    seed = str(params.get("seed", "fuzz"))
    unhandled_for = bytes.fromhex(params["unhandled_for"])  # victim prop hash

    os.makedirs(router.messagepath, exist_ok=True)
    filler_dest = bytes(16)
    now = time.time()
    created = []

    for i in range(count):
        tid = RNS.Identity.truncated_hash(
            hashlib.sha256(f"{seed}:{i}".encode()).digest())
        data = filler_dest + b"\x00" * 80 + b"\xc3" + ("B" * size).encode()
        fname = _hex(tid) + "_" + str(now)
        fpath = os.path.join(router.messagepath, fname)
        with open(fpath, "wb") as f:
            f.write(data)
        router.propagation_entries[tid] = [
            filler_dest,           # 0 dst
            fpath,                 # 1 storage location
            now,                   # 2 received
            len(data),             # 3 size
            [],                    # 4 handled peers
            [unhandled_for],       # 5 unhandled peers
            0,                     # 6 stamp value
        ]
        created.append(_hex(tid))

    return {
        "seeded": len(created),
        "transient_ids": created,
        "propagation_destination_hash": _hex(router.propagation_destination.hash),
    }


def cmd_fz_dump_state(params):
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_dump_state")
    router = base._state.router
    # Ensure the propagation machinery exists even if this node was never
    # seeded (the driver probes the attacker's prop hash via this dump).
    if not router.propagation_node:
        router.enable_propagation()
        # Synthetic fuzz messages carry no valid PoW stamps; relax the
        # receiver-side minimum so transfers aren't rejected+throttled.
        # This is a deliberate fuzzer-only policy change on the ATTACKER
        # node only — victims keep reference stamp policy.
        router.propagation_stamp_cost = 1
        router.propagation_stamp_cost_flexibility = 1

    entries = {}
    for tid, e in router.propagation_entries.items():
        entries[_hex(tid)] = {
            "size": int(e[3]),
            "handled_peers": sorted(_hex(p) for p in e[4]),
            "unhandled_peers": sorted(_hex(p) for p in e[5]),
        }

    peers = {}
    for dhash, peer in router.peers.items():
        transferring = peer.currently_transferring_messages
        peers[_hex(dhash)] = {
            "state": int(peer.state),
            "offered": int(peer.offered),
            "outgoing": int(peer.outgoing),
            "incoming": int(peer.incoming),
            "currently_transferring": (
                [_hex(t) for t in transferring] if transferring else []
            ),
        }

    since_seq = int(params.get("since_seq", 0))
    with base._state._inbox_lock:
        messages = [m for m in base._state._inbox if m["seq"] > since_seq]
        last_seq = base._state._inbox_seq

    return {
        "propagation_destination_hash": _hex(router.propagation_destination.hash),
        "entry_count": len(entries),
        "entries": entries,
        "peers": peers,
        "inbox": {"messages": messages, "last_seq": last_seq},
    }


def cmd_fz_peer(params):
    """Manual peering entry toward another node's propagation destination.

    Stamp-cost fields are pinned to cost 1 so peering-key PoW generation
    is instant and the victim's /offer validation (which checks the key
    against ITS OWN peering_cost, default 18) still accepts: Python's
    validate_peering_key only requires value >= target cost... which a
    cost-1 key does NOT satisfy. The victim therefore needs
    peering_cost=1 too — set on the VICTIM side by the driver via
    fz_set_peering_cost (kt bridge equivalent: router.maxPeeringCost and
    peer() with peeringCost=1).
    """
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_peer")
    router = base._state.router
    dhash = bytes.fromhex(params["destination_hash"])

    ident = RNS.Identity.recall(dhash)
    if ident is None:
        raise RuntimeError("peer identity not recalled yet — announce first")

    peer = _LXMPeerCls(router, dhash)
    peer.identity = ident
    peer.destination = RNS.Destination(
        ident, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "propagation")
    # Deterministic stamp-cost knowledge; must match the victim's
    # configured peering_cost or offers bounce ERROR_INVALID_KEY.
    cost = int(params.get("peering_cost", 1))
    peer.propagation_stamp_cost = 1
    peer.propagation_stamp_cost_flexibility = 1
    peer.peering_cost = cost
    # Upstream sync() dereferences these in a log line even when unset
    # (crash on manually-built peers — real peers get them from announce
    # app data). Pin generous defaults so the fuzz flow runs.
    peer.propagation_transfer_limit = float(params.get("transfer_limit_kb", 51200))
    peer.propagation_sync_limit = float(params.get("sync_limit_kb", 51200))
    router.peers[dhash] = peer
    return {"ok": True, "peered": _hex(dhash), "peering_cost": cost}


def cmd_fz_set_peering_cost(params):
    """Set THIS node's peering_cost (what inbound offer keys are checked against)."""
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_set_peering_cost")
    router = base._state.router
    cost = int(params["cost"])
    router.peering_cost = cost
    return {"peering_cost": cost}


def cmd_fz_announce_propagation_node(params):
    """Announce the propagation destination NOW (skips NODE_ANNOUNCE_DELAY).

    The reference announce_propagation_node() sleeps 20s in a thread
    before announcing — fine for production, hostile to a test rig.
    """
    if base._state.router is None:
        raise RuntimeError(
            "lxmf_init must be called before fz_announce_propagation_node")
    router = base._state.router
    if not router.propagation_node:
        router.enable_propagation()
    router.propagation_destination.announce(
        app_data=router.get_propagation_node_app_data())
    return {"announced": _hex(router.propagation_destination.hash)}


def cmd_fz_sync_peers(params):
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_sync_peers")
    for peer in list(base._state.router.peers.values()):
        peer.sync()
    return {"triggered": True}


def cmd_fz_clear_throttle(params):
    """Reset inbound-sync defensive state between fuzz rounds.

    Synthetic seeded messages carry no valid PoW stamps, so the attacker
    node's own defenses (rightly) reject + throttle the victim after the
    first transfer. Real peers would stay blocked; the fuzzer clears the
    state so every round starts clean.
    """
    if base._state.router is None:
        raise RuntimeError("lxmf_init must be called before fz_clear_throttle")
    router = base._state.router
    cleared = {
        "throttled_peers": len(router.throttled_peers),
        "validating": len(router.validating_pn_stamps_from),
        "accepted_links": len(router.accepted_offer_links),
        "inbound_transfers": router.propagation_resources_transferring,
    }
    router.throttled_peers.clear()
    router.validating_pn_stamps_from.clear()
    with router.accepted_offer_links_lock:
        router.accepted_offer_links.clear()
    # Drop any lingering active propagation links so fresh ones are used,
    # and clear the attack log so the driver never reads a previous
    # round's events as this round's landing signal.
    for link in list(getattr(router, "active_propagation_links", [])):
        try:
            link.teardown()
        except Exception:
            pass
    router.active_propagation_links = []
    with ATTACK.lock:
        ATTACK.log = []
        ATTACK.pending_offer_ids = []
    return {"cleared": cleared}


def cmd_fz_set_reply_mode(params):
    mode = params.get("mode", "pristine")
    if mode not in REPLY_MODES:
        raise ValueError(f"mode must be one of {REPLY_MODES}")
    with ATTACK.lock:
        ATTACK.mode = mode
    return {"mode": mode}


def cmd_fz_get_attack_log(params):
    with ATTACK.lock:
        events = list(ATTACK.log)
        ATTACK.log = []
    return {"events": events}


base.COMMANDS.update({
    "fz_seed_store": cmd_fz_seed_store,
    "fz_dump_state": cmd_fz_dump_state,
    "fz_peer": cmd_fz_peer,
    "fz_set_peering_cost": cmd_fz_set_peering_cost,
    "fz_announce_propagation_node": cmd_fz_announce_propagation_node,
    "fz_sync_peers": cmd_fz_sync_peers,
    "fz_clear_throttle": cmd_fz_clear_throttle,
    "fz_set_reply_mode": cmd_fz_set_reply_mode,
    "fz_get_attack_log": cmd_fz_get_attack_log,
})

if __name__ == "__main__":
    base._main()
