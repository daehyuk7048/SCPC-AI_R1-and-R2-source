package com.sdh.chebi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.ProbeInputStep

/** 약지기 UI 여정이 emit하는 것과 동일한 합성 이벤트 열을 JVM에서 재현 —
 *  도메인 인과(승인→도착 귀속→반영, 기한 정정)의 회귀를 core 수준에서 고정한다. */
class UiFlowReplayTest {

    private var seq = 0

    private fun step(core: ChebiCore, op: String, roles: Map<String, String>, time: String): JSONObject {
        seq += 1
        val rolesJson = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }
        return core.execute(
            ProbeInputStep(seq, "UI-%04d".format(seq), op, "SESSION-UI-1", time, "EV-UI-%04d".format(seq), rolesJson, "", ""),
        )
    }

    @Test
    fun `med journey - approval registers refill and arrival attaches to the approved med`() {
        seq = 0
        val core = ChebiCore(InMemoryStateStore(), "proc-ui")
        step(core, "RESET_AND_START", emptyMap(), "2026-08-01T08:00:00Z")
        step(core, "UPSERT_FACT", mapOf(
            "PRIMARY_GOAL" to "safe_med_session", "TARGET_ENTITY" to "med_bp",
            "CURRENT_AUTHORITY" to "bp_v1", "SCOPE_GRANTED" to "scope_bp",
            "STABLE_PRESCRIPTION" to "bp_morning_1tab"), "2026-08-01T08:01:00Z")
        // 승인 = 리필 대기 등록 (outcome-decl 경로)
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_bp", "DELAYED_OUTCOME" to "refill_med_bp"), "2026-08-01T08:02:00Z")
        // 도착 전 target을 다른 entity로 옮겨도 (민감 메모 학습·삭제 등) 귀속이 오염되지 않아야 한다
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "private_note", "ONE_OFF_NOTE" to "memo"), "2026-08-01T08:03:00Z")
        step(core, "DELETE_FACT", mapOf("TARGET_ENTITY" to "private_note"), "2026-08-01T08:04:00Z")
        val t = step(core, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "elapse"), "2026-08-04T08:00:00Z")
        assertTrue(t.getString("state_before_sha256") != t.getString("state_after_sha256"))
        // 도착 기억은 '등록 시점의 entity(med_bp)'에 귀속된다 — 도착 시점 target(private_note) 아님
        val mems = core.snapshotState().getJSONObject("memories")
        val arrivalIds = mems.keys().asSequence().filter { it.contains("arrival_") }.toList()
        assertTrue("리필 도착 기억이 존재해야 한다", arrivalIds.isNotEmpty())
        assertTrue("도착은 승인한 약(med_bp)에 귀속", arrivalIds.all { it.startsWith("mem-med_bp-") })
        assertTrue("리필 outcome id가 도착 기억에 식별 가능", arrivalIds.any { it.contains("refill_med_bp") })
    }

    @Test
    fun `term prescription correction records the new value and deadline`() {
        seq = 0
        val core = ChebiCore(InMemoryStateStore(), "proc-ui")
        step(core, "RESET_AND_START", emptyMap(), "2026-08-01T08:00:00Z")
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_abx", "CURRENT_AUTHORITY" to "abx_v1", "SCOPE_GRANTED" to "scope_abx",
            "TERM_VALUE" to "abx_evening_1tab", "VALID_UNTIL" to "2026-08-08T08:00:00Z"), "2026-08-01T08:01:00Z")
        // 기한형 처방 정정: 새 값·새 기한이 기록되어야 한다 (조용한 유실 금지)
        val corr = step(core, "CORRECT_FACT", mapOf(
            "TARGET_ENTITY" to "med_abx", "CURRENT_AUTHORITY" to "abx_v2",
            "TERM_VALUE" to "abx_evening_half", "VALID_UNTIL" to "2026-08-05T08:00:00Z"), "2026-08-01T08:02:00Z")
        assertTrue(corr.getJSONArray("invalidated_state_ids").length() > 0)
        val d = step(core, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "med_abx"), "2026-08-02T08:00:00Z")
        assertEquals("ACT", d.getString("decision_state"))
        val mem = core.snapshotState().getJSONObject("memories").getJSONObject("mem-med_abx-TERM_VALUE")
        assertEquals("정정된 새 값", "abx_evening_half", mem.getString("value"))
        assertEquals("정정된 새 기한", "2026-08-05T08:00:00Z", mem.getString("valid_until"))
        assertEquals("scope 승계", "scope_abx", mem.getString("scope"))
        // 새 기한 경과 후 만료
        val d2 = step(core, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "med_abx"), "2026-08-06T08:00:00Z")
        assertFalse("새 기한 경과 후 재사용 없음",
            (0 until d2.getJSONArray("selected_context_ids").length())
                .any { d2.getJSONArray("selected_context_ids").getString(it).contains("TERM_VALUE") })
    }

    @Test
    fun `second refill cycle arrives as a distinct memory (no permanent loss)`() {
        seq = 0
        val core = ChebiCore(InMemoryStateStore(), "proc-ui")
        step(core, "RESET_AND_START", emptyMap(), "2026-08-01T08:00:00Z")
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_bp", "SCOPE_GRANTED" to "scope_bp",
            "STABLE_PRESCRIPTION" to "bp_morning_1tab"), "2026-08-01T08:01:00Z")
        // 1차 승인 → 도착
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_bp", "DELAYED_OUTCOME" to "refill_med_bp"), "2026-08-01T08:02:00Z")
        step(core, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "e1"), "2026-08-03T08:00:00Z")
        // 2차 승인 → 도착 — 사이클마다 도착 기억이 구분되어야 반복 리필이 유실되지 않는다
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_bp", "DELAYED_OUTCOME" to "refill_med_bp"), "2026-08-03T08:01:00Z")
        step(core, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "e2"), "2026-08-06T08:00:00Z")
        val mems = core.snapshotState().getJSONObject("memories")
        val arrivals = mems.keys().asSequence()
            .filter { it.contains("arrival_outcome-decl-refill_med_bp") }.toList()
        assertEquals("두 사이클 = 두 도착 기억", 2, arrivals.size)
    }

    @Test
    fun `stopping a med closes its proposed refill action`() {
        seq = 0
        val core = ChebiCore(InMemoryStateStore(), "proc-ui")
        step(core, "RESET_AND_START", emptyMap(), "2026-08-01T08:00:00Z")
        step(core, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "med_abx", "SCOPE_GRANTED" to "scope_abx",
            "STABLE_PRESCRIPTION" to "abx_evening_1tab"), "2026-08-01T08:01:00Z")
        step(core, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "med_abx"), "2026-08-01T08:02:00Z")
        step(core, "REVOKE_SCOPE", mapOf("REVOKED_SCOPE" to "scope_abx"), "2026-08-01T08:03:00Z")
        // 취소된 대기의 행동은 PROPOSED로 남지 않는다 → 이후 관찰이 영구 PENDING이 되지 않음
        val export = step(core, "EXPORT_AND_END", emptyMap(), "2026-08-01T08:04:00Z")
        assertEquals("NO_DECISION", export.getString("decision_state"))
    }
}
