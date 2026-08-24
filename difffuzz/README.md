# difffuzz — Differential Fuzzer for LXMF Peer-Sync Offer/Response Handling

Black-box, wire-level differential testing between LXMF implementations,
focused on the surface where
[PR#38](https://github.com/torlando-tech/LXMF-kt/pull/38)'s Greptile P1
("Unbounded peer response selection") lives: the **offer-response
processor** a node runs when a propagation PEER answers its sync offer.

## Why

Static parity matrices (173/173 methods) prove methods exist and pass
self-written tests. They cannot prove edge-case behavior matches, and
say nothing about hostile inputs. This fuzzer runs identical adversarial
scenarios against two implementations over real RNS links and diffs the
observable outcomes. Modeled on FreeTAKTeam/LXMF-rs' fuzzing program
(cargo-fuzz targets + CI smoke gate + pre-release campaigns +
"reproducers become regression tests"), adapted to LXMF's Python/Kotlin
pair via the `lxmf-conformance` bridge protocol.

## Architecture

```
   VICTIM (impl under test)             ATTACKER (always Python)
   kt bridge JAR or                     fuzz_bridge.py =
   reference bridge                     vendored reference bridge +
        |                               CLASS-WIDE /offer handler patch
        |  TCP client iface   TCP server iface (loopback)
        |
        +-- initiates peer sync (link -> /offer request)
        |                                      |
        |          malicious reply crafted HERE per armed mode
        |<--- reply + Resource transfer -------|
```

- The victim runs UNMODIFIED protocol code (kt gets passive fz_*
  instrumentation commands compiled into the conformance bridge; Python
  victims run the same fuzz bridge with reply-mode `pristine`).
- Attack modes: `pristine`, `honest`, `dup` (every wanted ID x3),
  `unoffered` (ghost IDs never offered), `mixed` (dups+ghosts), `empty`,
  `garbage` (undecodable msgpack).
- Signals per round: `victim_offered/outgoing` (what the victim pushed),
  `attacker_incoming` (what arrived), `amplification_ratio`
  (sent/seeded — the P1 amplification signature), plus store/inbox state.
- Deterministic seeding (SHA-256-derived transient IDs) => the py↔py
  baseline and the kt↔py probe see identical scenarios; any behavioral
  difference is implementation, not chance.

## Usage

```bash
# One-time env: RNS + LXMF source checkouts, msgpack+cryptography, JDK17+
export PYTHON_RNS_PATH=/path/to/Reticulum
export PYTHON_LXMF_PATH=/path/to/LXMF
export KT_BRIDGE_JAR=/path/to/LXMFConformanceBridge.jar   # kt runs need this
export JAVA_BIN=$(which java)

cd difffuzz

# 1) Record the Python-reference baseline (exit 0, writes baseline.json)
python3 fuzzer.py --impls py,py

# 2) Run the Kotlin probe and diff against it
python3 fuzzer.py --impls kt,py          # exit 0 clean, 1 divergences

# Subset runs
python3 fuzzer.py --impls kt,py --modes dup,mixed
```

Reports land in `/tmp/lxmf-fuzz/report.json`; baseline in
`/tmp/lxmf-fuzz/baseline.json` (override with `FUZZ_BASELINE`).

## Semantics of results

- Exit 0 — every signal matches the Python baseline, or differs only in
  ways pinned in `KNOWN_DIVERGENCES` with rationale.
- Exit 1 — unexpected divergences exist. These are REAL GAPS until fixed;
  do not pin them to make CI green.
- `KNOWN_DIVERGENCES` currently pins the PR#38 P1 family (kt skips
  unoffered IDs and continues; Python aborts the whole sync via KeyError)
  and an `offered`-counter bookkeeping difference (kt counts every offer
  attempt; Python only completed/no-want rounds). When the P1 fix lands,
  remove those pins — the fuzzer then enforces the hardened behavior as
  the new parity baseline.

## Findings (2026-08-24, kt v0.0.22 deps @ feat/full-parity 772e289)

| Scenario | Python reference | Kotlin | Class |
|---|---|---|---|
| honest | 1.0x, clean | 1.0x, clean | match (harness sanity) |
| dup | 3.0x amplification | 4.0x amplification | P1 both; kt amplifies MORE (also re-offers prior-round entries) — needs triage |
| unoffered ghosts | aborts (0 sent) | transfers (17 sent) | P1 (pinned KNOWN) |
| mixed dups+ghosts | aborts | 25 sent, 6.25x | P1 (partially pinned) |
| empty list | no-want completion | no-want completion | match |
| garbage bytes | ignored | ignored | match |
| large mixed | aborts | 39 sent, attacker received 14 | P1 worst case |

Secondary finding: `offered` counter semantics differ (attempts vs
completed rounds) — candidate `port-deviations.md` entry or kt fix.

## Files

- `fuzzer.py` — driver: scenario generator, dual-bridge executor, differ
- `fuzz_bridge.py` — Python bridge = vendored reference + fz_* commands
  + class-wide `/offer` adversarial patch (modes)
- `vendor/lxmf_python.py`, `vendor/bridge_client.py` — vendored from
  torlando-tech/lxmf-conformance (do not edit; regenerate on bump)
