package com.sdh.chebi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.ProbeInputStep
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 약지기 도메인 파사드 — 사용자 UI 행동을 **합성 이벤트(ProbeInputStep)로 만들어
 * Probe와 같은 core.execute()에 넣는다**. UI 전용 판단 로직이 없으므로
 * "UI와 Probe가 같은 production core"라는 선언이 코드 경로 수준에서 참이 된다.
 *
 * 의료 가드: 이 앱은 용량·복용 여부를 결정하지 않는다. 기록된 의사 지시(권위=약별 지시
 * 버전)·기한·중단 scope를 기억·대조해 표시할 뿐이고, 복용은 사용자의 최종확인으로만
 * 확정된다. 모든 데이터는 합성이다.
 *
 * 결정론: 실시계를 읽지 않는다. 가상시계(분)와 순번은 UI 메타 저장소에 영속되며
 * canonical 상태(해시 대상)에는 넣지 않는다.
 */
class MedDomain(context: Context) {

    companion object {
        const val GOAL = "safe_med_session"
        const val MED_BP = "med_bp" // 혈압약 (상시 처방)
        const val MED_ABX = "med_abx" // 항생제 (이번 주만 — 기한형)
        const val MED_SPOUSE = "med_spouse" // 배우자의 약 (distractor — 관리 아닌 혼동 원천)
        const val ENTITY_PRIVATE = "private_note" // 민감 진단 메모 (삭제·tombstone 시연)
        const val SCOPE_BP = "scope_bp"
        const val SCOPE_ABX = "scope_abx"
        const val SCOPE_NOTIFY = "scope_notify" // 알림 경로 (실 OS 권한과 이원 설계)
        const val BP_REFILL_AMOUNT = 30
        const val ABX_TERM_DAYS = 7L
        private const val CLOCK_BASE_ISO = "2026-08-01T08:00" // 첫 아침 세션
    }

    private val prefs = context.getSharedPreferences("medkeeper-ui-meta", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    val store = AndroidStateStore(context, ChebiProbeAdapter.NAMESPACE_FULL)
    val core = ChebiCore(store, ChebiProbeAdapter.PROCESS_MARKER)

    // ------------------------------------------------------------------
    // 합성 이벤트 발행 (가상시계 + 순번)
    // ------------------------------------------------------------------

    private fun baseTime(): LocalDateTime = LocalDateTime.parse(CLOCK_BASE_ISO)

    private fun clockMinutes(): Long = prefs.getLong("ui_clock_min", 0L)

    private fun nowTime(): LocalDateTime = baseTime().plusMinutes(clockMinutes())

    fun virtualTimeIso(): String = isoOf(nowTime())

    private fun isoOf(t: LocalDateTime): String =
        t.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

    /** 아침(06~14시)/저녁 슬롯 — 복용 세션의 이름표. */
    fun slotLabel(): String = if (nowTime().hour in 6..13) "아침" else "저녁"

    fun sessionId(): String = "SESSION-UI-" + prefs.getInt("ui_session", 1)

    private fun emit(op: String, roles: Map<String, String>): JSONObject {
        val seq = prefs.getInt("ui_seq", 0) + 1
        prefs.edit()
            .putInt("ui_seq", seq)
            .putLong("ui_clock_min", clockMinutes() + 1) // 이벤트마다 가상 1분
            .apply()
        val rolesJson = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }
        val step = ProbeInputStep(
            seq, "UI-%04d".format(seq), op, sessionId(), virtualTimeIso(),
            "EV-UI-%04d".format(seq), rolesJson, "", "",
        )
        return core.execute(step)
    }

    // ------------------------------------------------------------------
    // E1 Learn — 처방·알림·메모 학습 (전부 같은 UPSERT 경로)
    // ------------------------------------------------------------------

    /** 혈압약 처방 등록 — 지시 v1이 이 약의 권위. 잔량·임계는 지시와 무관한 사실이라
     *  권위 중립으로 별도 기록한다(지시 정정이 잔량 기록을 무효화하지 않도록). */
    fun learnBpPrescription(): JSONObject {
        val r = emit(
            "UPSERT_FACT",
            mapOf(
                "PRIMARY_GOAL" to GOAL, "TARGET_ENTITY" to MED_BP,
                "CURRENT_AUTHORITY" to "bp_v1", "SCOPE_GRANTED" to SCOPE_BP,
                "STABLE_PRESCRIPTION" to "bp_morning_1tab",
            ),
        )
        emit(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to MED_BP, "SCOPE_GRANTED" to SCOPE_BP,
                // 시연 동선: 잔량 6이면 두 세션 안에 임계(5) 아래로 내려가 리필 draft →
                // 승인 → 지연 도착 → 가산의 전 과정을 3분 데모에서 관찰할 수 있다.
                "STABLE_STOCK" to "stock_6", "STABLE_THRESHOLD" to "threshold_5",
            ),
        )
        return r
    }

    /** 항생제 등록 — 이번 주만(기한형): 기한 내 세션마다 재사용, 기한 경과 시 만료. */
    fun learnAbxCourse(): JSONObject {
        val until = isoOf(nowTime().plusDays(ABX_TERM_DAYS))
        val r = emit(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to MED_ABX,
                "CURRENT_AUTHORITY" to "abx_v1", "SCOPE_GRANTED" to SCOPE_ABX,
                "TERM_VALUE" to "abx_evening_1tab", "VALID_UNTIL" to until,
            ),
        )
        emit(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to MED_ABX, "SCOPE_GRANTED" to SCOPE_ABX,
                "STABLE_STOCK" to "stock_14", "STABLE_THRESHOLD" to "threshold_2",
            ),
        )
        return r
    }

    /** 알림 예약 — 호출측(UI)이 실제 OS 알림 권한을 확인한 뒤에만 부른다. */
    fun learnNotifySchedule(): JSONObject = emit(
        "UPSERT_FACT",
        mapOf(
            "TARGET_ENTITY" to MED_BP,
            "SCOPE_GRANTED" to SCOPE_NOTIFY, "STABLE_ALERT" to "notify_morning_0800",
        ),
    )

    fun learnPrivateNote(): JSONObject = emit(
        "UPSERT_FACT",
        mapOf(
            "TARGET_ENTITY" to ENTITY_PRIVATE,
            "SCOPE_GRANTED" to SCOPE_BP, "ONE_OFF_NOTE" to "diagnosis_memo_synthetic",
        ),
    )

    // ------------------------------------------------------------------
    // E2 Reuse — 복용 세션 (프리필 → 최종확인 → 복용 기록)
    // ------------------------------------------------------------------

    /** 현재 슬롯에 등재된 약(아침=혈압약, 저녁=항생제 등) — 세션 결정·확인의 대상. */
    fun sessionTargetMed(): String =
        listOf(MED_BP, MED_ABX).firstOrNull { isMedListedToday(it) } ?: MED_BP

    /** 복용 세션 시작: 유효한 처방 기억만 프리필. 도착한 리필이 있으면 먼저 잔량에 반영. */
    fun startSession(): JSONObject {
        applyArrivedRefills()
        val r = emit(
            "REQUEST_DECISION",
            mapOf("PRIMARY_GOAL" to GOAL, "TARGET_ENTITY" to sessionTargetMed()),
        )
        if (r.getString("decision_state") == "WAIT") {
            addWaited(r.getString("event_id"))
        }
        return r
    }

    private fun addWaited(eventId: String) {
        val cur = prefs.getStringSet("waited_events", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("waited_events", cur + eventId).apply()
    }

    /** 복용 전 최종확인 — 별도 이벤트로 원장에 기재된다 (프리필과 구분 관찰, 선언 E2). */
    fun confirmBeforeTaking(): JSONObject = emit(
        "UPSERT_FACT",
        mapOf("TARGET_ENTITY" to sessionTargetMed(), "STABLE_CONFIRM_LOG" to "final_check_s" + prefs.getInt("ui_seq", 0)),
    )

    /**
     * 복용 기록: 오늘 세션의 유효한 약마다 잔량 정수를 1 감산해 기록한다(로컬 판단 —
     * 오프라인에서도 진행). 잔량이 임계치 이하로 내려가면 리필 draft 결정을 생성한다.
     */
    fun recordTaking(): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        var recordedAny = false
        listOf(MED_BP, MED_ABX).forEach { med ->
            val stock = stockOf(med) ?: return@forEach
            if (!isMedListedToday(med)) return@forEach
            val newStock = (stock - 1).coerceAtLeast(0)
            val stockScope = validOf(med).values.firstOrNull {
                it.getString("value").startsWith("stock_")
            }?.optString("scope")?.takeIf { it.isNotEmpty() && it != "DEFAULT" }
            results += emit(
                "UPSERT_FACT",
                buildMap {
                    put("TARGET_ENTITY", med)
                    put("STABLE_STOCK", "stock_$newStock")
                    if (stockScope != null) put("SCOPE_GRANTED", stockScope)
                },
            )
            recordedAny = true
            val threshold = thresholdOf(med) ?: 0
            if (newStock < threshold && !prefs.getBoolean("draft_open_$med", false)) {
                // 리필 draft 생성 = 전송 성격의 결정(오프라인이면 정직한 WAIT).
                val draft = emit(
                    "REQUEST_DECISION",
                    mapOf("PRIMARY_GOAL" to GOAL, "TARGET_ENTITY" to med),
                )
                if (draft.getString("decision_state") == "WAIT") {
                    addWaited(draft.getString("event_id"))
                }
                prefs.edit().putBoolean("draft_open_$med", true).apply()
                results += draft
            }
        }
        if (recordedAny) countSlotConfirm()
        return results
    }

    /** 리필 draft 승인 — 열린 draft가 있을 때만 대기열 등록(오조작으로 무에서 리필이
     *  생기지 않게). 승인만이 '리필' 대기 등록의 트리거다. */
    fun approveRefill(med: String): JSONObject? {
        if (!prefs.getBoolean("draft_open_$med", false)) return null
        prefs.edit().putBoolean("refill_credit_$med", true).apply()
        return emit(
            "UPSERT_FACT",
            mapOf("TARGET_ENTITY" to med, "DELAYED_OUTCOME" to "refill_$med"),
        )
    }

    /** 도착한 리필을 잔량에 반영(결정론 규칙): '승인으로 등록된 리필 outcome'의 도착
     *  기억만 인정하고(일반 지연 결과와 구분), 같은 도착은 1회만 가산한다. */
    private fun applyArrivedRefills() {
        lastAppliedRefills = 0
        listOf(MED_BP, MED_ABX).forEach { med ->
            if (!prefs.getBoolean("refill_credit_$med", false)) return@forEach
            val applied = prefs.getStringSet("applied_arrivals", emptySet()) ?: emptySet()
            val refillArrival = core.validMemories().keys.firstOrNull { id ->
                id.contains("arrival_outcome-decl-refill_$med") && id !in applied
            } ?: return@forEach
            // 중단·삭제된 약에는 가산하지 않는다 (유효 잔량 기억이 있을 때만).
            val stock = stockOf(med) ?: return@forEach
            val stockScope = validOf(med).values.firstOrNull {
                it.getString("value").startsWith("stock_")
            }?.optString("scope")?.takeIf { it.isNotEmpty() && it != "DEFAULT" }
            emit(
                "UPSERT_FACT",
                buildMap {
                    put("TARGET_ENTITY", med)
                    put("STABLE_STOCK", "stock_" + (stock + BP_REFILL_AMOUNT))
                    if (stockScope != null) put("SCOPE_GRANTED", stockScope)
                },
            )
            lastAppliedRefills += 1
            prefs.edit()
                .putStringSet("applied_arrivals", applied + refillArrival)
                .putBoolean("refill_credit_$med", false)
                .putBoolean("draft_open_$med", false)
                .apply()
        }
    }

    /** 시간대 추론(결정론): 같은 슬롯 확인 2회 이상 → '잘 지켜지는 시간대' INFERENCE 기록. */
    private fun countSlotConfirm() {
        val key = "slot_count_" + if (slotLabel() == "아침") "am" else "pm"
        val n = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, n).apply()
        if (n == 2) {
            emit(
                "UPSERT_FACT",
                mapOf("TARGET_ENTITY" to sessionTargetMed(), "INFERRED_SLOT_HABIT" to "kept_slot_" + slotLabel()),
            )
        }
    }

    // ------------------------------------------------------------------
    // E3 Exception — 정정·중단·삭제·네트워크·시간·distractor
    // ------------------------------------------------------------------

    /** 처방 정정: 혈압약 지시 v1→v2 — 이 약의 구지시·파생만 무효화(다른 약 보존). */
    fun correctBpPrescription(): JSONObject = emit(
        "CORRECT_FACT",
        mapOf(
            "TARGET_ENTITY" to MED_BP, "CURRENT_AUTHORITY" to "bp_v2",
            "STABLE_PRESCRIPTION" to "bp_morning_half_tab",
        ),
    )

    /** 항생제 복용 중단 — 그 약의 기억·미도착 리필 대기만 무효화·취소. */
    fun stopAbx(): JSONObject {
        prefs.edit()
            .putBoolean("draft_open_$MED_ABX", false)
            .putBoolean("refill_credit_$MED_ABX", false)
            .apply()
        return emit(
            "REVOKE_SCOPE",
            mapOf("REVOKED_SCOPE" to SCOPE_ABX, "PRESERVED_SCOPE" to SCOPE_BP),
        )
    }

    /** 알림 scope 철회(합성 이벤트 — Probe 경로와 동일 입력; 실 OS 권한과 이원 설계). */
    fun revokeNotifyScope(): JSONObject = emit(
        "REVOKE_SCOPE",
        mapOf("REVOKED_SCOPE" to SCOPE_NOTIFY, "PRESERVED_SCOPE" to SCOPE_BP),
    )

    fun setNetwork(online: Boolean): JSONObject = emit(
        "SET_NETWORK",
        mapOf("LINK_STATE" to if (online) "ONLINE" else "OFFLINE"),
    )

    /** 보류 목록 전체를 순회 재전달 — 각 이벤트는 원본 ID로 정확히 1회만 재시도된다. */
    fun replayWaited(): JSONObject? {
        val waited = (prefs.getStringSet("waited_events", emptySet()) ?: emptySet()).sorted()
        if (waited.isEmpty()) return null
        var last: JSONObject? = null
        val remaining = waited.toMutableSet()
        waited.forEach { ev ->
            val r = emit("REPLAY_EVENT", mapOf("REPLAY_OF_EVENT_ID" to ev))
            if (r.getString("decision_state") != "WAIT") remaining.remove(ev)
            last = r
        }
        prefs.edit().putStringSet("waited_events", remaining).apply()
        return last
    }

    /** 반나절 전진 — 다음 복용 슬롯(아침↔저녁)으로 이동. */
    fun advanceHalfDay(): JSONObject {
        val next = nowTime().plusHours(12).withMinute(0)
        prefs.edit().putLong("ui_clock_min", java.time.Duration.between(baseTime(), next).toMinutes()).apply()
        return emit("ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "half_day"))
    }

    /** 며칠 전진 — 리필 도착·기한 만료를 관찰하는 지연 구간. */
    fun advanceDays(days: Long): JSONObject {
        val next = nowTime().plusDays(days).withHour(8).withMinute(0)
        prefs.edit().putLong("ui_clock_min", java.time.Duration.between(baseTime(), next).toMinutes()).apply()
        return emit("ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "days_$days"))
    }

    fun startNewSession(): JSONObject {
        prefs.edit().putInt("ui_session", prefs.getInt("ui_session", 1) + 1).apply()
        return emit(
            "ADVANCE_SESSION",
            mapOf(
                "PRIMARY_GOAL" to GOAL, "TARGET_ENTITY" to MED_BP,
                "DISTRACTOR_ENTITY" to MED_SPOUSE,
            ),
        )
    }

    /** 배우자의 약에 대한 결정 요청 — 내 관리 대상이 아니므로 ABSTAIN(보류) 시연. */
    fun requestSpouseDecision(): JSONObject = emit(
        "REQUEST_DECISION",
        mapOf("PRIMARY_GOAL" to GOAL, "TARGET_ENTITY" to MED_SPOUSE),
    )

    fun deletePrivateNote(): JSONObject =
        emit("DELETE_FACT", mapOf("TARGET_ENTITY" to ENTITY_PRIVATE))

    fun resetAll(): JSONObject {
        prefs.edit()
            .putLong("ui_clock_min", 0L)
            .putString("ui_metrics", "[]")
            .putStringSet("waited_events", emptySet())
            .remove("last_comparison")
            .putInt("ui_session", prefs.getInt("ui_session", 1) + 1)
            .putInt("slot_count_am", 0).putInt("slot_count_pm", 0)
            .putBoolean("draft_open_$MED_BP", false).putBoolean("draft_open_$MED_ABX", false)
            .putBoolean("refill_credit_$MED_BP", false).putBoolean("refill_credit_$MED_ABX", false)
            .putStringSet("applied_arrivals", emptySet())
            .apply()
        return emit("RESET_AND_START", emptyMap())
    }

    // ------------------------------------------------------------------
    // MedChecklist — 체크리스트는 원장 위의 '뷰'다. 약별 성분에 출처가 붙고,
    // 무효화되면 그 성분만 재계산된다(E4 = 무효화 정밀성 + 재조립).
    // ------------------------------------------------------------------

    data class MedRow(
        val med: String,
        val label: String,
        val prescription: String?,
        val stock: Int?,
        val threshold: Int?,
        val listedToday: Boolean,
        val note: String,
    )

    data class Checklist(
        val rows: List<MedRow>,
        val arrivals: Int,
        val prefillCount: Int,
        val manualCount: Int,
    )

    private fun validOf(med: String): Map<String, JSONObject> =
        core.validMemories().filterValues { it.optString("entity") == med }

    private fun intVal(med: String, prefix: String): Int? =
        validOf(med).values.firstOrNull { it.getString("value").startsWith(prefix) }
            ?.getString("value")?.substringAfterLast('_')?.toIntOrNull()

    fun stockOf(med: String): Int? = intVal(med, "stock_")
    private fun thresholdOf(med: String): Int? = intVal(med, "threshold_")

    /** 처방 값의 슬롯 표기(_morning_/_evening_)와 현재 슬롯이 맞아야 오늘 목록에 오른다. */
    private fun matchesSlot(value: String): Boolean {
        val slot = if (slotLabel() == "아침") "morning" else "evening"
        return !(value.contains("_morning_") || value.contains("_evening_")) ||
            value.contains("_${slot}_")
    }

    private fun isMedListedToday(med: String): Boolean =
        validOf(med).values.any {
            val v = it.getString("value")
            (v.startsWith("bp_") || v.startsWith("abx_")) && matchesSlot(v)
        }

    /** 이번 startSession에서 새로 잔량에 반영된 리필 수 — 지표용(누계 아님). */
    var lastAppliedRefills: Int = 0
        private set

    fun composeChecklist(): Checklist {
        fun row(med: String, label: String): MedRow {
            val valid = validOf(med)
            val prescription = valid.values.firstOrNull {
                it.getString("value").let { v -> (v.startsWith("bp_") || v.startsWith("abx_")) && matchesSlot(v) }
            }
            val note = when {
                prescription == null && valid.isEmpty() -> "기록 없음 또는 중단·만료"
                prescription == null -> "지시 없음(중단·만료) — 잔량 기록만 있음"
                prescription.getString("lifetime") == "TERM" ->
                    "기한부 처방 (${prescription.optString("valid_until").take(10)}까지)"
                else -> "상시 처방 (지시 ${prescription.optString("authority")})"
            }
            return MedRow(
                med, label,
                prescription?.getString("value"),
                stockOf(med), thresholdOf(med),
                prescription != null,
                note,
            )
        }

        val rows = listOf(row(MED_BP, "혈압약"), row(MED_ABX, "항생제"))
        // 지표 정의(선언 정합): 프리필 = 이번 슬롯 등재 행 수 + 이번 세션에 새로 반영된 도착 수.
        val prefill = rows.count { it.listedToday } + lastAppliedRefills
        val manual = rows.count { !it.listedToday && it.stock != null }
        return Checklist(rows, lastAppliedRefills, prefill, manual)
    }

    // ------------------------------------------------------------------
    // CORE-3 지표 — 프리필/직접확인/최종확인 기록 (canonical 상태 밖 UI 관찰 기록)
    // ------------------------------------------------------------------

    fun recordSessionMetric(prefill: Int, manual: Int, safetyConfirmed: Boolean, decision: String) {
        val arr = JSONArray(prefs.getString("ui_metrics", "[]"))
        arr.put(
            JSONObject()
                .put("session", sessionId())
                .put("slot", slotLabel())
                .put("virtual_time", virtualTimeIso())
                .put("prefill_count", prefill)
                .put("manual_input_count", manual)
                .put("safety_confirmed", safetyConfirmed)
                .put("decision_state", decision),
        )
        prefs.edit().putString("ui_metrics", arr.toString()).apply()
    }

    fun metrics(): JSONArray = JSONArray(prefs.getString("ui_metrics", "[]"))

    // ------------------------------------------------------------------
    // 짝비교 (C3) — 같은 snapshot을 격리된 두 상태공간에 복사해 동일 시나리오 실행
    // ------------------------------------------------------------------

    fun runComparison(): JSONObject {
        val snapshot = core.snapshotState()
        val fullStore = AndroidStateStore(appContext, "medkeeper-cmp-full")
        val offStore = AndroidStateStore(appContext, "medkeeper-cmp-off")
        fullStore.save(JSONObject(snapshot.toString()))
        offStore.save(JSONObject(snapshot.toString()))
        val report = MedComparisonLab.run(fullStore, offStore, ChebiProbeAdapter.PROCESS_MARKER)
        prefs.edit().putString("last_comparison", report.toString()).apply()
        return report
    }

    fun lastComparison(): JSONObject? =
        prefs.getString("last_comparison", null)?.let(::JSONObject)

    // ------------------------------------------------------------------
    // 증거 내보내기 — MISSION_ADAPTER의 evidence_path와 같은 경로에 실파일 기록
    // ------------------------------------------------------------------

    fun exportEvidence(baseDir: File): List<File> {
        val state = core.snapshotState()
        val written = mutableListOf<File>()
        fun put(rel: String, body: JSONObject) {
            val f = File(baseDir, rel)
            f.parentFile?.mkdirs()
            f.writeText(body.toString(2))
            written += f
        }
        val entities = state.getJSONObject("entities")
        put("evidence/state/current_goal.json", JSONObject().put("goal", entities.opt("goal")))
        put("evidence/state/target_entity.json", JSONObject().put("target", entities.opt("target")))
        put(
            "evidence/state/context_selection.json",
            JSONObject()
                .put("distractors", entities.getJSONArray("distractors"))
                .put("valid_memory_ids", JSONArray(core.validMemories().keys.toList())),
        )
        put("evidence/state/facts.json", state.getJSONObject("memories"))
        put(
            "evidence/state/authority.json",
            JSONObject().put("authorities", state.getJSONObject("authorities")),
        )
        put(
            "evidence/state/invalidation.json",
            JSONObject().put("revoked_scopes", state.getJSONArray("revoked_scopes")),
        )
        put(
            "evidence/state/recovery.json",
            JSONObject().put(
                "active_scopes",
                JSONArray(
                    core.validMemories().values.map { it.optString("scope") }.distinct().sorted(),
                ),
            ),
        )
        put("evidence/state/tombstones.json", JSONObject().put("tombstones", state.getJSONArray("tombstones")))
        put(
            "evidence/ledger/outcomes.json",
            JSONObject()
                .put("pending_outcomes", state.getJSONArray("pending_outcomes"))
                .put("outcome_ledger", state.getJSONArray("outcome_ledger"))
                .put("reconciled_events", state.getJSONArray("reconciled_events"))
                .put("actions", state.getJSONObject("actions")),
        )
        put("evidence/ui/metrics.json", JSONObject().put("session_metrics", metrics()))
        lastComparison()?.let { put("evidence/comparison/comparison_run.json", it) }
        return written
    }
}

/**
 * 짝비교 실험실 (C3 인과 재현) — 단일 변수 절제: full은 VG-Ledger 유효성 게이트,
 * claim-off는 같은 core에서 게이트만 끈 동일 코드경로의 기본 동작(직전 체크리스트 통짜
 * 재사용, 충돌 시 전체 무효화). 두 arm 모두 episode를 정상 완주한다.
 *
 * ground truth(각 충돌 이벤트의 실제 영향 집합)를 데이터로 명기한다.
 */
object MedComparisonLab {

    private const val T = "cmp_med_bp"

    /** S6 처방 정정(bp v1→v2)의 실제 영향: v1 스탬프 기억 + 그 파생 추론. */
    private val GT_CORRECT_S6 = setOf(
        "mem-cmp_med_bp-STABLE_PRESCRIPTION",
        "inf-cmp_med_bp-recent_success",
    )

    /** S6 정정에서 보존되어야 할 항목(권위 중립 잔량·임계) — 복구 보존율의 ground truth. */
    private val GT_KEEP_S6 = setOf(
        "mem-cmp_med_bp-STABLE_STOCK",
        "mem-cmp_med_bp-STABLE_THRESHOLD",
    )

    fun run(fullStore: ChebiStateStore, offStore: ChebiStateStore, marker: String): JSONObject {
        val full = runArm(ChebiCore(fullStore, marker, gateEnabled = true))
        val off = runArm(ChebiCore(offStore, marker, gateEnabled = false))
        return JSONObject()
            .put("artifact_kind", "comparison_run")
            .put(
                "ground_truth",
                JSONObject().put("S6_correct_impact", JSONArray(GT_CORRECT_S6.toList())),
            )
            .put("full_arm", full)
            .put("claim_off_arm", off)
    }

    private fun runArm(core: ChebiCore): JSONObject {
        var seq = 0
        fun step(op: String, roles: Map<String, String>, time: String): JSONObject {
            seq += 1
            val rolesJson = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }
            return core.execute(
                ProbeInputStep(
                    seq, "CMP-%02d".format(seq), op, "SESSION-CMP", time,
                    "EV-CMP-%02d".format(seq), rolesJson, "", "",
                ),
            )
        }

        // S0 격리 arm 내부 초기화 — ground truth가 라이브 상태와 무관하게 결정론이 되도록.
        step("RESET_AND_START", emptyMap(), "2026-09-01T08:00:00Z")
        // S1 혈압약: 처방(지시 bp_v1 스탬프)과 잔량(권위 중립 사실)을 분리 등록
        step(
            "UPSERT_FACT",
            mapOf(
                "PRIMARY_GOAL" to "cmp_goal", "TARGET_ENTITY" to T,
                "CURRENT_AUTHORITY" to "bp_v1", "SCOPE_GRANTED" to "scope_bp",
                "STABLE_PRESCRIPTION" to "bp_morning_1tab",
            ),
            "2026-09-01T08:01:00Z",
        )
        step(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to T, "SCOPE_GRANTED" to "scope_bp",
                "STABLE_STOCK" to "stock_30", "STABLE_THRESHOLD" to "threshold_5",
            ),
            "2026-09-01T08:02:00Z",
        )
        // S2 항생제(기한형 — 2일 뒤 만료) + 잔량 분리
        step(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to "cmp_med_abx", "SCOPE_GRANTED" to "scope_abx",
                "TERM_VALUE" to "abx_evening_1tab", "VALID_UNTIL" to "2026-09-03T08:00:00Z",
            ),
            "2026-09-01T08:03:00Z",
        )
        step(
            "UPSERT_FACT",
            mapOf(
                "TARGET_ENTITY" to "cmp_med_abx", "SCOPE_GRANTED" to "scope_abx",
                "STABLE_STOCK" to "stock_14", "STABLE_THRESHOLD" to "threshold_2",
            ),
            "2026-09-01T08:04:00Z",
        )
        // S3 첫 세션(혈압약 대상 결정)
        val s3 = step("REQUEST_DECISION", mapOf("PRIMARY_GOAL" to "cmp_goal", "TARGET_ENTITY" to T), "2026-09-01T08:05:00Z")
        // S4 3일 경과 — 항생제 기한 만료
        step("ADVANCE_TIME", mapOf("DELAYED_OUTCOME" to "elapse"), "2026-09-04T08:00:00Z")
        // S5 만료 후 항생제 대상 결정 — full은 만료 게이트로 제외(ASK), off는 유령 재사용(ACT)
        val s5 = step("REQUEST_DECISION", mapOf("PRIMARY_GOAL" to "cmp_goal", "TARGET_ENTITY" to "cmp_med_abx"), "2026-09-04T08:01:00Z")
        // S6 혈압약 처방 정정 v1→v2 (GT: v1 처방 + 파생 추론)
        val s6 = step(
            "CORRECT_FACT",
            mapOf(
                "TARGET_ENTITY" to T, "CURRENT_AUTHORITY" to "bp_v2",
                "STABLE_PRESCRIPTION" to "bp_morning_half_tab",
            ),
            "2026-09-04T08:02:00Z",
        )
        // S7 정정 후 세션 — 두 arm 모두 정상 완주
        val s7 = step("REQUEST_DECISION", mapOf("PRIMARY_GOAL" to "cmp_goal", "TARGET_ENTITY" to T), "2026-09-04T08:03:00Z")

        fun selected(r: JSONObject) = ids(r.getJSONArray("selected_context_ids"))
        fun invalidated(r: JSONObject) = ids(r.getJSONArray("invalidated_state_ids"))
        fun preservedIds(r: JSONObject) = ids(r.getJSONArray("preserved_state_ids"))
        fun precision(actual: Set<String>, gt: Set<String>): Double =
            if (actual.isEmpty()) 0.0 else (actual intersect gt).size.toDouble() / actual.size

        return JSONObject()
            .put("s3_prefill", selected(s3).size)
            .put("s5_expired_ghost_reuse", selected(s5).count { "TERM" in it })
            .put("s5_decision", s5.getString("decision_state"))
            .put("s6_invalidated", JSONArray(invalidated(s6).sorted()))
            .put("s6_impact_precision", precision(invalidated(s6), GT_CORRECT_S6))
            .put(
                "s6_preservation_rate",
                (preservedIds(s6) intersect GT_KEEP_S6).size.toDouble() / GT_KEEP_S6.size,
            )
            .put("s7_prefill_final", selected(s7).size)
            .put("s7_decision", s7.getString("decision_state"))
            .put("episode_completed", true)
    }

    private fun ids(arr: JSONArray): Set<String> =
        (0 until arr.length()).map(arr::getString).toSet()
}
