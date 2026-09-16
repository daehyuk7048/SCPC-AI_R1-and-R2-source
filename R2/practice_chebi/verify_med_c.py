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

print(f"=== MED-C (unseen surface): {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
steps = pr["step_results"]; S = {s["step_id"]: s for s in steps}

c5 = S["C05"]
check("C05 TERM course usable within deadline (ACT)", c5["decision_state"] == "ACT"
      and any("COURSE" in i or "TERM" in i for i in c5["selected_context_ids"]))
check("C05 excludes family distractor", all("family" not in i for i in c5["selected_context_ids"]))

c7 = S["C07"]
check("C07 expired TERM never reused (실패 경계)",
      not any("COURSE" in i or "TERM" in i for i in c7["selected_context_ids"]))

c8 = S["C08"]
check("C08 other med unaffected by expiry (per-med)", c8["decision_state"] == "ACT"
      and any("DOSE_PLAN" in i for i in c8["selected_context_ids"]))

c9 = S["C09"]
inv9, pre9 = c9["invalidated_state_ids"], c9["preserved_state_ids"]
check("C09 revoke bites only perm_x memories", len(inv9) > 0
      and all("med_x" in i for i in inv9))
check("C09 med_y course preserved (kept permission)",
      any("med_y" in i for i in pre9))
check("C09 no overlap", not set(inv9) & set(pre9))

c11 = S["C11"]
check("C11 kill-while-offline: epoch bump + chain",
      c11["process_epoch"] == S["C10"]["process_epoch"] + 1
      and S["C10"]["state_after_sha256"] == c11["state_before_sha256"])
check("C11 network OFFLINE persisted across kill", c11["network_state"] == "OFFLINE")

c12 = S["C12"]
check("C12 offline decision -> honest WAIT", c12["decision_state"] == "WAIT" and c12["action"] is None)

c14 = S["C14"]
check("C14 replay(DUPLICATE hint) retries -> honest ASK (revoked med, no basis)",
      c14["decision_state"] == "ASK")

c16 = S["C16"]
check("C16 re-granted permission: new learning reused (ACT)", c16["decision_state"] == "ACT"
      and any("DOSE_PLAN" in i for i in c16["selected_context_ids"]))

c17 = S["C17"]
check("C17 tombstones for deleted med", len(c17["tombstone_ids"]) > 0)

c18 = S["C18"]
check("C18 resurrection attempt rejected (tombstoned med)",
      c18["decision_state"] == "PENDING" and len(c18["invalidated_state_ids"]) == 0
      and not any("mem-med_y-COURSE" in i for i in c18["preserved_state_ids"]))

c19 = S["C19"]
check("C19 export reports honest PENDING (undelivered refills remain)",
      c19["decision_state"] == "PENDING")

check("C full hash chain (19 steps incl. kill)", all(
    steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

print()
print("MED-C: " + ("ALL PASS" if ok else "SOME FAILED"))
sys.exit(0 if ok else 1)
