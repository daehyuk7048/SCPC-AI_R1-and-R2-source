import json, sys
import jsonschema

R2 = r"C:/Users/UserK/Desktop/SCPC/R2"
KIT = r"C:/Users/UserK/Desktop/SCPC/data/2차/SCPC2026_R2_CANDIDATE_RELEASE_v3/candidate_release_v3"
schema = json.load(open(f"{KIT}/candidate_kit/PROBE_RESULT.schema.json", encoding="utf-8"))
pr = json.load(open(f"{R2}/practice_chebi/RUN_D/PROBE_RESULT.json", encoding="utf-8"))
st = json.load(open(f"{R2}/practice_chebi/RUN_D/RUNNER_STATUS.json", encoding="utf-8"))

ok = True
def check(name, cond):
    global ok
    print(("  PASS " if cond else "  FAIL ") + name)
    if not cond: ok = False

print(f"=== MED-D (audit5 exec: cycle/until/cancel/delete): {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
steps = pr["step_results"]; S = {s["step_id"]: s for s in steps}

ARR = "mem-med_p-arrival_outcome-decl-refill_p_"

d4 = S["D04"]
arr1 = [i for i in d4["preserved_state_ids"] if i.startswith(ARR)]
check("D04 1st refill cycle arrives (decl arrival memory)", len(arr1) == 1)

d9 = S["D09"]
arr2 = [i for i in d9["preserved_state_ids"] if i.startswith(ARR)]
check("D09 2nd cycle arrival is a DISTINCT memory (no permanent loss)",
      len(set(arr2)) == 2 and set(arr1) < set(arr2))

d7 = S["D07"]
check("D07 UNTIL-only correction republishes the TERM memory",
      any("SHORT_TERM_SUPPLY" in i for i in d7["invalidated_state_ids"]))

d8 = S["D08"]
check("D08 TERM usable before corrected (shortened) deadline (ACT)",
      d8["decision_state"] == "ACT"
      and any("SHORT_TERM_SUPPLY" in i for i in d8["selected_context_ids"]))

d10 = S["D10"]
check("D10 corrected deadline enforced: TERM excluded after 06-15",
      d10["decision_state"] == "ACT"
      and not any("SHORT_TERM_SUPPLY" in i for i in d10["selected_context_ids"])
      and any("DOSE_PLAN" in i for i in d10["selected_context_ids"]))

d11 = S["D11"]
inv11, pre11 = d11["invalidated_state_ids"], d11["preserved_state_ids"]
check("D11 revoke bites only med_p scoped memories", len(inv11) > 0
      and all("med_p" in i for i in inv11))
check("D11 authority-neutral arrival history survives revoke",
      any(i.startswith(ARR) for i in pre11))

d12 = S["D12"]
act12 = d12["action"]
check("D12 replay of cancelled decision -> ABSTAIN (not CONFIRMED_COMPLETE)",
      d12["decision_state"] == "ABSTAIN")
check("D12 echoed action is the CANCELLED one (ledger consistent)",
      isinstance(act12, dict) and act12.get("commit_state") == "CANCELLED")

d14 = S["D14"]
check("D14 delete tombstones med_q only",
      any("med_q" in t for t in d14["tombstone_ids"])
      and not any("med_p" in t for t in d14["tombstone_ids"]))

d15 = S["D15"]
check("D15 cancelled refill never arrives (delete cancelled pending)",
      not any("mem-med_q-arrival" in i for i in d15["preserved_state_ids"])
      and d15["decision_state"] == "NO_DECISION")

d16 = S["D16"]
check("D16 deleted med decision -> honest ASK/ABSTAIN, nothing selected",
      d16["decision_state"] in ("ASK", "ABSTAIN")
      and not any("med_q" in i for i in d16["selected_context_ids"])
      and d16["action"] is None)

d17 = S["D17"]
check("D17 export NO_DECISION (no orphan pendings/PROPOSED after cancels)",
      d17["decision_state"] == "NO_DECISION")

check("D full hash chain (17 steps)", all(
    steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

print()
print("MED-D: " + ("ALL PASS" if ok else "SOME FAILED"))
sys.exit(0 if ok else 1)
