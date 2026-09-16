import json, sys
import jsonschema

R2 = r"C:/Users/UserK/Desktop/SCPC/R2"
KIT = r"C:/Users/UserK/Desktop/SCPC/data/2차/SCPC2026_R2_CANDIDATE_RELEASE_v3/candidate_release_v3"
schema = json.load(open(f"{KIT}/candidate_kit/PROBE_RESULT.schema.json", encoding="utf-8"))
pr = json.load(open(f"{R2}/practice_chebi/RUN_C/PROBE_RESULT.json", encoding="utf-8"))
st = json.load(open(f"{R2}/practice_chebi/RUN_C/RUNNER_STATUS.json", encoding="utf-8"))

ok = True
def check(name, cond):
    global ok
    print(("  PASS " if cond else "  FAIL ") + name)
    if not cond: ok = False

print(f"=== PRACTICE-C (unseen surface): {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
steps = pr["step_results"]; S = {s["step_id"]: s for s in steps}

c5 = S["C05"]
check("C05 ACT with 3-item minimal context", c5["decision_state"] == "ACT" and len(c5["selected_context_ids"]) == 3)
check("C05 excludes distractor(routine_commute)", all("commute" not in i for i in c5["selected_context_ids"]))

c6 = S["C06"]
inv, pre = c6["invalidated_state_ids"], c6["preserved_state_ids"]
check("C06 revoke BITES: auto_route stable invalidated", any("ROUTINE_PREFERENCE" in i for i in inv))
check("C06 partial: packing rule preserved", any("PACKING_RULE" in i for i in pre))
check("C06 no overlap", not set(inv) & set(pre))

c7 = S["C07"]
check("C07 ACT with reduced context (no revoked, no consumed)", c7["decision_state"] == "ACT"
      and not any("ROUTINE_PREFERENCE" in i or "ONCE" in i.upper() for i in c7["selected_context_ids"]))

c9 = S["C09"]
check("C09 kill-while-offline: epoch bump + chain", c9["process_epoch"] == S["C08"]["process_epoch"] + 1
      and S["C08"]["state_after_sha256"] == c9["state_before_sha256"])
check("C09 network OFFLINE persisted across kill", c9["network_state"] == "OFFLINE")

c10 = S["C10"]
check("C10 offline decision -> honest WAIT", c10["decision_state"] == "WAIT" and c10["action"] is None)

c12 = S["C12"]
check("C12 replay retries waited decision -> ACT bound to original event",
      c12["decision_state"] == "ACT" and c12["action"] and "EV-C-DECISION-03" in c12["action"]["action_id"])

c13 = S["C13"]
check("C13 arrivals change state (commit boundary)", c13["state_before_sha256"] != c13["state_after_sha256"])

c14 = S["C14"]
check("C14 tombstones for deleted entity", len(c14["tombstone_ids"]) > 0)

c15 = S["C15"]
check("C15 resurrection attempt rejected (nothing invalidated/created)",
      c15["decision_state"] == "PENDING" and len(c15["invalidated_state_ids"]) == 0)

c16 = S["C16"]
check("C16 deleted data NOT used: honest ASK", c16["decision_state"] == "ASK"
      and len(c16["selected_context_ids"]) == 0)

check("C full hash chain (17 steps incl. kill)", all(
    steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

full = json.dumps(pr)
check("deleted value absent from entire result", "passport_charger" not in full and "gift_for_host" not in full)

print()
print("PRACTICE-C: " + ("ALL PASS" if ok else "SOME FAILED"))
sys.exit(0 if ok else 1)
