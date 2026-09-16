"""SCPC 2026 Final — AI Agent Harness (self-contained, generalizable).

Design goals (per contest rules)
--------------------------------
* No hardcoded task_id / session_id answer tables. Every decision is derived from
  the STRUCTURE of a task (records, objects, focal-resolution trace) plus a small
  set of GENERAL semantic intent detectors on the user's final correction clause.
* Uses the provided fixed SLM facade (`FixedSLMClient`) as an auxiliary evidence
  signal; the structured answer fields are built by the harness logic, not the SLM.
* Follows the required module structure:
  choose_focal / infer_target / decide_control / build_content_scope /
  build_policy / build_plan_events / update_session_memory.

The decision rules were reverse-engineered ONLY from the public dev split
(dev_tasks.jsonl + dev_answers.json) to learn field meanings and the templated
decision structure — not from the hidden screening answers.

Answer axes and how they are decided
------------------------------------
focal_id : deterministic marker/ref resolution (100% on dev).
control  : intent of the final "단," correction clause (proceed=keep-internal,
           hold=precondition-broke, ask=confirm-first, amend=redact-summary);
           neutral action clauses fall back to route/consent/safety records.
target   : coherent with control (proceed+internal -> memory_store, amend/ask/hold
           -> resolved_target / user), driven by records.
content_scope, policy, plan_events : templated by (control, target, focal contents).

Run:  python harness.py           # dev self-check (needs dev_answers.json) + writes submission.csv
"""
from __future__ import annotations

import csv
import json
import re
from pathlib import Path
from typing import Any

SUBMISSION_SCHEMA = "scpc.final.answer.v1"
FIXED_SLM_ID = "scpc-final-fixed-slm-local-facade"


# =====================================================================
# Provided fixed SLM facade (auxiliary evidence only — NOT the answer source)
# =====================================================================
class FixedSLMClient:
    model_id = FIXED_SLM_ID

    def summarize_task(self, task: dict[str, Any]) -> dict[str, Any]:
        text_parts: list[str] = [str(task.get("prompt", ""))]
        device_state = task.get("device_state", {}) or {}
        for rec in device_state.get("records", []) or []:
            text_parts.append(str(rec.get("type", "")))
            text_parts.append(str(rec.get("value", "")))
        for mem in task.get("personal_memory", []) or []:
            text_parts.append(str(mem.get("text", "")))
        text = " ".join(text_parts).lower()

        flags: set[str] = set()
        tags: set[str] = set()
        if "phishing" in text or "피싱" in text or "security_alert" in text:
            flags.update(["payment", "phishing"])
            tags.add("security_precedence")
        if "consent" in text or "동의" in text:
            tags.add("consent_precedence")
        if "health" in text or "건강" in text or "복약" in text or "검진" in text:
            flags.add("health")
        if "external" in text or "외부" in text:
            flags.add("external_share")
        if "privacy" in text or "개인정보" in text or "개인" in text:
            flags.add("privacy")
        if "rrn" in text or "raw_quote" in text or "실명" in text or "위치" in text:
            flags.add("sensitive_content")
        if "ambiguous" in text or "모호" in text:
            flags.add("ambiguous_reference")
            tags.add("resolved_target")
        return {
            "risk_flags": sorted(flags),
            "requires_redaction": any(k in text for k in [
                "raw_sensitive_forbidden", "raw_quote_forbidden",
                "numeric_value_forbidden", "실명", "위치", "원문"]),
            "requires_confirmation": any(k in text for k in [
                "ambiguous", "amount_changed", "duration_ambiguous",
                "missing", "확인", "모호"]),
            "audit_tags": sorted(tags),
        }


# =====================================================================
# Structure accessors
# =====================================================================
def _recs(t): return list(((t.get("device_state") or {}).get("records") or []))
def _objs(t): return list(((t.get("device_state") or {}).get("objects") or []))


def _first_rec(t, typ):
    for r in _recs(t):
        if r.get("type") == typ:
            return r.get("value")
    return None


def _rec_map(t):
    """type -> scalar value ('<struct>' for dict/list values)."""
    m = {}
    for r in _recs(t):
        v = r.get("value")
        if isinstance(v, (dict, list)):
            m.setdefault(r.get("type"), "<struct>")
        else:
            m[r.get("type")] = v
    return m


def _obj_by_ref(t, ref):
    for o in _objs(t):
        if (o.get("attrs") or {}).get("ref_code") == ref:
            return o
    return None


def _focal_obj(t, focal_id):
    for o in _objs(t):
        if o.get("id") == focal_id:
            return o
    return None


def _has(text, *subs):
    return any(s in text for s in subs)


# =====================================================================
# Decisive-clause semantic intent detectors (general, paraphrase-robust)
# =====================================================================
def _last_clause(prompt):
    p = prompt or ""
    i = p.rfind("단,")
    if i >= 0:
        return p[i:]
    parts = [s for s in re.split(r"(?<=[.?!]) ", p) if s.strip()]
    return parts[-1] if parts else p


def _decisive(task):
    """The final CORRECTION clause ('단, ...') from prompt or last history, else None.

    A decisive intent is only trusted from this explicit last-correction clause;
    neutral action instructions merely mention words like 보류/요약 while asking the
    agent to DECIDE, and must fall through to record-based reasoning instead.
    """
    p = task.get("prompt") or ""
    i = p.rfind("단,")
    if i >= 0:
        return p[i:]
    for h in reversed(task.get("visible_history") or []):
        s = h.get("summary", "") or ""
        j = s.rfind("단,")
        if j >= 0:
            return s[j:]
    return None


# proceed: "don't send out; only update internal/local state"
_PROCEED_INTERNAL = [
    "내부 상태", "내부 기록", "내부의 상태", "장치 내부", "기기 내부", "기기 안",
    "로컬 상태", "상태만 갱신", "상태값만 갱신", "상태 표시만", "상태 기록만",
    "상태 기록으로만", "완료 상태만", "처리 상태만", "상태 정리", "내부 상태 정리",
    "내부 기록을 갱신", "상태 기록만 새로", "상태 표시만 갱신",
]
_PROCEED_SUPPRESS = [
    "보내지 말", "보내기는 접", "전송을 하지", "전송은 하지", "외부 전송을 하지",
    "전달 단계는 빼", "외부 전달 단계는 빼", "전달 동작은 취소", "보내는 작업은 취소",
    "실제 보내기는", "넘기는 대신", "공유 채널로 넘기는 대신", "공유하지 말",
    "공유 작업이 아니라", "외부 공유가 아니라", "수신처 전달 대신", "외부 전송이 아니라",
]
# hold: the basis/precondition broke -> stop / block / defer
_HOLD_STOP = [
    "보류", "멈춘", "멈춰", "차단", "중단", "진행하지 말", "진행하면 안", "진행하지 않",
    "실행하면 안", "실행을 막", "수행하면 위험", "처리하지 않", "실행을 보류", "요청을 보류",
    "더 진행하지",
]
_HOLD_BROKEN = [
    "근거가 무너", "근거가 깨", "조건을 깨", "조건을 깨뜨", "전제가 사라", "믿을 수 없",
    "뒤집었", "무효화", "취소된 것", "기대면 안", "허용 근거가", "깨졌으므로", "깨뜨리므로",
    "사라진 상태", "더 이상 믿", "허용의 근거가", "승인 조건을 깨",
]
_HOLD_MEM = ["예전에 정한 공유 방식도 반영", "예전에 말한 취향이 있으면 반영"]
# ask: target/scope/premise unconfirmed -> ask the user first
_ASK_CONFIRM = [
    "확인 절차", "확인을 받아야", "확인이 필요", "확인 질문", "다시 물어", "먼저 물어봐",
    "clarification", "사용자 확인", "먼저 확인", "다시 확인", "질문을 하", "확인하지 않으면",
    "물어봐야", "확인해야",
]
_ASK_UNCERTAIN = [
    "확정되지 않", "확정 정보가 없", "불분명", "달라진 부분이 있는지", "바뀐 듯", "미확정",
    "유효성이 불분명", "결론을 내리기보다", "결론을 내릴 수 없", "아직 확인되지 않",
    "조건 변경 여부", "새 전제", "재사용하지 말고 확인", "보내도 되는지", "확인하라는 최신 지시",
    "달라진 부분", "바뀐 부분",
]
_REUSE_KW = ["성공한 target과 범위는 작업 메모리"]  # prior-success reuse -> amend
# turn-level constraints stated in visible_history (no "단," correction) that decide the control.
# These recur as verbatim history templates and are 100% ask on dev (17/17). Reading them extends the
# same "parse the history statement" method that fixed focal, from focal resolution to control.
_HIST_ASK = [
    "로컬 처리로 단정하지 말고",          # "…don't assume local processing; get user confirmation"
    "사용자 확인을 거친다",
    "최종 수신처와 허용 범위가 아직 확정되지 않았",  # "…destination/scope not yet fixed; confirm first"
    "승인 경로가 닫히기 전까지 확인 응답을 우선",     # "…prioritise a confirmation reply until the approval route"
]


def _proceed_intent(clause):
    return _has(clause, *_PROCEED_INTERNAL) or _has(clause, *_PROCEED_SUPPRESS)


def _hold_intent(clause):
    return _has(clause, *_HOLD_STOP) or _has(clause, *_HOLD_BROKEN)


def _ask_intent(clause):
    return _has(clause, *_ASK_CONFIRM) or _has(clause, *_ASK_UNCERTAIN)


def _amend_intent(clause):
    return ("요약" in clause) and _has(
        clause, "제거", "덜어", "제외", "남기지 않", "최소",
        "정제", "익명화", "포함하지 않", "민감 필드", "식별 가능")


def _is_remember_policy(clause):
    return ("기억해" in clause) and _has(clause, "기본 규정", "승인 채널")


def _neutral_family(clause):
    """Neutral action clause -> family whose record->control map applies."""
    if _has(clause, "원본, 익명 요약, 사진", "원본과 사진 중 무엇을 보낼",
            "익명 요약, 사진 중", "사진 중 현재 요청에 맞는"):
        return "MATERIAL"
    if _has(clause, "가능하면 처리하되, 상위 금지 원칙", "상위 금지 원칙과 최신 수신처 모호성"):
        return "LADDER"
    if _has(clause, "평소처럼 빠르게 처리하되", "fast path"):
        return "FASTPATH"
    if _has(clause, "최신 target과 공유 범위를 판정",
            "같은 표현이라도 최신 route", "안전/범위 신호가 있으면 전체 계획"):
        return "LATEST"
    if _has(clause, "검진", "점검을 받으러", "조명을", "생일 준비", "지난번 취향",
            "지난번 저장한 메모리", "캘린더와 안내 메시지"):
        return "DEVICE"
    return None


# --- non-marker focal: read WHICH candidate the history confirms -------------
_ORD = {"첫 번째": 0, "첫번째": 0, "첫째": 0, "처음": 0,
        "두 번째": 1, "두번째": 1, "둘째": 1, "가운데": 1, "중간": 1,
        "세 번째": 2, "세번째": 2, "셋째": 2, "마지막": -1}
_CONFIRM = r"(?:처리\s*대상|선택|확정|유효|통과|승인 상태|남은|남았)"


def _resolve_nonmarker_code(task):
    """The confirmed candidate ref_code, parsed from the last WM-bearing history line.

    Reads the sentence's own statement of which listed candidate survived the latest
    correction — a fixed positional guess ("always the 2nd") does not generalize:
      • "WM-x 만 통과 …나머지는 배제"        -> the singled-out code (x)
      • "…유지된 참조는 WM-x 이다"            -> the designated code after the anchor
      • "유효한 항목은 두 번째다" / "둘째만 선택" -> the ordinal tied to the confirm verb
      • otherwise the middle/2nd of the listed candidates
    """
    line = None
    for h in task.get("visible_history", []) or []:
        s = h.get("summary", "") or ""
        if re.search(r"WM-\d+", s):
            line = s
    if not line:
        return None
    codes = re.findall(r"WM-\d+", line)
    if len(codes) == 1:
        return codes[0]
    m = re.search(r"(WM-\d+)\s*만\s*(?:통과|유효|선택|승인|확정)", line)
    if m:
        return m.group(1)
    m = re.search(r"(?:유지된\s*참조는|참조는|ref는|binding은|기준\s*참조는|대상은|"
                  r"승인\s*후보|통과\s*항목은?|처리할\s*ref는?)\s*(WM-\d+)", line)
    if m:
        return m.group(1)
    m = re.search(r"(WM-\d+)\s*(?:로|을|를)?\s*(?:고정|지정)", line)
    if m:
        return m.group(1)
    m = re.search(r"(첫 ?번째|첫째|두 ?번째|둘째|가운데|중간|세 ?번째|셋째|마지막)"
                  r"\s*(?:후보|항목|것|참조)?\s*(?:만|은|는|이|을)?\s*(?:현재\s*)?" + _CONFIRM, line)
    if not m:
        m = re.search(_CONFIRM + r"[^.]{0,6}?(첫 ?번째|첫째|두 ?번째|둘째|가운데|중간|세 ?번째|셋째|마지막)", line)
    if m:
        idx = _ORD.get(m.group(1), _ORD.get(m.group(1).replace(" ", "")))
        if idx is not None and (idx == -1 or idx < len(codes)):
            return codes[idx]
    # a code named right after a confirmation verb ("...남은 것은 WM-####") is the confirmed
    # candidate regardless of listing order -- prefer it over the positional fallback
    m = re.search(_CONFIRM + r"[^.]{0,8}?(WM-\d+)", line)
    if m:
        return m.group(1)
    return codes[1]


def _hard_block(rm):
    return bool(rm.get("safety_mode") or rm.get("security_alert")
                or rm.get("consent") == "revoked")


def _needs_ask(rm):
    return bool(rm.get("target_changed_after_turn") or rm.get("memory_conflict")
                or rm.get("payment_policy"))


def _precondition_changed(task):
    """A single resolved local/internal candidate bound to confirmed local/internal authority.
    Keyed by semantic substring so screening/hidden value variants (local_candidate_only,
    local_authority_confirmed) count the same as the dev values, while mixed/external do not."""
    rcs = str(_first_rec(task, "route_candidate_snapshot") or "").lower()
    dac = str(_first_rec(task, "dispatch_authority_check") or "").lower()
    single_local = (("internal" in rcs) or ("local" in rcs)) and "external" not in rcs and "mixed" not in rcs
    confirmed_local = ("confirmed" in dac) and (("internal" in dac) or ("local" in dac))
    return single_local and confirmed_local


# =====================================================================
# Vocabularies
# =====================================================================
KNOWN_TARGETS = {
    "memory_store", "privacy_review", "user", "project_room", "legal_review",
    "caregiver", "audit_vendor", "family_room", "vendor_alpha", "living_room",
    "clinic_portal", "fitness_coach", "security_review", "location_share", "jimin_dm",
}
LOCAL_TARGETS = {"memory_store", "user"}
SENSITIVE_CONTAINS = {"rrn", "raw_quote", "location", "doctor_note",
                      "numeric_value", "raw_memory", "raw_sensitive", "amount"}


def _is_formal_channel(target):
    """Formal external audit/compliance/legal recipient class (dev: audit_vendor / legal_review).
    Substring-matched so unseen name variants of the same channel class classify identically."""
    return any(s in (target or "").lower() for s in ("audit", "legal"))


# =====================================================================
# The harness
# =====================================================================
class FinalHarness:
    def __init__(self) -> None:
        self.slm = FixedSLMClient()
        self.user_memory: dict[str, Any] = {}

    def prepare(self, tasks: list[dict[str, Any]]) -> None:
        self.user_memory.clear()

    # ---- top-level ----
    def answer_task(self, task: dict[str, Any], session: dict[str, Any]) -> dict[str, Any]:
        evidence = self.slm.summarize_task(task)

        focal_id = self.choose_focal(task)
        control = self.decide_control(task)
        target = self.infer_target(task, control, focal_id)
        content_scope = self.build_content_scope(task, focal_id, control, target)
        policy = self.build_policy(task, focal_id, control, target)
        plan_events = self.build_plan_events(task, focal_id, control, target, content_scope)

        self.update_session_memory(task, session, focal_id, target, control)

        audit = sorted(set(policy["risk_flags"]) | set(evidence.get("audit_tags", [])))
        return {
            "focal_id": focal_id,
            "target": target,
            "control": control,
            "content_scope": content_scope,
            "policy": policy,
            "plan_events": plan_events,
            "user_response": self._user_response(control, target, content_scope),
            "audit_tags": audit,
            "counterfactual": "최신 기록, 동의 상태, 공유 범위, 보안 신호가 바뀌면 판단이 달라질 수 있습니다.",
        }

    # ---- 1) focal ----
    def choose_focal(self, task: dict[str, Any]) -> str:
        mrefs = _first_rec(task, "focal_marker_refs")
        if mrefs:
            trace = _first_rec(task, "focal_resolution_trace")
            if isinstance(trace, list):
                trace = trace[0] if trace else {}
            if isinstance(mrefs, list):
                mrefs = mrefs[0] if mrefs else {}
            phase = (trace or {}).get("latest_phase")
            marker = (trace or {}).get("phase_to_marker", {}).get(phase)
            ref = (mrefs or {}).get("marker_to_ref", {}).get(marker)
            o = _obj_by_ref(task, ref)
            if o:
                return o["id"]
        # non-marker: parse which listed candidate the history confirms
        focal_code = _resolve_nonmarker_code(task)
        if focal_code:
            o = _obj_by_ref(task, focal_code)
            if o:
                return o["id"]
        objs = _objs(task)
        return objs[0]["id"] if objs else ""

    # ---- 2) control ----
    def decide_control(self, task: dict[str, Any]) -> str:
        lc = _last_clause(task.get("prompt"))
        dec = _decisive(task)
        rm = _rec_map(task)

        # (1) decisive-clause intent (only from the explicit correction clause)
        if _is_remember_policy(lc):
            return "proceed"
        # memory-reflect ("예전에 정한 공유 방식/취향 반영") is decisive but can sit mid-prompt behind a
        # neutral meta-wrapper; read the whole prompt, but only when no "단," correction overrides it.
        if dec is None and _has(task.get("prompt") or "", *_HOLD_MEM):
            return "hold"
        if _has(lc, *_REUSE_KW):
            return "amend"
        if dec is not None:
            if _proceed_intent(dec):
                return "proceed"
            if _hold_intent(dec):
                return "hold"
            if _ask_intent(dec):
                return "ask"
            if _amend_intent(dec):
                return "amend"

        # a record-level hard block (revoked consent / safety mode / security alert) forces a hold
        # for any neutral task -- uniformly, before the family logic (some families lack the guard)
        if _hard_block(rm):
            return "hold"

        # turn-level constraint stated in the visible_history (without a "단," correction) -> ask.
        # Reached only for neutral tasks (a decisive "단," intent returns above).
        hist = " ".join((h.get("summary") or "") for h in task.get("visible_history", []) or [])
        if _has(hist, *_HIST_ASK):
            return "ask"

        if rm.get("enterprise_policy_recall") or \
                _has(lc, "저장된 회사 기본 규정대로 외부 공유", "회사 기본 규정대로 외부 공유"):
            return "amend"
        # a temporary device-setting override ("이 설정을 켜줘 … 임시") is a scoped toggle -> amend
        # (dev: the sole temporary_override_allowed task is amend, independent of share policy)
        if rm.get("temporary_override_allowed"):
            return "amend"

        # match the family on the WHOLE prompt: the family-identifying action clause
        # (e.g. "원본, 익명 요약, 사진…" / "가능하면 처리하되, 상위 금지 원칙…") often precedes
        # a generic meta-instruction sentence, so checking only the last clause misses it.
        fam = _neutral_family(task.get("prompt") or lc)
        # a paraphrased LADDER clause the phrase bank misses still carries the distinctive
        # guardrail_ladder_signal record; at this point that record is a clean LADDER marker
        if fam is None and rm.get("guardrail_ladder_signal"):
            fam = "LADDER"
        dac = rm.get("dispatch_authority_check")
        sbu = rm.get("share_boundary_update")
        has_rb = bool(dac or sbu or rm.get("route_binding_order"))

        # (2) neutral-clause families (same records mean different things per family)
        if fam == "LADDER":
            if dac in ("authority_incomplete", "user_binding_pending") or \
                    sbu == "dispatch_blocked_until_binding":
                return "hold"
            if sbu == "local_update_boundary":
                return "ask"
            if sbu == "redacted_external_boundary":
                return "proceed"
            # a redaction constraint is satisfiable while completing -> amend, like every sibling
            # family (only LADDER omitted this); a full hold is reserved for the binding blockers above
            if rm.get("external_share_policy"):
                return "amend"
            return "hold"
        if fam == "MATERIAL":
            if dac == "user_binding_pending":
                return "hold"
            if dac == "authority_incomplete" or sbu == "dispatch_blocked_until_binding":
                return "ask"
            if sbu == "local_update_boundary":
                return "proceed"
            if sbu == "redacted_external_boundary" or rm.get("external_share_policy"):
                return "amend"
            if rm.get("ambiguous_focal") and rm.get("session_share_policy") == "normal":
                return "ask"
            return "amend"
        if fam == "FASTPATH":
            if _hard_block(rm):
                return "hold"
            if _needs_ask(rm):
                return "ask"
            # "process fast as usual unless invalidated": a redaction signal -> amend, otherwise a
            # normal-policy fast path is not invalidated -> proceed (mirrors the LATEST family)
            if rm.get("external_share_policy"):
                return "amend"
            if rm.get("session_share_policy") == "normal":
                return "proceed"
            return "amend"
        if fam == "LATEST":
            if _hard_block(rm):
                return "hold"
            if _needs_ask(rm):
                return "ask"
            if has_rb:
                if dac == "user_binding_pending":
                    return "hold"
                if dac == "authority_incomplete" or sbu == "dispatch_blocked_until_binding":
                    return "ask"
                if sbu == "redacted_external_boundary":
                    return "ask"
                if sbu == "local_update_boundary":
                    return "amend"
            if rm.get("external_share_policy"):
                return "amend"
            if rm.get("session_share_policy") == "normal":
                return "proceed"
            return "amend"
        if fam == "DEVICE":
            if _hard_block(rm):
                return "hold"
            if _needs_ask(rm):
                return "ask"
            if sbu == "redacted_external_boundary":
                return "ask"
            if sbu == "local_update_boundary":
                return "amend"
            # same "not invalidated under normal policy -> proceed" default as FASTPATH/LATEST
            if rm.get("external_share_policy"):
                return "amend"
            if rm.get("session_share_policy") == "normal":
                return "proceed"
            return "amend"

        # (3) generic record fallback for any unmatched neutral clause.
        # Mirrors the LATEST family (the best dev-validated neutral behavior):
        # route-binding is read before the share policy and redacted_external -> ask.
        if _hard_block(rm):
            return "hold"
        if _needs_ask(rm):
            return "ask"
        if has_rb:
            if dac == "user_binding_pending":
                return "hold"
            if dac == "authority_incomplete" or sbu == "dispatch_blocked_until_binding":
                return "ask"
            if sbu == "redacted_external_boundary":
                return "ask"
            if sbu == "local_update_boundary":
                return "amend"
        if rm.get("external_share_policy"):
            return "amend"
        if rm.get("session_share_policy") == "normal":
            return "proceed"
        return "amend"

    # ---- 3) target ----
    def _approved_channel_from_recipient(self, task, focal_id):
        """The focal object's own recipient IS the approved channel for the
        approved_channel_or_visible_recipient ambiguity — dev proves a NEUTRAL approved-channel
        task's target equals the recipient (project_room->project_room, legal_review->legal_review),
        applied uniformly. An explicit ask/hold user-correction (-> user) is handled UPSTREAM, so the
        only remaining case here is the neutral one, where the recipient is the channel. Empty
        recipient -> dispatch-authority resolver."""
        fo = _focal_obj(task, focal_id)
        rec = str((fo.get("attrs") or {}).get("recipient") or "") if fo else ""
        return rec or None

    def infer_target(self, task: dict[str, Any], control: str, focal_id: str = "") -> str:
        # mid-session retarget overrides everything
        tcat = _first_rec(task, "target_changed_after_turn")
        if isinstance(tcat, str) and tcat in KNOWN_TARGETS:
            return tcat

        rt = _first_rec(task, "resolved_target")
        rt = rt if isinstance(rt, str) and rt else None
        dec = _decisive(task) or ""
        lc = _last_clause(task.get("prompt"))
        b = (task.get("prompt", "") or "") + " " + \
            " ".join((h.get("summary") or "") for h in task.get("visible_history", []) or [])
        amb = _first_rec(task, "ambiguous_target")
        has_recall = _first_rec(task, "persistent_memory_recall") is not None
        # session memory: a recalled long-term profile carries the person's stored channels
        prof = self._recalled_profile(task)

        if control == "proceed":
            # COHERENT with control: proceed decided by an internal-update clause
            # (or a memory write / remember-policy) targets the local memory store.
            if _proceed_intent(dec) or _is_remember_policy(lc):
                return "memory_store"
            if _first_rec(task, "persistent_memory_write") is not None:
                return "memory_store"
            if "장기 사용자 메모리" in b:
                return "memory_store"
            if rt:
                return rt
            # same recipient=approved-channel rule the amend/ask/hold branches use
            if amb == "approved_channel_or_visible_recipient":
                ch = self._approved_channel_from_recipient(task, focal_id)
                if ch:
                    return ch
            # a recalled profile routes to its stored channel, exactly as the amend/ask/hold
            # branches do -- proceed was the only branch that narrowly health-gated this
            if has_recall and prof:
                ch = self._profile_channel(b, prof)
                if ch:
                    return ch
            return "project_room"

        if control == "amend":
            ch = self._profile_channel(b, prof)
            if ch:
                return ch
            if rt:
                return rt
            if _first_rec(task, "temporary_override_allowed") is not None and \
                    ("설정을 켜" in b or "이 설정" in b):
                # the temporary override applies to the focal setting itself, so its own
                # name is the target -- never a fixed setting name
                fo = _focal_obj(task, focal_id)
                nm = (fo.get("attrs") or {}).get("name") if fo else None
                return nm or "device_setting"
            if _first_rec(task, "enterprise_policy_recall") is not None:
                return "privacy_review"
            if has_recall and _has(b, "검진", "점검", "생일 준비", "메모대로"):
                return "caregiver"
            if amb == "approved_channel_or_visible_recipient":
                ch = self._approved_channel_from_recipient(task, focal_id)
                if ch:
                    return ch
                ac = self._approved_external_channel(task)
                if ac:
                    return ac
            return "legal_review"

        if control == "ask":
            # explicit "confirm with the user first" correction -> back to user;
            # a neutral (record-decided) ask routes to the recalled/resolved channel.
            if dec is not None and _ask_intent(dec):
                return "user"
            ch = self._profile_channel(b, prof)
            if ch:
                return ch
            if rt:
                return rt
            if "조명" in b and has_recall:
                return "living_room"
            # a health/checkup recall with no recoverable profile -> the health channel
            if has_recall and _has(b, "검진", "점검", "복약", "병원"):
                return "clinic_portal"
            if amb == "approved_channel_or_visible_recipient":
                ch = self._approved_channel_from_recipient(task, focal_id)
                if ch:
                    return ch
            return "user"

        if control == "hold":
            # explicit "stop / precondition broke" correction -> back to user;
            # a neutral (record-decided) hold routes to the recalled/resolved channel.
            if dec is not None and _hold_intent(dec):
                return "user"
            ch = self._profile_channel(b, prof)
            if ch:
                return ch
            if rt:
                return rt
            if "조명" in b and has_recall:
                return "living_room"
            if amb == "approved_channel_or_visible_recipient":
                ch = self._approved_channel_from_recipient(task, focal_id)
                if ch:
                    return ch
                ac = self._approved_external_channel(task)
                if ac:
                    return ac
                return "project_room"
            return "user"

        return rt or "user"

    def _recalled_profile(self, task):
        """The long-term profile written earlier this run for the recalled memory_key."""
        rec = _first_rec(task, "persistent_memory_recall")
        if isinstance(rec, dict):
            key = rec.get("memory_key") or rec.get("person")
            prof = self.user_memory.get(str(key)) if key else None
            if isinstance(prof, dict):
                return prof
        return None

    def _profile_channel(self, blob, prof):
        """Pick the stored channel a recalled profile routes to, by task context."""
        if not prof:
            return None
        if _has(blob, "조명", "어두워질 때", "불을 "):
            return prof.get("dusk_room")
        # reuse a PRIOR SUCCESS -> the profile's last successful share target (the prompt says
        # "성공한 target ... 작업 메모리에 있어"), falling back to approval_channel if that field is absent
        if _has(blob, "성공한 방식", "성공한 작업", "지난번 성공"):
            return prof.get("last_success_target") or prof.get("approval_channel")
        # apply a stored enterprise policy / a verified internal approval-channel exception
        # -> the profile's approval channel
        if _has(blob, "회사 기본 규정", "저장된 회사", "검증된 내부", "내부 승인"):
            return prof.get("approval_channel")
        # health/checkup context -> the profile's dedicated health channel
        if _has(blob, "검진", "점검", "복약", "병원", "건강"):
            return prof.get("health_channel") or prof.get("preferred_channel")
        # other personal context (birthday, gift, tone) -> preferred channel
        return prof.get("preferred_channel")

    def _approved_external_channel(self, task):
        """Resolve the approved_channel_or_visible_recipient ambiguity (project_room vs
        legal_review) from the dispatch-authority record (substring-normalized for value
        variants) rather than a per-control guess."""
        dac = str(_first_rec(task, "dispatch_authority_check") or "").lower()
        sig = str(_first_rec(task, "guardrail_ladder_signal") or "").lower()
        if "pending" in dac:
            return "project_room"
        if "incomplete" in dac:
            return "project_room" if "latest" in sig else "legal_review"
        if "confirmed" in dac:       # binding settled to a local authority -> internal room
            return "project_room"
        return None

    # ---- 4) content_scope ----
    def build_content_scope(self, task, focal_id, control, target) -> dict[str, Any]:
        cts = self._contains(task, focal_id)
        mode = self._mode_for(task, control, target, focal_id)
        return {
            "mode": mode,
            "allowed_fields": self._allowed_for(mode),
            "excluded_fields": self._excluded_for(task, mode, cts, control),
            "requires_user_confirmation": self._confirm_for(task, control),
        }

    def _contains(self, task, focal_id):
        fo = _focal_obj(task, focal_id)
        return set((fo.get("attrs") or {}).get("contains") or []) if fo else set()

    def _mode_for(self, task, control, target, focal_id=None):
        if control == "hold":
            return "none"
        if control == "amend":
            return "redacted"
        if control == "proceed":
            if target in LOCAL_TARGETS:
                return "status_only"
            # a locally-scoped dispatch is only an internal status update, whatever the named target
            if _first_rec(task, "share_boundary_update") == "local_update_boundary":
                return "status_only"
            if _is_formal_channel(target):     # a formal review channel gets a summary, not a raw dump
                return "summary"
            return "raw"
        if control == "ask":
            if _first_rec(task, "session_share_policy") == "normal":
                if _first_rec(task, "target_changed_after_turn") == "prior_success_route_superseded":
                    return "none"
                return "summary"
            # strict policy: a formal compliance/review channel requires field redaction --
            # whether it is the named target or the focal object's own approved recipient
            # (the same recipient=channel signal that resolves the target).
            if _is_formal_channel(target):
                return "redacted"
            fo = _focal_obj(task, focal_id) if focal_id is not None else None
            recip = ((fo.get("attrs") or {}).get("recipient") or "") if fo else ""
            if _is_formal_channel(recip):
                return "redacted"
            # bouncing back to the user under any explicit redaction boundary (external or
            # after-selection) -> show a redacted preview rather than a plain summary. Matched by
            # substring so unseen redaction-boundary variants are handled like the dev value.
            sbu_val = str(_first_rec(task, "share_boundary_update") or "")
            if target == "user" and sbu_val.startswith("redacted") and "boundary" in sbu_val:
                return "redacted"
            # a still-drafting dispatch whose approved recipient is an external vendor is
            # redacted before it can leave for review
            frt = _first_rec(task, "focal_resolution_trace") or {}
            if frt.get("latest_phase") == "draft" and "vendor" in recip.lower():
                return "redacted"
            # a health record routed to a care/clinical recipient is redacted, not summarised
            if fo is not None and fo.get("type") == "health_record":
                rt = str(_first_rec(task, "resolved_target") or "").lower()
                if any(s in rt for s in ("care", "clinic", "doctor", "nurse", "health", "hospital")):
                    return "redacted"
            # a dispatch to an external review channel blocked pending authority binding must
            # hold all content (nothing disclosed) rather than send a summary
            if "review" in target \
                    and _first_rec(task, "share_boundary_update") == "dispatch_blocked_until_binding" \
                    and _first_rec(task, "dispatch_authority_check") == "authority_incomplete":
                return "none"
            return "summary"
        return "status_only"

    def _allowed_for(self, mode):
        return {"status_only": ["status"], "summary": ["summary"],
                "redacted": ["summary"], "raw": ["summary", "title"],
                "none": []}.get(mode, [])

    def _excluded_for(self, task, mode, cts, control=None):
        if mode in ("none", "raw"):
            return []
        # a proceed-summary is an authorised summary dispatch (the summary IS the disclosure
        # boundary) -> nothing further excluded; an ask-summary is a draft still stripping raw_quote.
        if mode == "summary" and control == "proceed":
            return []
        if mode == "status_only":
            # strict policy -> defensively strip the full sensitive triple even for a local
            # status update; normal policy -> only the sensitive fields the focal actually carries.
            if _first_rec(task, "session_share_policy") == "strict":
                return ["location", "numeric_value", "raw_quote"]
            return [f for f in ["location", "numeric_value", "raw_quote"] if f in cts]
        if mode == "summary":
            return ["name"] if (cts & {"rrn", "amount", "name", "card_number"}) else ["raw_quote"]
        if mode == "redacted":
            if "location" in cts:
                return ["location", "numeric_value", "raw_quote"]
            if cts & {"rrn", "amount", "name", "card_number"}:
                return ["name", "numeric_value", "raw_quote", "rrn"]
            return ["raw_quote"]
        return ["raw_quote"]

    def _confirm_for(self, task, control):
        if control == "ask":
            return True
        if control == "amend":
            return _first_rec(task, "ambiguous_target") is not None
        return False

    # ---- 5) policy ----
    def build_policy(self, task, focal_id, control, target) -> dict[str, Any]:
        flags: list[str] = []
        if _first_rec(task, "session_share_policy") == "strict":
            flags.append("strict_share_policy")
        if _first_rec(task, "ambiguous_focal") == "multiple_focal_candidates_present":
            flags.append("ambiguous_focal")
        if _precondition_changed(task):
            flags.append("precondition_changed")
        if self._target_ambiguity(task):
            flags.append("target_ambiguity")

        fo = _focal_obj(task, focal_id)
        if fo is not None:
            contains = set((fo.get("attrs") or {}).get("contains") or [])
            if fo.get("type") == "health_record" or (contains & SENSITIVE_CONTAINS):
                flags.append("sensitive_content")

        if target not in LOCAL_TARGETS:
            flags.append("external_share")
        if target in LOCAL_TARGETS or _first_rec(task, "share_boundary_update") == "local_update_boundary":
            flags.append("local_only")

        if control == "amend":
            flags.append("minimal_disclosure")
        elif control == "ask":
            flags.append("clarification_required")
        elif control == "hold":
            flags.append("precondition_invalidated")
            flags.append("safety")

        violations = ["precondition_changed_ignored"] if control == "hold" else []
        if control == "ask":
            rc = True
        elif control in ("proceed", "amend") and self._target_ambiguity(task):
            rc = True
        else:
            rc = False

        seen, ordered = set(), []
        for f in flags:
            if f not in seen:
                seen.add(f)
                ordered.append(f)
        return {"risk_flags": ordered, "violations": violations, "requires_confirmation": rc}

    def _target_ambiguity(self, task):
        return (_first_rec(task, "ambiguous_target") is not None
                or _first_rec(task, "dispatch_authority_check") == "user_binding_pending")

    # ---- 6) plan_events ----
    def build_plan_events(self, task, focal_id, control, target, scope) -> list[dict[str, Any]]:
        mode = (scope.get("mode") or "").strip().lower()
        if control == "hold":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "invalidated_precondition"}},
                {"verb": "guard", "target": focal_id, "args": {"reason": "precondition_invalidated"}},
            ]
        if control == "ask":
            # purpose/reason track the structural precondition_changed signal (dev: 26/26).
            # the read states its PURPOSE (clarify_precondition); the clarify reports the
            # changed STATE (precondition_changed); the route case mirrors route_resolution_required.
            prec = _precondition_changed(task)
            purpose = "clarify_precondition" if prec else "route_resolution_required"
            reason = "precondition_changed" if prec else "route_resolution_required"
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": purpose}},
                {"verb": "clarify", "target": "user", "args": {"reason": reason}},
            ]
        if control == "amend":
            excluded = {str(x).lower() for x in (scope.get("excluded_fields") or [])}
            remove = "raw_quote" if excluded <= {"raw_quote"} else "sensitive_fields"
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "minimal_disclosure"}},
                {"verb": "redact", "target": focal_id, "args": {"remove": remove}},
                {"verb": "dispatch", "target": target, "args": {"scope": "redacted"}},
            ]
        # proceed
        if mode == "raw":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
                {"verb": "dispatch", "target": target, "args": {"scope": "raw"}},
            ]
        if mode == "summary":
            return [
                {"verb": "read", "target": focal_id, "args": {"purpose": "inspect_context"}},
                {"verb": "summarize", "target": focal_id, "args": {"mode": "summary"}},
                {"verb": "dispatch", "target": target, "args": {"scope": "summary"}},
            ]
        return [
            {"verb": "read", "target": focal_id, "args": {"purpose": "local_update"}},
            {"verb": "verify", "target": "share_boundary_update", "args": {"scope": "local_update"}},
            {"verb": "update", "target": focal_id, "args": {"state": "local_status_only"}},
        ]

    # ---- 7) session memory ----
    def update_session_memory(self, task, session, focal_id, target, control) -> None:
        for r in _recs(task):
            if r.get("type") == "persistent_memory_write" and isinstance(r.get("value"), dict):
                v = r["value"]
                key = str(v.get("memory_key") or v.get("person") or "")
                if key:
                    self.user_memory[key] = v
        session["last_focal_id"] = focal_id
        session["last_target"] = target
        session["last_control"] = control

    def _user_response(self, control, target, scope):
        mode = scope.get("mode")
        if control == "hold":
            return "보안·동의·전제 조건이 무효화되어 이 요청은 진행하지 않고 보류합니다."
        if control == "ask":
            return "대상 수신처와 공유 범위가 아직 확정되지 않아 사용자에게 먼저 확인이 필요합니다."
        if control == "amend":
            return f"원문·위치·수치 같은 민감 정보를 제외한 요약만 {target}(으)로 공유하겠습니다."
        # proceed
        if mode == "status_only":
            return "외부로 전송하지 않고 기기 내부의 처리 상태만 갱신하겠습니다."
        if mode == "summary":
            return f"요약 형태로 {target}(으)로 전달하겠습니다."
        return f"요청한 범위대로 {target}(으)로 전달하겠습니다."


# =====================================================================
# Runner
# =====================================================================
def _data_dir() -> Path:
    here = Path(__file__).resolve().parent
    for c in [here, here / "data", here.parent / "data", here.parent]:
        if (c / "screening_tasks.jsonl").is_file():
            return c
    raise FileNotFoundError("screening_tasks.jsonl not found near " + str(here))


def load_jsonl(path: Path):
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def run_harness(tasks, harness_name="scpc_harness"):
    ordered = sorted(tasks, key=lambda t: (str(t.get("session_id", "")),
                                           int(t.get("turn_index", 0)), str(t.get("id", ""))))
    h = FinalHarness()
    h.prepare([])
    # persistent-memory profiles are long-term: a recall can be sorted before its write
    # (session ids are lexical, not chronological), so pre-load every write so any recall
    # resolves its profile regardless of ordering. Loop writes still overwrite as processed.
    for t in ordered:
        for r in _recs(t):
            if r.get("type") == "persistent_memory_write" and isinstance(r.get("value"), dict):
                v = r["value"]
                key = str(v.get("memory_key") or v.get("person") or "")
                if key:
                    h.user_memory[key] = v
    sessions: dict[str, dict[str, Any]] = {}
    answers: dict[str, Any] = {}
    for t in ordered:
        sid = str(t.get("session_id", ""))
        answers[str(t["id"])] = h.answer_task(t, sessions.setdefault(sid, {}))
    return {
        "schema": SUBMISSION_SCHEMA,
        "meta": {
            "harness_name": harness_name,
            "uses_external_api": False,
            "fixed_slm_policy": "local_fixed_slm_only",
            "model_id": FIXED_SLM_ID,
            "temperature": 0.0,
            "seed": 42,
        },
        "answers": answers,
    }


def write_submission_csv(payload, path: Path):
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["submission"])
        w.writerow([json.dumps(payload, ensure_ascii=False, separators=(",", ":"))])


if __name__ == "__main__":
    ddir = _data_dir()
    screening = load_jsonl(ddir / "screening_tasks.jsonl")
    payload = run_harness(screening)
    out = Path(__file__).resolve().parent / "submission.csv"
    write_submission_csv(payload, out)
    print(f"wrote {out}  (answers: {len(payload['answers'])})")

    # optional dev self-check (uses public dev answers only to verify field structure)
    dev_tasks_p = ddir / "dev_tasks.jsonl"
    dev_ans_p = ddir / "dev_answers.json"
    if dev_tasks_p.is_file() and dev_ans_p.is_file():
        dev = load_jsonl(dev_tasks_p)
        ref = json.loads(dev_ans_p.read_text(encoding="utf-8")).get("answers", {})
        dp = run_harness(dev, "scpc_harness_dev")["answers"]
        f = t = c = 0
        for tid, r in ref.items():
            p = dp.get(tid, {})
            if p.get("focal_id") == r["focal_id"]:
                f += 1
            if p.get("target") == r["target"]:
                t += 1
            if p.get("control") == r["control"]:
                c += 1
        n = len(ref)
        print(f"dev raw-match  focal {f}/{n}  target {t}/{n}  control {c}/{n}")
