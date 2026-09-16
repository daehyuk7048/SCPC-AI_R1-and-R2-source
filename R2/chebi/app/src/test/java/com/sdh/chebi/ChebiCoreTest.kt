package com.sdh.chebi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.ProbeInputStep

/** VG-Ledger 의미론 고정 테스트 — CORE-1/2/3/4/5/6의 핵심 행동을 JVM에서 검증. */
class ChebiCoreTest {

    private fun step(
        index: Int,
        op: String,
        roles: Map<String, String> = emptyMap(),
        session: String = "S-A",
        time: String = "2026-01-01T00:0$index:00Z",
        event: String = "EV-$index",
    ): ProbeInputStep {
        val rolesJson = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }
        return ProbeInputStep(index, "STEP-$index", op, session, time, event, rolesJson, "", "")
    }

    private fun newCore(store: ChebiStateStore = InMemoryStateStore(), marker: String = "proc-1") =
        ChebiCore(store, marker)

    private fun learn(core: ChebiCore, index: Int = 2) = core.execute(
        step(
            index, "UPSERT_FACT",
            mapOf(
                "PRIMARY_GOAL" to "GOAL_A", "TARGET_ENTITY" to "ENTITY_A",
                "STABLE_VALUE" to "BLUE", "ONE_OFF_VALUE" to "ONCE_MORNING",
                "CURRENT_AUTHORITY" to "V1", "PRESERVED_SCOPE" to "KEEP",
            ),
        ),
    )

    @Test
    fun `CORE-3 one-off is consumed after first decision and never reused`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        val d1 = core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("ACT", d1.getString("decision_state"))
        val used1 = d1.getJSONArray("selected_context_ids").toList()
        assertTrue("one-off must be usable on first decision", used1.any { "ONE_OFF" in it.toString().uppercase() })

        val d2 = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val used2 = d2.getJSONArray("selected_context_ids").toList()
        assertFalse("consumed one-off must not reappear", used2.any { "ONE_OFF" in it.toString().uppercase() })
    }

    @Test
    fun `CORE-1 distractor memories are excluded from selected context`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "ADVANCE_SESSION", mapOf("DISTRACTOR_ENTITY" to "ENTITY_B", "TARGET_ENTITY" to "ENTITY_A"), session = "S-B"),
        )
        // distractor entity의 기억이 있어도 선택되지 않아야 한다
        core.execute(
            step(4, "UPSERT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "STABLE_VALUE" to "BLUE2"), session = "S-B"),
        )
        val d = core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A"), session = "S-B"))
        assertEquals("ACT", d.getString("decision_state"))
        d.getJSONArray("selected_context_ids").toList().forEach {
            assertFalse("distractor must never be selected", it.toString().contains("ENTITY_B"))
        }
    }

    @Test
    fun `CORE-2 correction invalidates stale authority memories`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        val corr = core.execute(
            step(
                5, "CORRECT_FACT",
                mapOf("TARGET_ENTITY" to "ENTITY_A", "CURRENT_AUTHORITY" to "V2", "ONE_OFF_VALUE" to "GREEN"),
                session = "S-B",
            ),
        )
        assertTrue("old-authority memories must be invalidated", corr.getJSONArray("invalidated_state_ids").length() > 0)
        val d = core.execute(step(6, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A"), session = "S-B"))
        // 선택된 context는 V2 권위의 기억만이어야 한다 (구권위 무효)
        assertEquals("ACT", d.getString("decision_state"))
    }

    @Test
    fun `CORE-4 revoke splits invalidated and preserved precisely`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "UPSERT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "STABLE_VALUE" to "SWIM", "PRESERVED_SCOPE" to "REVOKE_ME")),
        )
        val rev = core.execute(
            step(4, "REVOKE_SCOPE", mapOf("REVOKED_SCOPE" to "REVOKE_ME", "PRESERVED_SCOPE" to "KEEP")),
        )
        assertTrue(rev.getJSONArray("invalidated_state_ids").length() > 0)
        assertTrue(rev.getJSONArray("preserved_state_ids").length() > 0)
        val inv = rev.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        val pre = rev.getJSONArray("preserved_state_ids").toList().map(Any::toString)
        assertTrue("no overlap between invalidated and preserved", inv.intersect(pre.toSet()).isEmpty())
    }

    @Test
    fun `CORE-4 offline decision degrades honestly to WAIT`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "SET_NETWORK", mapOf("NETWORK_STATE" to "OFFLINE")))
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("WAIT", d.getString("decision_state"))
        assertTrue("no action may be committed while offline", d.isNull("action"))
    }

    @Test
    fun `CORE-5 replay returns identical action and creates nothing new`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        val d = core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val original = d.getJSONObject("action")
        // 결과 도착 전(in-flight PROPOSED)의 재전달: 완료로 위장하지 않고 PENDING으로 echo.
        val replay = core.execute(step(4, "REPLAY_EVENT", mapOf("REPLAY_OF_EVENT_ID" to "EV-3")))
        assertEquals("PENDING", replay.getString("decision_state"))
        assertEquals("PROPOSED", replay.getJSONObject("action").getString("commit_state"))
        assertEquals(original.getString("action_id"), replay.getJSONObject("action").getString("action_id"))
        assertEquals(original.getString("idempotency_key"), replay.getJSONObject("action").getString("idempotency_key"))
        // 결과 도착(commit 경계) 후의 재전달: 그때 비로소 CONFIRMED_COMPLETE.
        core.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "elapse")))
        val replay2 = core.execute(step(6, "REPLAY_EVENT", mapOf("REPLAY_OF_EVENT_ID" to "EV-3")))
        assertEquals("CONFIRMED_COMPLETE", replay2.getString("decision_state"))
        assertEquals("COMMITTED", replay2.getJSONObject("action").getString("commit_state"))
        assertEquals(original.getString("action_id"), replay2.getJSONObject("action").getString("action_id"))
    }

    @Test
    fun `CORE-5 state survives process restart with epoch bump and identical content`() {
        val store = InMemoryStateStore()
        val core1 = newCore(store, "proc-1")
        core1.execute(step(1, "RESET_AND_START"))
        learn(core1)
        val beforeKill = core1.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))

        // 프로세스 재시작 시뮬레이션: 같은 저장소, 다른 process marker
        val core2 = newCore(store, "proc-2")
        val after = core2.execute(step(4, "PROCESS_KILL_RELAUNCH", session = "S-C"))
        assertEquals(beforeKill.getInt("process_epoch") + 1, after.getInt("process_epoch"))
        // KILL step 자체는 상태를 변형하지 않는다: 이전 after 해시 == 이번 before 해시
        assertEquals(beforeKill.getString("state_after_sha256"), after.getString("state_before_sha256"))
    }

    @Test
    fun `CORE-2 deleted memory never resurrects and tombstone holds no value`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        val del = core.execute(step(3, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertTrue(del.getJSONArray("tombstone_ids").length() > 0)
        // tombstone 문자열 어디에도 원문 값(BLUE/ONCE_MORNING)이 없어야 한다
        val tomb = del.getJSONArray("tombstone_ids").toString()
        assertFalse(tomb.contains("BLUE"))
        assertFalse(tomb.contains("ONCE_MORNING"))
        // 삭제 후 결정: 기억이 없으므로 ASK (유령 재사용 없음)
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("ASK", d.getString("decision_state"))
    }

    @Test
    fun `CORE-6 advance time moves pending outcome to ledger and changes state`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        val d = core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("ACT", d.getString("decision_state"))
        val adv = core.execute(step(4, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "ARRIVED"), time = "2026-01-08T00:00:00Z"))
        assertEquals("PENDING", adv.getString("decision_state"))
        assertNotEquals(
            "outcome arrival must change state",
            adv.getString("state_before_sha256"),
            adv.getString("state_after_sha256"),
        )
    }

    @Test
    fun `G3 action stays PROPOSED across kill and commits only on outcome arrival`() {
        val store = InMemoryStateStore()
        val core1 = newCore(store, "proc-1")
        core1.execute(step(1, "RESET_AND_START"))
        learn(core1)
        val d = core1.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("PROPOSED", d.getJSONObject("action").getString("commit_state"))

        // kill을 넘어도 완료로 위장되지 않는다
        val core2 = newCore(store, "proc-2")
        val replay = core2.execute(step(4, "REPLAY_EVENT", mapOf("REPLAY_OF_EVENT_ID" to "EV-3")))
        assertEquals("PROPOSED", replay.getJSONObject("action").getString("commit_state"))

        // 지연 결과 도착이 commit 경계
        core2.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "ARRIVED"), time = "2026-01-08T00:00:00Z"))
        val replay2 = core2.execute(step(6, "REPLAY_EVENT", mapOf("REPLAY_OF_EVENT_ID" to "EV-3")))
        assertEquals("COMMITTED", replay2.getJSONObject("action").getString("commit_state"))
    }

    @Test
    fun `CORE-2 inference derived from stable memory dies with its parent`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // 파생 INFERENCE가 생겼고, 부모(STABLE) 정정 시 descendant로 함께 무효화되어야 한다
        val corr = core.execute(
            step(4, "CORRECT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "CURRENT_AUTHORITY" to "V2", "ONE_OFF_VALUE" to "GREEN")),
        )
        val inv = corr.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        assertTrue("stable parent invalidated", inv.any { "STABLE" in it.uppercase() })
        assertTrue("derived inference must be invalidated with its parent", inv.any { it.startsWith("inf-") })
    }

    @Test
    fun `CORE-5 out-of-order stale authority is explicitly rejected without state change`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "CORRECT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "CURRENT_AUTHORITY" to "V2", "ONE_OFF_VALUE" to "GREEN")),
        )
        val before = core.execute(step(4, "ADVANCE_SESSION", session = "S-C"))
        val ooo = core.execute(
            step(5, "DELIVER_OUT_OF_ORDER", mapOf("OLDER_EVENT_ID" to "EV-2", "CURRENT_AUTHORITY" to "V1"), session = "S-C"),
        )
        assertEquals("PENDING", ooo.getString("decision_state"))
        assertEquals(0, ooo.getJSONArray("invalidated_state_ids").length())
        // 상태는 receipts 외 변형 없음: 다음 결정에서 V1 값이 부활하지 않는다
        val d = core.execute(step(6, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A"), session = "S-C"))
        d.getJSONArray("selected_context_ids").toList().forEach {
            val id = it.toString()
            assertFalse("stale value must not resurrect", id.contains("V1"))
        }
    }

    @Test
    fun `CORE-4 decision on known distractor entity abstains instead of acting`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "ADVANCE_SESSION", mapOf("TARGET_ENTITY" to "ENTITY_A", "DISTRACTOR_ENTITY" to "ENTITY_B"), session = "S-B"),
        )
        val r = core.execute(
            step(4, "REQUEST_DECISION", mapOf("PRIMARY_GOAL" to "G", "TARGET_ENTITY" to "ENTITY_B"), session = "S-B"),
        )
        assertEquals("ABSTAIN", r.getString("decision_state"))
        assertTrue(r.isNull("action"))
        assertEquals(0, r.getJSONArray("selected_context_ids").length())
    }

    @Test
    fun `CORE-6 outcome arrival changes the next decision's selected context`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val beforeArrival = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        core.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "supply_arrived")))
        val afterArrival = core.execute(step(6, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val before = beforeArrival.getJSONArray("selected_context_ids").toList().map(Any::toString)
        val after = afterArrival.getJSONArray("selected_context_ids").toList().map(Any::toString)
        assertFalse("도착 전에는 도착 기억이 없어야 함", before.any { "arrival" in it })
        assertTrue("도착한 결과가 다음 판단의 context를 실제로 바꾼다", after.any { "arrival" in it })
    }

    @Test
    fun `C3 comparison arms diverge exactly on the validity-gate variable`() {
        val report = MedComparisonLab.run(InMemoryStateStore(), InMemoryStateStore(), "proc-1")
        val full = report.getJSONObject("full_arm")
        val off = report.getJSONObject("claim_off_arm")
        // 만료 항생제 유령 재사용: full 0건(만료 게이트) / off 1건 이상
        assertEquals(0, full.getInt("s5_expired_ghost_reuse"))
        assertTrue(off.getInt("s5_expired_ghost_reuse") >= 1)
        // 정정 영향 특정: full 정밀(1.0) / off 과잉(1.0 미만)
        assertEquals(1.0, full.getDouble("s6_impact_precision"), 1e-9)
        assertTrue(off.getDouble("s6_impact_precision") < 1.0)
        // 두 arm 모두 episode 정상 완주 (일부러 약한 데모가 아님)
        assertTrue(full.getBoolean("episode_completed"))
        assertTrue(off.getBoolean("episode_completed"))
    }

    @Test
    fun `delete with value role removes only that memory and preserves the rest`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(
            step(2, "UPSERT_FACT", mapOf(
                "TARGET_ENTITY" to "ENTITY_A",
                "STABLE_VALUE" to "BLUE", "ONE_OFF_VALUE" to "SECRET_NOTE")),
        )
        val del = core.execute(
            step(3, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "EPHEMERAL_VALUE" to "SECRET_NOTE")),
        )
        val inv = del.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        val pres = del.getJSONArray("preserved_state_ids").toList().map(Any::toString)
        assertTrue("값 role의 기억만 삭제", inv.any { "ONE_OFF" in it })
        assertTrue("다른 유효 기억은 보존", pres.any { "STABLE" in it })
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertEquals("ACT", d.getString("decision_state"))
        assertFalse(d.toString().contains("SECRET_NOTE"))
    }

    @Test
    fun `abstain on distractor does not hijack the current target`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "ADVANCE_SESSION", mapOf("TARGET_ENTITY" to "ENTITY_A", "DISTRACTOR_ENTITY" to "ENTITY_B"), session = "S-B"),
        )
        core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_B"), session = "S-B"))
        // target role 없는 후속 결정: ABSTAIN이 target을 오염시키지 않았다면 기존 목표로 ACT.
        val after = core.execute(step(5, "REQUEST_DECISION", emptyMap(), session = "S-B"))
        assertEquals("ACT", after.getString("decision_state"))
        after.getJSONArray("selected_context_ids").toList().forEach {
            assertTrue("기존 목표 entity의 기억만 선택", "entity_a" in it.toString().lowercase())
        }
    }

    @Test
    fun `correct with two value classes records both corrected values`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(
            step(3, "CORRECT_FACT", mapOf(
                "TARGET_ENTITY" to "ENTITY_A", "CURRENT_AUTHORITY" to "V2",
                "STABLE_RULE" to "NEW_RULE", "ONE_OFF_PASS" to "NEW_PASS")),
        )
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val selected = d.getJSONArray("selected_context_ids").toList().map(Any::toString)
        assertTrue("STABLE 정정값 기록", selected.any { "STABLE_RULE" in it })
        assertTrue("ONE_OFF 정정값 기록", selected.any { "ONE_OFF_PASS" in it })
    }

    @Test
    fun `hashes are reproducible across processes from reset onward`() {
        fun runAll(marker: String): List<String> {
            val core = ChebiCore(InMemoryStateStore(), marker)
            val r1 = core.execute(step(1, "RESET_AND_START"))
            val r2 = learn(core)
            val r3 = core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
            return listOf(r1, r2, r3).flatMap {
                listOf(it.getString("state_before_sha256"), it.getString("state_after_sha256"))
            }
        }
        // process_marker(런타임 난수)는 해시 대상이 아니므로, 같은 입력이면
        // 다른 프로세스에서도 같은 해시 체인이 나온다 (결정론 입증).
        assertEquals(runAll("proc-A"), runAll("proc-B"))
    }

    @Test
    fun `pending reported only while an action awaits its outcome`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val duringWait = core.execute(step(4, "ADVANCE_SESSION", mapOf("TARGET_ENTITY" to "ENTITY_A"), session = "S-B"))
        assertEquals("PENDING", duringWait.getString("decision_state"))
        core.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "arrival")))
        // 도착으로 전부 COMMITTED — 더 이상 대기가 없으니 PENDING이라 말하지 않는다.
        val afterCommit = core.execute(step(6, "ADVANCE_SESSION", mapOf("TARGET_ENTITY" to "ENTITY_A"), session = "S-C"))
        assertEquals("NO_DECISION", afterCommit.getString("decision_state"))
    }

    @Test
    fun `outcome does not arrive before its requested time has passed`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // 결정(00:05)과 같은 시각의 ADVANCE_TIME: 아직 도래하지 않았으므로 도착 없음.
        val same = core.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "later"), event = "EV-5b"))
        assertEquals("PENDING", same.getString("decision_state"))
        assertFalse("미도래 결과는 도착 기억을 만들지 않는다", same.toString().contains("arrival_"))
        // 시간이 실제로 지나면 도착한다.
        val later = core.execute(step(7, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "later"), event = "EV-7"))
        assertTrue(later.getString("state_before_sha256") != later.getString("state_after_sha256"))
    }

    @Test
    fun `value-only correct publishes the replaced instance as invalidated`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        // 권위 변경 없는 값 정정: 같은 role 키의 기존 ACTIVE 인스턴스 교체는 무효화로 게시된다.
        val corr = core.execute(
            step(3, "CORRECT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "STABLE_VALUE" to "GREEN")),
        )
        val inv = corr.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        assertTrue("교체된 STABLE 인스턴스가 invalidated에 게시", inv.any { "STABLE_VALUE" in it })
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertTrue("정정된 새 값은 재사용 가능", d.toString().contains("STABLE_VALUE"))
    }

    @Test
    fun `out-of-order delivery to a deleted entity is rejected as tombstoned`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val ooo = core.execute(
            step(4, "DELIVER_OUT_OF_ORDER", mapOf("OLDER_EVENT_ID" to "EV-2", "STABLE_VALUE" to "BLUE")),
        )
        assertEquals("PENDING", ooo.getString("decision_state"))
        assertEquals(0, ooo.getJSONArray("invalidated_state_ids").length())
        val reconciled = core.snapshotState().getJSONArray("reconciled_events")
        assertTrue(
            "삭제 entity 대상 재전달은 tombstone 사유로 기각",
            (0 until reconciled.length()).any { reconciled.getString(it).startsWith("REJECTED_TOMBSTONED") },
        )
    }

    @Test
    fun `authority-neutral memory survives an authority correction`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core) // AUTHORITY V1 선언과 함께 학습된 값들
        // 권위 미선언 학습: 예보와 무관한 기억은 권위 중립으로 기록된다.
        core.execute(
            step(3, "UPSERT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "STABLE_NEUTRAL_NOTE" to "PLAIN")),
        )
        val corr = core.execute(
            step(4, "CORRECT_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "CURRENT_AUTHORITY" to "V2", "STABLE_VALUE" to "GREEN")),
        )
        val inv = corr.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        assertTrue("구권위(V1) 값은 무효화", inv.any { "STABLE_VALUE" in it })
        assertFalse("권위 중립 값은 정정의 영향 밖", inv.any { "NEUTRAL_NOTE" in it })
        val d = core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertTrue(
            "권위 중립 값은 정정 후에도 재사용",
            d.getJSONArray("selected_context_ids").toList().any { "NEUTRAL_NOTE" in it.toString() },
        )
    }

    @Test
    fun `correcting one med's authority does not invalidate another med`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_BP", "CURRENT_AUTHORITY" to "BP_V1",
            "STABLE_DOSE_NOTE" to "bp_morning_1")))
        core.execute(step(3, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_ABX", "CURRENT_AUTHORITY" to "ABX_V1",
            "STABLE_DOSE_NOTE" to "abx_evening_1")))
        // 항생제만 정정: 혈압약의 기억은 영향 밖이어야 한다 (약 단위 권위).
        val corr = core.execute(step(4, "CORRECT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_ABX", "CURRENT_AUTHORITY" to "ABX_V2",
            "STABLE_DOSE_NOTE" to "abx_evening_2")))
        val inv = corr.getJSONArray("invalidated_state_ids").toList().map(Any::toString)
        assertTrue(inv.any { "med_abx" in it.lowercase() })
        assertFalse("다른 약은 무효화되지 않는다", inv.any { "med_bp" in it.lowercase() })
        val d = core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_BP")))
        assertEquals("ACT", d.getString("decision_state"))
        assertTrue("혈압약 기억은 계속 재사용", d.toString().contains("MED_BP") || d.toString().contains("med_bp"))
    }

    @Test
    fun `term memory reuses within its window and expires after`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_ABX",
            "TERM_VALUE" to "abx_course", "VALID_UNTIL" to "2026-01-01T00:07:00Z")))
        // 기한 내: 세션마다 재사용 (소모되지 않음)
        val d1 = core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_ABX")))
        val d2 = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_ABX")))
        assertTrue(d1.toString().contains("TERM_VALUE"))
        assertTrue("기한 내에는 재등장한다 (소모형 아님)", d2.toString().contains("TERM_VALUE"))
        // 기한 경과: 만료되어 재사용되지 않음
        val d3 = core.execute(step(8, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_ABX")))
        assertFalse("기한 경과 후 만료", d3.getJSONArray("selected_context_ids").toList()
            .any { "TERM_VALUE" in it.toString() })
    }

    @Test
    fun `stopping a med cancels its undelivered pending outcomes`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_ABX", "SCOPE_GRANTED" to "SCOPE_ABX",
            "STABLE_DOSE_NOTE" to "abx_evening_1")))
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_ABX")))
        // 복용 중단: 그 약의 미도착 대기가 취소되어 이후 도착하지 않는다.
        core.execute(step(4, "REVOKE_SCOPE", mapOf("REVOKED_SCOPE" to "SCOPE_ABX")))
        val t = core.execute(step(6, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "later")))
        assertFalse("취소된 대기는 도착 기억을 만들지 않는다", t.toString().contains("arrival_"))
        val reconciled = core.snapshotState().getJSONArray("reconciled_events")
        assertTrue((0 until reconciled.length()).any {
            reconciled.getString(it).startsWith("CANCELLED_PENDING")
        })
    }

    @Test
    fun `re-granting a scope makes new learning valid again`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_BP", "SCOPE_GRANTED" to "SCOPE_NOTIFY",
            "STABLE_ALERT" to "notify_8am")))
        core.execute(step(3, "REVOKE_SCOPE", mapOf("REVOKED_SCOPE" to "SCOPE_NOTIFY")))
        // 재허용 + 재학습: 새 기억은 다시 유효 (철회 때 무효화된 항목은 그대로).
        core.execute(step(4, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_BP", "SCOPE_GRANTED" to "SCOPE_NOTIFY",
            "STABLE_ALERT_NEW" to "notify_9am")))
        val d = core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_BP")))
        assertEquals("ACT", d.getString("decision_state"))
        assertTrue("재허용 후 새 학습은 재사용된다",
            d.getJSONArray("selected_context_ids").toList().any { "ALERT_NEW" in it.toString() })
    }

    @Test
    fun `mid-run reset reports tombstones consistent with the reset state`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // run 중간의 RESET: 출력 tombstone_ids는 리셋 '후' 상태(빈 목록)와 일치해야 한다.
        val reset = core.execute(step(4, "RESET_AND_START"))
        assertEquals(0, reset.getJSONArray("tombstone_ids").length())
    }

    @Test
    fun `value-scoped delete also invalidates inferences derived from the deleted fact`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // 값 지정 삭제(STABLE_VALUE=BLUE): 그 기억을 근거로 만든 추론도 함께 무효화된다.
        val del = core.execute(
            step(4, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A", "STABLE_VALUE" to "BLUE")),
        )
        assertTrue("파생 추론이 무효화 목록에 게시된다",
            del.getJSONArray("invalidated_state_ids").toList().any { "inf-" in it.toString() })
        val d2 = core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        assertFalse("근거가 삭제된 추론은 재사용되지 않는다",
            d2.getJSONArray("selected_context_ids").toList().any { "inf-" in it.toString() })
    }

    @Test
    fun `network transition reports in-flight work honestly`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // 결정 직후(미완 행동·대기 결과 존재)의 네트워크 전환은 PENDING으로 정직 보고.
        val net = core.execute(step(4, "SET_NETWORK", mapOf("NETWORK_STATE" to "OFFLINE")))
        assertEquals("PENDING", net.getString("decision_state"))
    }

    @Test
    fun `CORE-6 re-learned entity receives new outcome arrivals after full delete`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        learn(core)
        core.execute(step(3, "DELETE_FACT", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        // 명시적 재학습(정규 UPSERT) 사이클의 새 결과는 tombstone과 무관하게 정상 도착한다.
        learn(core, 4)
        core.execute(step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "ENTITY_A")))
        val t = core.execute(step(6, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "elapse")))
        assertTrue("부활 사이클의 도착 기억이 생성된다",
            t.getJSONArray("preserved_state_ids").toList().any { "arrival_" in it.toString() })
    }

    @Test
    fun `revoke after one-off consumption still cancels in-flight outcomes of that scope`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_X", "SCOPE_GRANTED" to "SCOPE_X", "ONE_OFF_DOSE" to "once_x")))
        core.execute(step(3, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_X")))
        // 결정이 일회성을 소모한 뒤의 철회: 그 scope에서 개시된 in-flight 결과도 취소된다.
        core.execute(step(4, "REVOKE_SCOPE", mapOf("REVOKED_SCOPE" to "SCOPE_X")))
        val t = core.execute(step(5, "ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "later")))
        assertFalse("취소된 대기는 도착 기억을 만들지 않는다", t.toString().contains("arrival_"))
        val export = core.execute(step(6, "EXPORT_AND_END"))
        assertEquals("NO_DECISION", export.getString("decision_state"))
    }

    @Test
    fun `authority change with deadline-only correction keeps the term value under the new deadline`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_T", "CURRENT_AUTHORITY" to "T_V1", "SCOPE_GRANTED" to "SCOPE_T",
            "TERM_VALUE" to "course_t", "VALID_UNTIL" to "2026-01-05T00:00:00Z")))
        // 새 지시(v2)가 기한만 싣고 오는 정정: 값이 대체 없이 유실되면 안 된다.
        core.execute(step(3, "CORRECT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_T", "CURRENT_AUTHORITY" to "T_V2",
            "VALID_UNTIL" to "2026-01-03T00:00:00Z")))
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_T")))
        assertEquals("ACT", d.getString("decision_state"))
        assertTrue(d.getJSONArray("selected_context_ids").toList().any { "TERM_VALUE" in it.toString() })
        val mems = core.snapshotState().getJSONObject("memories")
        val termId = mems.keys().asSequence().first { "TERM_VALUE" in it }
        assertEquals("2026-01-03T00:00:00Z", mems.getJSONObject(termId).getString("valid_until"))
        assertEquals("T_V2", mems.getJSONObject(termId).getString("authority"))
        val d2 = core.execute(
            step(5, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_T"), time = "2026-01-04T00:00:00Z"),
        )
        assertFalse("정정된 새 기한 경과 후 재사용 없음",
            d2.getJSONArray("selected_context_ids").toList().any { "TERM_VALUE" in it.toString() })
    }

    @Test
    fun `deadline-only correction stamps a deadline onto a term learned without one`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_N", "SCOPE_GRANTED" to "SCOPE_N", "TERM_VALUE" to "course_n")))
        // 기한 없이 학습된 기한형: 기한만의 정정이 침묵 no-op이 되지 않고 기한을 부여한다.
        core.execute(step(3, "CORRECT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_N", "VALID_UNTIL" to "2026-01-02T00:00:00Z")))
        val d = core.execute(
            step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_N"), time = "2026-01-03T00:00:00Z"),
        )
        assertFalse("부여된 기한 경과 후 기한형은 만료된다",
            d.getJSONArray("selected_context_ids").toList().any { "TERM_VALUE" in it.toString() })
    }

    @Test
    fun `claim-off arm keeps naive full-invalidation on deadline-only correction`() {
        // 대조군(claim-off)은 정밀 인수 없이 전면 무효화로 남아야 A/B 대비가 성립한다.
        val core = ChebiCore(InMemoryStateStore(), "proc-1", gateEnabled = false)
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_T", "CURRENT_AUTHORITY" to "T_V1", "SCOPE_GRANTED" to "SCOPE_T",
            "TERM_VALUE" to "course_t", "VALID_UNTIL" to "2026-01-05T00:00:00Z")))
        core.execute(step(3, "CORRECT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_T", "CURRENT_AUTHORITY" to "T_V2",
            "VALID_UNTIL" to "2026-01-03T00:00:00Z")))
        val d = core.execute(step(4, "REQUEST_DECISION", mapOf("TARGET_ENTITY" to "MED_T")))
        assertFalse("claim-off는 무효화분을 되살리지 않는다",
            d.getJSONArray("selected_context_ids").toList().any { "TERM_VALUE" in it.toString() })
    }

    @Test
    fun `declared inference never chains to another inference as basis`() {
        val core = newCore()
        core.execute(step(1, "RESET_AND_START"))
        core.execute(step(2, "UPSERT_FACT", mapOf(
            "TARGET_ENTITY" to "MED_C", "SCOPE_GRANTED" to "SCOPE_C", "STABLE_CONFIRM_LOG" to "c1")))
        core.execute(step(3, "UPSERT_FACT", mapOf("TARGET_ENTITY" to "MED_C", "INFERRED_CONFIRM_NOTE" to "n1")))
        core.execute(step(4, "UPSERT_FACT", mapOf("TARGET_ENTITY" to "MED_C", "INFERRED_HABIT" to "h1")))
        val mems = core.snapshotState().getJSONObject("memories")
        mems.keys().asSequence().filter { it.startsWith("inf-") }.forEach { id ->
            val basis = mems.getJSONObject(id).optString("derived_from")
            if (basis.isNotEmpty()) {
                assertEquals("추론의 근거는 원 FACT만: $id -> $basis",
                    "FACT", mems.getJSONObject(basis).getString("type"))
            }
        }
    }

    private fun org.json.JSONArray.toList(): List<Any> = (0 until length()).map(::get)
}
