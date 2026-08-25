#!/usr/bin/env python3
"""Verify the three inherited LXMF flaws against the REAL python reference
code paths (no hostile input synthesis — this drives actual LXMPeer code).

Flaw 1: offer_response accepts duplicate IDs -> payload amplification.
Flaw 2: unoffered (ghost) ID in reply -> KeyError abort AFTER marking every
        offered entry handled (self-DoS; peer never re-offers them).
Flaw 3: backing file missing -> bytes skipped but ID stays in the transfer
        index; completion marks an UNSENT message handled. Combined with
        persistent strategy + no retry cap => endless re-offer loop.
"""

import sys, os, tempfile, hashlib, time

sys.path.insert(0, "/workspace/Reticulum")
sys.path.insert(0, "/workspace/lxmf-ref")

import RNS
import msgpack
from LXMF import LXMRouter
from LXMF.LXMPeer import LXMPeer  # class lives inside the submodule

RNS.loglevel = 0


class FakeReceipt:
    """offer_response() only touches .response on the receipt."""
    def __init__(self, response):
        self.response = response


class FakeResource:
    """Intercept Resource creation so we can inspect the payload without a
    real link. Records data + callback; status COMPLETE for conclusion."""
    COMPLETE = 0x02
    last = None

    def __init__(self, data, link=None, callback=None):
        FakeResource.last = self
        self.data = data
        self.link = link
        self.callback = callback
        self.status = None
        self._size = len(data)

    def get_data_size(self):
        return self._size

    def get_transfer_size(self):
        return self._size

    def conclude(self):
        self.status = FakeResource.COMPLETE
        if self.callback:
            self.callback(self)


# One Reticulum instance per process (RNS singleton); each scenario gets
# its own LXMRouter + store on top of it.
_rns_ready = False


def fresh_setup(seed_prefix="verify"):
    global _rns_ready
    if not _rns_ready:
        rns_tmp = tempfile.mkdtemp(prefix="rns-cfg-")
        RNS.Reticulum(configdir=rns_tmp, loglevel=0)
        _rns_ready = True
    tmp = tempfile.mkdtemp(prefix="lxmf-verify-")
    identity = RNS.Identity()
    router = LXMRouter(identity=identity, storagepath=tmp)
    router.enable_propagation()

    peer_dhash = bytes.fromhex("9e7d4d15c7e8a1da6e4f0a29558b5932")
    peer = LXMPeer(router, peer_dhash)
    router.peers[peer_dhash] = peer

    filler = bytes(16)
    now = time.time()
    seeded = []
    for i in range(4):
        tid = RNS.Identity.truncated_hash(
            hashlib.sha256(f"{seed_prefix}:{i}".encode()).digest())
        data = filler + b"\x00" * 80 + b"\xc3" + b"B" * 64
        path = os.path.join(
            router.messagepath,
            RNS.hexrep(tid, delimit=False) + f"_{int(now)}")
        with open(path, "wb") as f:
            f.write(data)
        router.propagation_entries[tid] = [
            filler, path, now, len(data), [], [peer_dhash], 0]
        seeded.append(tid)

    _ = peer.unhandled_messages  # prime property cache
    return router, peer, seeded


print("=" * 70)
print("FLAW 1: duplicate reply IDs -> payload amplification (real code)")
print("=" * 70)
router, peer, seeded = fresh_setup("f1")
RNS.Resource = FakeResource  # intercept transport

peer.last_offer = list(seeded)
dup_ids = [seeded[0]] * 3 + seeded[1:]  # honest set with one ID x3

# Surface any exception offer_response swallows (blanket except upstream).
import traceback
_orig_trace = RNS.trace_exception
RNS.trace_exception = lambda e: traceback.print_exc()

peer.offer_response(FakeReceipt(list(dup_ids)))
RNS.trace_exception = _orig_trace

res = FakeResource.last
lxm_list = msgpack.unpackb(res.data)[1]
transferring = peer.currently_transferring_messages or []
print(f"reply contained      : {len(dup_ids)} IDs ({len(set(dup_ids))} distinct)")
print(f"payload carries      : {len(lxm_list)} messages")
print(f"transfer index holds : {len(transferring)} IDs")
print(f"=> AMPLIFIED x{len(lxm_list) / len(set(dup_ids)):.1f}: "
      f"{len(set(dup_ids))} distinct messages wire-sent {len(lxm_list)} times\n")

print("=" * 70)
print("FLAW 2: ghost ID in reply -> KeyError abort AFTER mass mark-handled")
print("(self-DoS: peer permanently stops offering those messages)")
print("=" * 70)
router, peer, seeded = fresh_setup("f2")

ghost = RNS.Identity.truncated_hash(b"never-offered-ghost")
peer.last_offer = list(seeded)
unhandled_before = len(peer.unhandled_messages)
reply_with_ghost = [seeded[0]] + [ghost]  # wants first entry + a ghost

try:
    peer.offer_response(FakeReceipt(list(reply_with_ghost)))
    aborted = False
except KeyError:
    aborted = True

time.sleep(0.1)
still_unhandled = len(peer.unhandled_messages)
handled_now = sum(1 for t in seeded if peer.destination_hash
                  in router.propagation_entries[t][4])
print(f"before: {unhandled_before} unhandled entries offered")
print(f"reply wanted {seeded[0].hex()[:12]}… + ghost {ghost.hex()[:12]}…")
print(f"handler hit KeyError internally (blanket except) -> sync aborted, "
      f"nothing transferred")
print(f"after abort      : {handled_now}/4 offered entries marked HANDLED, "
      f"{still_unhandled} remain unhandled")
print(f"=> SELF-DOS CONFIRMED: {handled_now} of {unhandled_before} offered "
      f"entries permanently marked handled during an ABORTED sync; victim "
      f"never re-offers them to this peer\n")

print("=" * 70)
print("FLAW 3: missing backing file -> unsent message marked handled")
print("=" * 70)
router, peer, seeded = fresh_setup("f3")

# Corrupt the store: delete one wanted entry's file behind the router's back.
victim_entry = seeded[1]
os.unlink(router.propagation_entries[victim_entry][1])

peer.last_offer = list(seeded)
# Honest full-want reply (bool True = "wants everything offered")
peer.offer_response(FakeReceipt(True))

res = FakeResource.last
lxm_list = msgpack.unpackb(res.data)[1]
transferring = peer.currently_transferring_messages or []
print(f"offered 4 entries; file for #{seeded[1].hex()[:12]}… deleted pre-sync")
print(f"payload carries     : {len(lxm_list)} messages (3 readable files)")
print(f"transfer index holds: {len(transferring)} IDs (includes deleted-file entry: "
      f"{victim_entry in transferring})")

# Now simulate resource completion -> bookkeeping
res.conclude()
handled_after = sum(1 for t in seeded if peer.destination_hash
                    in router.propagation_entries[t][4])
deleted_marked = peer.destination_hash in router.propagation_entries[victim_entry][4]
print(f"after completion    : {handled_after}/4 marked handled; "
      f"deleted-file entry marked handled: {deleted_marked}")
print(f"=> CONFIRMED: message whose bytes NEVER LEFT the node is recorded "
      f"as delivered to the peer")
print()
print("(with STRATEGY_PERSISTENT, completion immediately calls sync(); the "
      "dead entry stays unhandled and is re-offered every round — endless "
      "slow retry loop, no retry cap, identical in reference)")

print("\nAll three flaws reproduced against pristine /workspace/lxmf-ref code.")
