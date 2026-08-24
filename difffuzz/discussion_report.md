# Peer-sync robustness: three related flaws in `LXMPeer.offer_response` / `resource_concluded` (with minimal repro)

While porting Python LXMF to Kotlin ([libre-7/LXMF-kt](https://github.com/libre-7/LXMF-kt), PR #38), our differential fuzzer surfaced three related correctness/resilience flaws in the propagation-peer sync path. All three reproduce on pristine `master` (`795fdaa`) with a ~5-second, networking-free script that drives the real `LXMPeer.offer_response()` / `resource_concluded()` code paths — script attached at the bottom.

**Scope/severity framing:** all three require an **authenticated, accepted peering** (valid PoW peering key + known identity) to trigger. So this is about resilience against a malicious or buggy *peer*, not a remote attack by an unauthenticated node. Affects `lxmd` operators and anything using peer sync.

Reference lines below are against current master: `LXMF/LXMPeer.py`, `offer_response()` at 400–486 and `resource_concluded()` at 492–532.

---

## Flaw 1 — Reply IDs are not bounded by the offer; duplicates amplify transfers

`offer_response()`, WantedIds branch (lines 450–452):

```python
for transient_id in response:
    wanted_messages.append(self.router.propagation_entries[transient_id])
    wanted_message_ids.append(transient_id)
```

The reply's IDs are looked up in the **global** `propagation_entries` store with no intersection against `self.last_offer` and no dedup. Consequences:

- **Duplicate IDs** → the same message is re-read from disk and appended to the transfer payload once per occurrence.
- **Unoffered IDs** → entries outside the advertised offer are disclosed and transferred.

Repro output (4-entry store, reply = `[A,A,A,B,C,D]`, i.e. one ID tripled):

```
reply contained      : 6 IDs (4 distinct)
payload carries      : 6 messages
transfer index holds : 6 IDs
=> AMPLIFIED x1.5: 4 distinct messages wire-sent 6 times
```

Amplification is attacker-chosen: N repetitions of an ID means N disk reads and N wire copies of that message per sync round.

## Flaw 2 — KeyError abort marks offered messages handled anyway (self-DoS)

Same branch: the "not-wanted ⇒ mark handled" loop (443–448) runs **before** the wanted loop (450–452). If the reply contains any ID absent from the store, the wanted loop raises `KeyError`, which the blanket handler (480–486) catches — but by then every legitimately-offered entry has already been marked handled:

```
before: 4 unhandled entries offered
reply wanted <real-id> + ghost <never-offered-id>
handler hit KeyError internally (blanket except) -> sync aborted, nothing transferred
after abort      : 3/4 offered entries marked HANDLED, 1 remain unhandled
=> SELF-DOS CONFIRMED: 3 of 4 offered entries permanently marked handled during an ABORTED sync
```

Net effect: **one malicious/buggy reply permanently desynchronizes the victim from that peer** — those messages will never be re-offered, and nothing was actually transferred. The victim's own store still holds them; only this peer relationship is poisoned. Recovery requires manual intervention (unpeer/re-peer or wiping peer state).

Suggested fix direction: intersect `response` with `last_offer` before both loops (also fixes Flaw 1), e.g. process only `[t for t in response if t in self.last_offer]`, deduplicated.

## Flaw 3 — Unsent message recorded as delivered when its backing file is missing

Payload build (457–468) skips entries whose file can't be read:

```python
for message_entry in wanted_messages:
    file_path = message_entry[1]
    if os.path.isfile(file_path):
        ...lxm_list.append(lxmf_data)
```

…but `wanted_message_ids` (line 469) keeps the full list, and `resource_concluded()` (500–502) marks **every ID in the index** handled on completion:

```python
for transient_id in self.currently_transferring_messages:
    self.add_handled_message(transient_id)
    self.remove_unhandled_message(transient_id)
```

Repro (file deleted between seeding and sync):

```
offered 4 entries; file for #ba543a23… deleted pre-sync
payload carries     : 3 messages (3 readable files)
transfer index holds: 4 IDs (includes deleted-file entry: True)
after completion    : 4/4 marked handled; deleted-file entry marked handled: True
=> message whose bytes NEVER LEFT the node is recorded as delivered
```

Related: under `STRATEGY_PERSISTENT`, completion immediately re-syncs when unhandled messages remain (lines 523–524) — so a permanently unreadable entry now cycles forever (link establishment → offer → partial transfer → completion → re-sync), with no retry cap. Slow (bounded by link RTT) but unbounded. This second-order behavior exists whether or not Flaw 3 is fixed, since an unfixed store keeps the entry unhandled.

Fix direction: build the transfer index from exactly the entries whose bytes entered the payload (zip payload appends with ID appends), so completion bookkeeping matches what was actually sent. For the retry loop, some form of retry cap / dead-letter marking after K failed attempts would bound it.

---

## Verification

All outputs above come from the attached script (`verify_upstream_flaws.py`): it builds a real `LXMRouter` with propagation enabled, seeds four deterministic store entries, constructs a real `LXMPeer`, and invokes `offer_response()` directly with crafted request receipts — only the transport layer (`RNS.Resource`) is stubbed to capture payloads. Runs offline in ~5 s against a stock checkout. Happy to provide it inline if attachments are awkward here.

For reference, the Kotlin port has shipped fixes for Flaws 1–3 (bounded+deduped selection, transferred-only bookkeeping) in [PR #38](https://github.com/torlando-tech/LXMF-kt/pull/38), including a differential fuzzer (`difffuzz/`) that drives adversarial offer replies through real RNS links against both implementations — happy to upstream any of it if useful.
