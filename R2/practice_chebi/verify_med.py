import json, sys
import jsonschema

R2 = r"C:/Users/UserK/Desktop/SCPC/R2"
KIT = r"C:/Users/UserK/Desktop/SCPC/data/2차/SCPC2026_R2_CANDIDATE_RELEASE_v3/candidate_release_v3"
schema = json.load(open(f"{KIT}/candidate_kit/PROBE_RESULT.schema.json", encoding="utf-8"))

ok = True
def check(name, cond):
    global ok
    print(("  PASS " if cond else "  FAIL ") + name)
    if not cond: ok = False

def load(case):
    pr = json.load(open(f"{R2}/practice_chebi/RUN_{case}/PROBE_RESULT.json", encoding="utf-8"))
    st = json.load(open(f"{R2}/practice_chebi/RUN_{case}/RUNNER_STATUS.json", encoding="utf-8"))
    return pr, st

# ===== MED-A: 약별 권위 + 기한형 유효 + distractor =====
pr, st = load("A")
print(f"=== MED-A: {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
steps = pr["step_results"]; S = {s["step_id"]: s for s in steps}

a5 = S["A05"]
check("A05 ACT with ONLY med_bp context", a5["decision_state"] == "ACT"
      and len(a5["selected_context_ids"]) > 0
      and all("med_bp" in i for i in a5["selected_context_ids"]))
check("A05 no abx/spouse leak", not any(("med_abx" in i) or ("spouse" in i) for i in a5["selected_context_ids"]))

a6 = S["A06"]
inv6 = a6["invalidated_state_ids"]
check("A06 correction hits only med_bp (per-med authority)",
      len(inv6) > 0 and all("med_bp" in i for i in inv6))
check("A06 abx untouched", not any("med_abx" in i for i in inv6))

a7 = S["A07"]
check("A07 TERM abx still ACT after bp correction", a7["decision_state"] == "ACT"
      and any("TERM" in i for i in a7["selected_context_ids"]))

a8 = S["A08"]
check("A08 uses corrected prescription", a8["decision_state"] == "ACT"
      and any("PRESCRIPTION" in i for i in a8["selected_context_ids"]))

check("A pre-correction value absent from entire result (값 수준 부재)",
      "bp_morning_1tab" not in json.dumps(pr))

a9, a10 = S["A09"], S["A10"]
check("A09 epoch bump + chain", a9["process_epoch"] == S["A08"]["process_epoch"] + 1
      and S["A08"]["state_after_sha256"] == a9["state_before_sha256"])
check("A10 ACT after relaunch", a10["decision_state"] == "ACT")
check("A full hash chain", all(
    steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

# ===== MED-B: offline/replay/OOO/arrival/delete =====
pr, st = load("B")
print(f"=== MED-B: {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
steps = pr["step_results"]; S = {s["step_id"]: s for s in steps}

b4 = S["B04"]
check("B04 offline -> honest WAIT, no action", b4["decision_state"] == "WAIT" and b4["action"] is None)

b6 = S["B06"]
check("B06 replay retries waited decision -> ACT", b6["decision_state"] == "ACT" and b6["action"])
check("B06 action bound to ORIGINAL event id", "EV-MB-DECISION-01" in b6["action"]["action_id"])
check("B06 action PROPOSED before outcome", b6["action"]["commit_state"] == "PROPOSED")

b7 = S["B07"]
check("B07 stale directive (bp_v0) rejected, nothing invalidated",
      b7["decision_state"] == "PENDING" and len(b7["invalidated_state_ids"]) == 0)

b8 = S["B08"]
check("B08 arrival memory appears in preserved (실도착 증거)",
      any("arrival_" in i for i in b8["preserved_state_ids"]))

b9 = S["B09"]
check("B09 tombstones created", len(b9["tombstone_ids"]) > 0)

b10 = S["B10"]
check("B10 epoch bump + chain", b10["process_epoch"] == b9["process_epoch"] + 1
      and b9["state_after_sha256"] == b10["state_before_sha256"])

b11 = S["B11"]
check("B11 deleted med -> honest ASK, no resurrection",
      b11["decision_state"] == "ASK" and len(b11["selected_context_ids"]) == 0)

full = json.dumps(pr)
check("deleted/stale values absent from entire result",
      "bp_old_2tab" not in full)
check("B full hash chain", all(
    steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

print()
print("ALL RELATIONS: " + ("PASS" if ok else "SOME FAILED"))
sys.exit(0 if ok else 1)
