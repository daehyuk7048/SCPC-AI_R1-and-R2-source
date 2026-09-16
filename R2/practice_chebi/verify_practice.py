import json, sys
import jsonschema

R2 = r"C:/Users/UserK/Desktop/SCPC/R2"
KIT = r"C:/Users/UserK/Desktop/SCPC/data/2차/SCPC2026_R2_CANDIDATE_RELEASE_v3/candidate_release_v3"
schema = json.load(open(f"{KIT}/candidate_kit/PROBE_RESULT.schema.json", encoding="utf-8"))

def load_case(case):
    pr = json.load(open(f"{R2}/practice_chebi/RUN_{case}/PROBE_RESULT.json", encoding="utf-8"))
    st = json.load(open(f"{R2}/practice_chebi/RUN_{case}/RUNNER_STATUS.json", encoding="utf-8"))
    return pr, st

ok = True
def check(name, cond):
    global ok
    print(("  PASS " if cond else "  FAIL ") + name)
    if not cond: ok = False

# ---------- PRACTICE-A ----------
pr, st = load_case("A")
print(f"=== PRACTICE-A: {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
S = {s["step_id"]: s for s in pr["step_results"]}
steps = pr["step_results"]

a4 = S["A4"]
check("A4 ACT with context", a4["decision_state"] == "ACT" and len(a4["selected_context_ids"]) >= 2)
check("A4 excludes distractor(routine_swim)", all("swim" not in i for i in a4["selected_context_ids"]))
check("A4 uses one-off first time", any("ONE_OFF" in i.upper() for i in a4["selected_context_ids"]))

a5 = S["A5"]
check("A5 invalidates old stable", any("STABLE" in i.upper() for i in a5["invalidated_state_ids"]))
check("A5 invalidates derived inference (descendant)", any(i.startswith("inf-") for i in a5["invalidated_state_ids"]))

a6 = S["A6"]
check("A6 partial revoke: nothing over-invalidated", len(a6["invalidated_state_ids"]) == 0 and len(a6["preserved_state_ids"]) > 0)

a7 = S["A7"]
check("A7 epoch bump", a7["process_epoch"] == S["A6"]["process_epoch"] + 1)
check("A7 chain continuity", S["A6"]["state_after_sha256"] == a7["state_before_sha256"])

a8 = S["A8"]
check("A8 ACT after recovery", a8["decision_state"] == "ACT")
check("A8 consumed one-off not reused", not any("ONE_OFF" in i.upper() for i in a8["selected_context_ids"]))
check("A8 uses corrected stable", any("STABLE" in i.upper() for i in a8["selected_context_ids"]))
check("A8 action is PROPOSED (not fake-completed)", a8["action"] and a8["action"]["commit_state"] == "PROPOSED")

check("A full hash chain", all(steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

# ---------- PRACTICE-B ----------
pr, st = load_case("B")
print(f"=== PRACTICE-B: {st['run_status']} {st['step_count']} steps ===")
jsonschema.validate(pr, schema); print("  PASS schema valid")
S = {s["step_id"]: s for s in pr["step_results"]}
steps = pr["step_results"]

b4 = S["B4"]
check("B4 offline -> honest WAIT, no action", b4["decision_state"] == "WAIT" and b4["action"] is None)

b6 = S["B6"]
check("B6 replay retries waited decision -> ACT", b6["decision_state"] == "ACT")
check("B6 action bound to ORIGINAL event id (exactly-once)",
      b6["action"] and "EV-B-DECISION-01" in b6["action"]["action_id"])
check("B6 action PROPOSED before outcome", b6["action"] and b6["action"]["commit_state"] == "PROPOSED")

b7 = S["B7"]
check("B7 out-of-order rejected, nothing invalidated", b7["decision_state"] == "PENDING" and len(b7["invalidated_state_ids"]) == 0)

b8 = S["B8"]
check("B8 delayed outcome arrival changes state", b8["state_before_sha256"] != b8["state_after_sha256"])

b9 = S["B9"]
check("B9 tombstones created", len(b9["tombstone_ids"]) > 0)

b10 = S["B10"]
check("B10 epoch bump + chain continuity",
      b10["process_epoch"] == b9["process_epoch"] + 1 and b9["state_after_sha256"] == b10["state_before_sha256"])

full = json.dumps(pr)
check("deleted original value absent from entire result", "locker_note_1234" not in full)
check("stale OOO value absent from entire result", "old_towel_only" not in full)
check("B full hash chain", all(steps[i]["state_after_sha256"] == steps[i+1]["state_before_sha256"] for i in range(len(steps)-1)))

print()
print("ALL RELATIONS: " + ("PASS" if ok else "SOME FAILED"))
sys.exit(0 if ok else 1)
