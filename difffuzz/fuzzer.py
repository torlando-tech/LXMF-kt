#!/usr/bin/env python3
"""Differential fuzzer for LXMF peer-sync offer/response handling.

Spawns two bridges (victim + attacker), seeds the victim's store, has
the victim initiate peer-sync toward the attacker, and lets the
attacker answer with adversarial /offer replies per an armed mode.
After each round it dumps BOTH nodes' observable state and compares
behavior against a recorded baseline.

Roles:
  victim   = implementation under test (kt bridge or pristine reference)
  attacker = fuzz_bridge.py (Python reference + class-wide /offer patch)

Usage:
  python3 fuzzer.py --impls py,py            # harness self-check
  python3 fuzzer.py --impls kt,py            # the real differential run
  python3 fuzzer.py --modes dup,mixed ...    # subset of attack modes

A divergence is reported when the victim's observable outcome differs
from the reference (py-victim) outcome for the same scenario seed —
with one documented exception: Python's KeyError abort path vs Kotlin's
skip-and-continue on unoffered IDs is EXPECTED to diverge until PR#38's
P1 fix lands; the fuzzer pins that as a known_divergence with rationale.

Exit code: 0 = no unexpected divergences, 1 = unexpected divergences,
2 = environment/setup failure.
"""

import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "vendor"))

from bridge_client import BridgeClient  # noqa: E402

ATTACK_MODES = ["honest", "dup", "unoffered", "mixed", "empty", "garbage"]

# Known divergences pinned with rationale. Keyed by (scenario_name, signal).
# These are NOT failures: they are documented places where the Kotlin
# port intentionally (or knowingly-until-fixed) differs from Python.
KNOWN_DIVERGENCES = {
    # P1 from PR#38 review: kt skips unoffered IDs and transfers the rest;
    # Python raises KeyError inside offer_response -> sync aborts, no
    # transfer at all. Fix pending on feat/full-parity.
    ("unoffered-ghosts", "victim_outgoing"): (
        "kt skips unknown IDs (null-safe lookup) while Python aborts the "
        "whole sync via KeyError - this IS the PR#38 P1 gap"
    ),
    ("unoffered-ghosts", "amplification_ratio"): (
        "same root cause as victim_outgoing above"
    ),
    ("unoffered-ghosts", "attacker_incoming"): (
        "ghost-requested entries actually reach the attacker on kt"
    ),
    ("mixed-dup-plus-ghosts", "victim_offered"): (
        "counter bookkeeping: kt counts every offer attempt, py only "
        "completed rounds"
    ),
    ("empty-list", "victim_offered"): (
        "counter bookkeeping difference (see mixed note)"
    ),
}


def now_ms():
    return int(time.time() * 1000)


class Scenario:
    def __init__(self, name, mode, count=4, size=512):
        self.name = name
        self.mode = mode
        self.count = count
        self.size = size


def default_scenarios():
    scenarios = []
    # Baseline first: honest reply must transfer exactly the offered set.
    scenarios.append(Scenario("baseline-honest", "honest"))
    # The P1 probes.
    scenarios.append(Scenario("dup-only", "dup"))
    scenarios.append(Scenario("unoffered-ghosts", "unoffered"))
    scenarios.append(Scenario("mixed-dup-plus-ghosts", "mixed"))
    # Malformed shapes.
    scenarios.append(Scenario("empty-list", "empty"))
    scenarios.append(Scenario("garbage-bytes", "garbage"))
    # Size variation for transfer accounting (unique seed => unique IDs).
    scenarios.append(Scenario("large-payload-mixed-2", "mixed", count=3, size=4096))
    return scenarios


# ---------------------------------------------------------------------------
# Bridge lifecycle helpers
# ---------------------------------------------------------------------------

def spawn_victim(impl, workdir, loglevel="3"):
    if impl == "py":
        # The py VICTIM also runs fuzz_bridge.py, but in "pristine" reply
        # mode: the /offer shim is transparent unless an attack is armed,
        # and the victim side needs the fz_* instrumentation commands
        # (seed/dump/peer/sync) which the plain reference bridge lacks.
        return BridgeClient(
            [sys.executable, os.path.join(HERE, "fuzz_bridge.py")],
            timeout=60,
            env={
                "PYTHON_RNS_PATH": os.environ.get("PYTHON_RNS_PATH", ""),
                "PYTHON_LXMF_PATH": os.environ.get("PYTHON_LXMF_PATH", ""),
                "LXMF_CONFORMANCE_RNS_LOGLEVEL": loglevel,
            },
        )
    if impl == "kt":
        jar = os.environ.get(
            "KT_BRIDGE_JAR",
            "/workspace/lxmf-kt/conformance-bridge/build/libs/LXMFConformanceBridge.jar",
        )
        java = os.environ.get("JAVA_BIN", "java")
        return BridgeClient([java, "-jar", jar], timeout=90)
    raise ValueError(f"unknown impl: {impl}")


def spawn_attacker(loglevel="3"):
    return BridgeClient(
        [sys.executable, os.path.join(HERE, "fuzz_bridge.py")],
        timeout=60,
        env={
            "PYTHON_RNS_PATH": os.environ.get("PYTHON_RNS_PATH", ""),
            "PYTHON_LXMF_PATH": os.environ.get("PYTHON_LXMF_PATH", ""),
            "LXMF_CONFORMANCE_RNS_LOGLEVEL": loglevel,
        },
    )


# ---------------------------------------------------------------------------
# Topology wiring (TCP loopback, same pattern as lxmf-conformance fixtures)
# ---------------------------------------------------------------------------

def wire_topology(victim, attacker):
    """Attacker hosts the TCP listener; victim connects to it."""
    r = attacker.execute("lxmf_add_tcp_server_interface")
    port = r["port"]
    victim.execute(
        "lxmf_add_tcp_client_interface", target_host="127.0.0.1", target_port=port
    )
    return port


def announce_pair(victim, attacker):
    """Exchange announces so both sides can recall identities/paths.

    The attacker's PROPAGATION destination must be announced too — the
    victim links to it for peer sync. The reference bridge only announces
    the delivery destination via lxmf_announce, so after the delivery
    exchange we enable PN on the attacker (fz_dump_state does this as a
    side effect now) and trigger a propagation-node announce.
    """
    av = victim.execute("lxmf_announce")
    time.sleep(0.5)
    aa = attacker.execute("lxmf_announce")
    time.sleep(0.5)

    # Enable PN machinery on the attacker, pin its peering cost to 1
    # (so the victim's cost-1 key validates), and announce its
    # propagation destination so path+identity are recallable.
    attacker.execute("fz_dump_state")
    try:
        attacker.execute("fz_set_peering_cost", cost=1)
    except Exception:
        pass
    try:
        attacker.execute("fz_announce_propagation_node")
        time.sleep(1.0)
    except Exception as e:
        # Fallback: the base lxmf_announce re-announces the PN destination
        # when propagation is enabled (with the 20s delayed thread — slow
        # but functional).
        print(f"[!] fz_announce_propagation_node failed ({e}); falling back")
        attacker.execute("lxmf_announce")
        time.sleep(2.0)

    return av["delivery_destination_hash"], aa["delivery_destination_hash"]


def enable_and_get_prop_hash(node):
    """Enable PN on a node (via dump's side effect) and return its prop hash."""
    r = node.execute("fz_dump_state")
    return r["propagation_destination_hash"]


# ---------------------------------------------------------------------------
# One differential round
# ---------------------------------------------------------------------------

def run_round(victim_impl, victim, attacker, scenario, verbose=False):
    """Returns dict with both states, attack log, and derived signals."""
    result = {"scenario": scenario.name, "mode": scenario.mode}

    # 0. Reset the attacker's defensive state (previous rounds may have
    #    throttled the victim) and arm this round's attack mode.
    try:
        attacker.execute("fz_clear_throttle")
    except Exception:
        pass
    attacker.execute("fz_set_reply_mode", mode=scenario.mode)

    # 1. Seed the VICTIM store: entries marked unhandled-for the ATTACKER's
    #    prop hash, so the victim offers them toward the attacker.
    att_state_probe = attacker.execute("fz_dump_state")
    att_prop_hash = att_state_probe["propagation_destination_hash"]

    seeded = victim.execute(
        "fz_seed_store",
        count=scenario.count,
        size=scenario.size,
        seed=f"{scenario.name}",
        unhandled_for=att_prop_hash,
    )
    result["seeded_ids"] = seeded["transient_ids"]
    if isinstance(result["seeded_ids"], str):
        result["seeded_ids"] = json.loads(result["seeded_ids"])

    # 2. Victim peers toward the attacker's propagation destination and
    #    initiates sync. Peering key generation is async in both impls
    #    (first sync() call spawns the PoW job), so we trigger twice.
    victim.execute("fz_peer", destination_hash=att_prop_hash, peering_cost=1)
    victim.execute("fz_sync_peers")

    expected_ids = set(result["seeded_ids"])
    deadline = time.time() + 45
    offered_seen = False
    while time.time() < deadline:
        time.sleep(0.5)
        log = attacker.execute("fz_get_attack_log")
        events = log["events"] if isinstance(log["events"], list) else json.loads(log["events"])
        # Only an offer carrying THIS round's seeded IDs counts as landed;
        # stale events from earlier rounds are drained and discarded.
        for ev in events:
            if set(ev.get("offered", []) or []) & expected_ids:
                offered_seen = True
                break
        if offered_seen:
            break
        # Re-trigger (peering key may still be generating).
        victim.execute("fz_sync_peers")

    result["attack_landed"] = offered_seen
    if not offered_seen:
        result["error"] = "no offer reached the attacker within timeout"
        return result

    # 3. Wait for the reply transfer / processing to settle.
    time.sleep(4)

    # 4. Dump final state from both nodes.
    vstate = victim.execute("fz_dump_state")
    astate = attacker.execute("fz_dump_state")

    def norm_peer_counters(state):
        out = {}
        for phex, p in state["peers"].items():
            out[phex] = {
                "offered": p["offered"],
                "outgoing": p["outgoing"],
                "incoming": p["incoming"],
                "currently_transferring_count": len(p["currently_transferring"]),
                "state": p["state"],
            }
        return out

    def entry_handled_sets(state):
        return {
            tid: {
                "handled": sorted(e["handled_peers"]),
                "unhandled": sorted(e["unhandled_peers"]),
            }
            for tid, e in state["entries"].items()
        }

    result["victim"] = {
        "entry_count": vstate["entry_count"],
        "entries": entry_handled_sets(vstate),
        "peer_counters": norm_peer_counters(vstate),
        "inbox_count": len(vstate["inbox"]["messages"]),
    }
    result["attacker"] = {
        "entry_count": astate["entry_count"],
        "entries": entry_handled_sets(astate),
        "peer_counters": norm_peer_counters(astate),
    }
    return result


# ---------------------------------------------------------------------------
# Signal extraction + differencing
# ---------------------------------------------------------------------------

def extract_signals(round_result):
    """Reduce a round into comparable signals.

    Flow under test: VICTIM offers -> attacker replies -> VICTIM SENDS.
    So the P1-relevant counters are the victim's OFFERED/OUTGOING (what
    it pushed) and the attacker's INCOMING (what actually arrived).
    """
    v = round_result.get("victim", {})
    a = round_result.get("attacker", {})
    signals = {}

    vp = v.get("peer_counters", {})
    ap = a.get("peer_counters", {})

    signals["victim_offered"] = sum(p["offered"] for p in vp.values())
    signals["victim_outgoing"] = sum(p["outgoing"] for p in vp.values())
    signals["attack_landed"] = round_result.get("attack_landed", False)
    signals["attacker_incoming"] = sum(p["incoming"] for p in ap.values())
    signals["victim_entry_count"] = v.get("entry_count", 0)
    # Distinct vs repeated: if victim_outgoing > distinct seeded count,
    # duplicates were honored (the P1 amplification signature).
    seeded = round_result.get("seeded_ids") or []
    if isinstance(seeded, str):
        seeded = json.loads(seeded)
    signals["seeded_count"] = len(seeded)
    signals["amplification_ratio"] = (
        round(signals["victim_outgoing"] / len(seeded), 2) if seeded else 0.0
    )
    # Deliveries into the victim's own inbox stay a future-real-payload
    # signal; synthetic filler won't parse as valid LXMs.
    signals["victim_inbox_deliveries"] = v.get("inbox_count", 0)
    return signals


def compare_to_reference(baseline_signals, probe_signals, mode):
    """Return list of (signal, base, probe) divergences not known-pinned."""
    divergences = []
    for sig, base_val in sorted(baseline_signals.items()):
        probe_val = probe_signals.get(sig)
        if base_val != probe_val:
            if (mode, sig) in KNOWN_DIVERGENCES:
                continue
            divergences.append((sig, base_val, probe_val))
    return divergences


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--impls", default="kt,py",
                    help="victim_impl,attacker_impl where impl in {kt,py}")
    ap.add_argument("--modes", default=",".join(ATTACK_MODES))
    ap.add_argument("--loglevel", default="3")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    victim_impl, attacker_impl = [x.strip() for x in args.impls.split(",")]
    modes = [m.strip() for m in args.modes.split(",")]

    if attacker_impl != "py":
        print("[!] attacker must be py (fuzz bridge patches the python handler)")
        return 2

    os.makedirs("/tmp/lxmf-fuzz", exist_ok=True)
    report = {"victim_impl": victim_impl, "rounds": []}
    unexpected_total = []

    victim = None
    attacker = None
    try:
        print(f"[*] spawning attacker (py fuzz bridge)...")
        attacker = spawn_attacker(args.loglevel)
        print(f"[*] spawning victim ({victim_impl})...")
        victim = spawn_victim(victim_impl, "/tmp/lxmf-fuzz", args.loglevel)

        print("[*] initializing bridges (lxmf_init)...")
        victim.execute("lxmf_init")
        attacker.execute("lxmf_init")

        print("[*] wiring topology (tcp loopback)...")
        wire_topology(victim, attacker)
        print("[*] exchanging announces...")
        announce_pair(victim, attacker)

        # Fresh victim node per mode would be cleanest, but bridge init
        # cost (~2s) x modes is fine to pay only if state bleeds between
        # rounds; seeding uses unique per-scenario seeds so rounds are
        # independent by construction.
        for scenario in default_scenarios():
            if scenario.mode not in modes:
                continue
            print(f"\n=== round {scenario.name} (mode={scenario.mode}) ===")
            try:
                rr = run_round(victim_impl, victim, attacker, scenario, args.verbose)
            except Exception as e:
                rr = {"scenario": scenario.name, "mode": scenario.mode,
                      "error": f"{type(e).__name__}: {e}"}
                print(f"    round error: {rr['error']}")
            report["rounds"].append(rr)

            if "error" not in rr:
                sigs = extract_signals(rr)
                rr["signals"] = sigs
                print(f"    signals: {json.dumps(sigs)}")
                if not sigs.get("attack_landed"):
                    print("    !! attack never landed — infrastructure issue")

    finally:
        for node in (victim, attacker):
            if node:
                try:
                    node.close()
                except Exception:
                    pass

    out_path = "/tmp/lxmf-fuzz/report.json"
    with open(out_path, "w") as f:
        json.dump(report, f, indent=2, default=str)
    print(f"\n[*] report written to {out_path}")

    # Cross-compare against the recorded py-victim baseline if present.
    baseline_path = os.environ.get("FUZZ_BASELINE", "/tmp/lxmf-fuzz/baseline.json")
    if victim_impl == "py":
        # This run IS the baseline: record signals per scenario.
        baseline = {r["scenario"]: r.get("signals", {}) for r in report["rounds"]}
        with open(baseline_path, "w") as f:
            json.dump(baseline, f, indent=2)
        print(f"[*] baseline signals recorded to {baseline_path}")
        _ = unexpected_total
        return 0

    if os.path.isfile(baseline_path):
        with open(baseline_path) as f:
            baseline = json.load(f)
        # Cross-compare per SCENARIO (not mode): two scenarios may share a
        # mode, and baseline entries are keyed by scenario name.
        print("\n[*] differential comparison vs python baseline:")
        any_unexpected = False
        for r in report["rounds"]:
            scen = r.get("scenario")
            sigs = r.get("signals")
            if not sigs:
                continue
            base_sigs = baseline.get(scen, {})
            divs = [
                (k, base_sigs.get(k), sigs.get(k))
                for k in sorted(base_sigs)
                if base_sigs.get(k) != sigs.get(k)
                and (scen, k) not in KNOWN_DIVERGENCES
            ]
            known = [
                (k, base_sigs.get(k), sigs.get(k))
                for k in sorted(base_sigs)
                if base_sigs.get(k) != sigs.get(k)
                and (scen, k) in KNOWN_DIVERGENCES
            ]
            for k, b, p in known:
                print(f"    [{scen}] KNOWN divergence: {k}: py={b} kt={p}")
            if divs:
                any_unexpected = True
                for k, b, p in divs:
                    print(f"    [{scen}] UNEXPECTED divergence: {k}: py={b} kt={p}")
                    unexpected_total.append((scen, k, b, p))
            else:
                print(f"    [{scen}] behavior matches reference")
        if any_unexpected:
            return 1
    else:
        print(f"[!] no baseline at {baseline_path} — run with --impls py,py first")

    return 0


if __name__ == "__main__":
    sys.exit(main())
