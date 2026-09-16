package com.sdh.chebi

import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.Sha256

/**
 * 약지기 production core — VG-Ledger (Validity-Gate Ledger).
 *
 * 하나의 영속 상태(기억 원장 + 행동 원장 + 보류 결과 큐)가 앱 UI와 Probe Mode 양쪽에서
 * 동일하게 사용된다. 모든 판단은 결정론적이며(모델·난수·실시계 없음), 가상시간과 입력
 * 이벤트만으로 구동된다.
 *
 * 기억(MemoryItem) = { 유형 FACT/INFERENCE/EPHEMERAL, 값, 권위(authority), 범위(scope),
 *   출처(origin event), 상태 ACTIVE/CONSUMED/INVALIDATED, entity 연결 }
 * 게이트: 재사용(REQUEST_DECISION의 context 선택) · 충돌(CORRECT/REVOKE의 영향 특정) ·
 *   복구(출처 역추적 성분 무효화)가 전부 같은 유효성 판정을 공유한다.
 */
interface ChebiStateStore {
    fun load(): JSONObject
    fun save(state: JSONObject)
}

class InMemoryStateStore : ChebiStateStore {
    private var encoded: String? = null
    override fun load(): JSONObject = encoded?.let(::JSONObject) ?: JSONObject()
    override fun save(state: JSONObject) {
        encoded = CanonicalJson.encode(state)
    }
}

class ChebiCore(
    private val store: ChebiStateStore,
    private val processMarker: String,
    /** VG-Ledger 게이트 스위치. false = claim-off arm: 유효조건 판정 없이 직전 상태 전체를
     *  재사용하고, 정정은 전체 재작성·철회는 전체 재확인으로 폴백하는 '성실하지만 순진한' 동작. */
    private val gateEnabled: Boolean = true,
) {

    fun execute(step: ProbeInputStep): JSONObject {
        val state = normalized(store.load())
        // before 해시는 epoch 갱신 이전에 찍는다: 직전 step의 after와 항상 일치해
        // (KILL 포함) 상태 연속성이 해시 체인으로 증명되고, epoch 증가는 이 step의 변화로 기록된다.
        val before = digestOf(state)
        ensureProcessEpoch(state)

        val selected = linkedSetOf<String>()
        val invalidated = linkedSetOf<String>()
        val preserved = linkedSetOf<String>()
        var decision = "NO_DECISION"
        var action: JSONObject? = null

        val memories = state.getJSONObject("memories")
        val actions = state.getJSONObject("actions")

        state.put("virtual_time", step.virtualTime)
        state.put("session_id", step.sessionId)

        when (step.operation) {
            "RESET_AND_START" -> {
                resetAll(state)
            }

            "UPSERT_FACT" -> {
                upsertFromRoles(state, step, invalidated, preserved)
            }

            "ADVANCE_SESSION" -> {
                // 새 세션: 기억은 지속(continuity), distractor entity는 격리 등록만 한다.
                registerEntities(state, step.roles)
                activeIds(memories).forEach(preserved::add)
                decision = pendingOrNone(state)
            }

            "REQUEST_DECISION" -> {
                state.put("decision_request_count", state.getInt("decision_request_count") + 1)
                val outcome = decide(state, step, selected)
                decision = outcome.first
                action = outcome.second
            }

            "CORRECT_FACT" -> {
                correctFromRoles(state, step, invalidated, preserved)
            }

            "REVOKE_SCOPE" -> {
                revokeScope(state, step, invalidated, preserved)
            }

            "DELETE_FACT" -> {
                deleteFact(state, step, invalidated, preserved)
            }

            "SET_NETWORK" -> {
                state.put("network", requestedNetwork(step.roles))
                activeIds(memories).forEach(preserved::add)
                // 다른 수동 관찰 분기(세션·KILL·EXPORT)와 동일하게 미완 행동·대기 결과를
                // 정직하게 보고한다 — 전환 step만 NO_DECISION으로 위장하지 않게.
                decision = pendingOrNone(state)
            }

            "PROCESS_KILL_RELAUNCH" -> {
                // 실제 kill은 host가 수행. 이 step은 relaunch 후 첫 관찰 —
                // 상태는 원장에서 복구되었고 변형 없이 그대로임을 해시로 증명한다.
                activeIds(memories).forEach(preserved::add)
                decision = pendingOrNone(state)
            }

            "REPLAY_EVENT" -> {
                val replayOf = roleValue(step.roles, listOf("REPLAY_OF_EVENT_ID", "REPLAY", "DUPLICATE"))
                val existing = replayOf?.let(actions::optJSONObject)
                val waiting = state.getJSONObject("waiting_events")
                if (existing != null) {
                    // exactly-once: 새 행동을 만들지 않고 동일 행동을 echo한다. 라벨은 원장의
                    // commit_state와 1:1 — 취소는 ABSTAIN, 커밋 완료만 CONFIRMED_COMPLETE,
                    // 아직 결과 미도착(in-flight PROPOSED)은 PENDING (완료로 위장 금지, G3).
                    action = JSONObject(CanonicalJson.encode(existing))
                    decision = when (existing.getString("commit_state")) {
                        "CANCELLED" -> "ABSTAIN"
                        "COMMITTED" -> "CONFIRMED_COMPLETE"
                        else -> "PENDING"
                    }
                } else if (replayOf != null && waiting.has(replayOf)) {
                    // WAIT로 보류됐던 이벤트의 재전달: 조건이 회복됐으면 지금 1회 재시도.
                    // 행동은 원본 이벤트 ID로 생성되므로 이벤트당 최대 1개가 보장된다.
                    val originalRoles = waiting.optJSONObject(replayOf) ?: step.roles
                    val retryStep = ProbeInputStep(
                        step.index, step.stepId, step.operation, step.sessionId,
                        step.virtualTime, replayOf, originalRoles, step.canonicalJson, step.digest,
                    )
                    val retry = decide(state, retryStep, selected)
                    decision = retry.first
                    action = retry.second
                    if (decision != "WAIT") waiting.remove(replayOf)
                } else {
                    decision = "ABSTAIN"
                }
                // 재시도 경로에서 selected로 이미 게시된 ID는 보존 목록에 중복 게시하지 않는다
                // (최초 전달 REQUEST_DECISION의 출력 관례와 동일하게).
                activeIds(memories).forEach { if (it !in selected) preserved += it }
            }

            "DELIVER_OUT_OF_ORDER" -> {
                // 늦게 도착한 과거 이벤트: tombstone·권위 게이트로 심사해 기각 사유를
                // 분리 기록하고, 오래된 값이 현재 상태를 되살리거나 덮지 않게 한다(reconcile).
                reconcileOutOfOrder(state, step, invalidated, preserved)
                decision = "PENDING"
            }

            "ADVANCE_TIME" -> {
                // 가상시간 경과: 보류 중이던 지연 결과를 도착 처리해 행동 원장에 반영.
                val arrived = arriveDueOutcomes(state, step)
                activeIds(memories).forEach(preserved::add)
                decision = if (arrived > 0) "PENDING" else pendingOrNone(state)
            }

            "EXPORT_AND_END" -> {
                activeIds(memories).forEach(preserved::add)
                decision = pendingOrNone(state)
            }
        }

        // 불변식: 같은 step에서 무효화로 게시된 ID는 보존 목록에 중복 게시하지 않는다.
        preserved.removeAll(invalidated)

        val receiptId = "receipt-" + safeId(step.eventId)
        val evidenceId = "evidence-" + safeId(step.eventId)
        appendUnique(state.getJSONArray("receipts"), receiptId)
        appendUnique(state.getJSONArray("evidence"), evidenceId)
        store.save(state)

        return JSONObject()
            .put("step_id", step.stepId)
            .put("event_id", step.eventId)
            .put("operation", step.operation)
            .put("session_id", step.sessionId)
            .put("virtual_time", step.virtualTime)
            .put("process_epoch", state.getInt("process_epoch"))
            .put("network_state", state.getString("network"))
            .put("decision_state", decision)
            .put("selected_context_ids", JSONArray(selected.toList()))
            .put("invalidated_state_ids", JSONArray(invalidated.toList()))
            .put("preserved_state_ids", JSONArray(preserved.toList()))
            .put("state_before_sha256", before)
            .put("state_after_sha256", digestOf(state))
            .put("action", action ?: JSONObject.NULL)
            // RESET은 tombstones 배열 객체 자체를 교체하므로 로컬 참조가 아니라 state에서
            // 재조회한다 — 출력이 state_after 해시와 항상 같은 상태를 가리키게(mid-run RESET 정합).
            .put("tombstone_ids", copyArray(state.getJSONArray("tombstones")))
            .put("receipt_ids", JSONArray().put(receiptId))
            .put("auto_check_evidence_ids", JSONArray().put(evidenceId))
    }

    fun decisionRequestCount(): Int =
        normalized(store.load()).getInt("decision_request_count")

    /** UI 플랜 조립용 스냅샷 (정규화된 현재 상태 사본). */
    fun snapshotState(): JSONObject = normalized(store.load())

    /**
     * UI 플랜 조립용 공개 게이트: Probe 재사용 게이트와 **같은** isValid(권위·scope·기한·소모)
     * 판정을 노출한다 — 유효성 판정이 두 곳으로 갈라지지 않게 하는 단일화 지점.
     * 주의: entity·distractor 선별은 decide가 결정 대상에 대해 추가로 수행하므로,
     * 호출자는 표시 대상 entity로 재필터해 사용한다 (MedDomain.validOf가 그 예).
     */
    fun validMemories(): Map<String, JSONObject> {
        val state = normalized(store.load())
        val memories = state.getJSONObject("memories")
        return memories.keys().asSequence().sorted()
            .map { it to memories.getJSONObject(it) }
            .filter { (_, m) -> isValid(state, m) }
            .toMap()
    }

    fun evidenceIds(): List<String> {
        val state = normalized(store.load())
        return strings(state.getJSONArray("evidence")) + strings(state.getJSONArray("receipts"))
    }

    // ------------------------------------------------------------------
    // E1 Learn — role 분류와 기억 기록
    // ------------------------------------------------------------------

    /** 알려진 정확 role 키 — substring 분류보다 우선해 오분류를 막는다. */
    private val exactRoleClasses = mapOf(
        "PRIMARY_GOAL" to "GOAL", "TARGET_ENTITY" to "TARGET",
        "DISTRACTOR_ENTITY" to "DISTRACTOR", "STABLE_VALUE" to "STABLE",
        "ONE_OFF_VALUE" to "ONE_OFF", "EPHEMERAL_VALUE" to "ONE_OFF",
        "CURRENT_AUTHORITY" to "AUTHORITY", "REVOKED_SCOPE" to "REVOKED_SCOPE",
        "PRESERVED_SCOPE" to "SCOPE", "SCOPE_GRANTED" to "SCOPE",
        "DELAYED_OUTCOME" to "OUTCOME", "REPLAY_OF_EVENT_ID" to "REPLAY",
        "TERM_VALUE" to "TERM", "VALID_UNTIL" to "UNTIL",
        "OLDER_EVENT_ID" to "OLDER_EVENT", "LINK_STATE" to "NETWORK",
    )

    /** role 키를 의미 클래스로 정규화한다 — 정확 일치 우선, 미지 표현은 substring/
     *  동의어 토큰 폴백(PERMISSION↔scope, DIRECTIVE↔authority, MAIN/SUBJECT 토큰↔target —
     *  특정 pack이 아닌 일반 의미 동의어만 추가한다). */
    private fun roleClass(key: String): String {
        val k = key.uppercase()
        exactRoleClasses[k]?.let { return it }
        val tokens = k.split('_')
        return when {
            "DISTRACTOR" in k -> "DISTRACTOR"
            "ENTITY" in k || "TARGET" in k || "MAIN" in tokens || "SUBJECT" in tokens -> "TARGET"
            "GOAL" in k -> "GOAL"
            "AUTHORITY" in k || "DIRECTIVE" in k -> "AUTHORITY"
            "UNTIL" in k || "EXPIR" in k || "DEADLINE" in k -> "UNTIL"
            "INFER" in k -> "INFERRED"
            "TERM" in tokens -> "TERM"
            "ONE_OFF" in k || "ONCE" in k || "EPHEMERAL" in k -> "ONE_OFF"
            "STABLE" in k || "PREFER" in k -> "STABLE"
            "REVOKE" in k -> "REVOKED_SCOPE"
            "PRESERVE" in k || "SCOPE" in k || "PERMISSION" in k -> "SCOPE"
            "OUTCOME" in k || "DELAY" in k -> "OUTCOME"
            "REPLAY" in k -> "REPLAY"
            "NETWORK" in k -> "NETWORK"
            "OLDER" in k -> "OLDER_EVENT"
            else -> "OTHER"
        }
    }

    private fun classifyRoles(roles: JSONObject): Map<String, MutableList<Pair<String, String>>> {
        val out = mutableMapOf<String, MutableList<Pair<String, String>>>()
        roles.keys().asSequence().sorted().forEach { key ->
            if (!roles.isNull(key)) {
                out.getOrPut(roleClass(key)) { mutableListOf() }.add(key to roles.get(key).toString())
            }
        }
        return out
    }

    private fun registerEntities(state: JSONObject, roles: JSONObject) {
        val classes = classifyRoles(roles)
        val entities = state.getJSONObject("entities")
        classes["TARGET"]?.firstOrNull()?.let { entities.put("target", it.second) }
        classes["GOAL"]?.firstOrNull()?.let { entities.put("goal", it.second) }
        classes["DISTRACTOR"]?.forEach { (_, v) ->
            appendUnique(entities.getJSONArray("distractors"), v)
        }
    }

    private fun upsertFromRoles(
        state: JSONObject,
        step: ProbeInputStep,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        registerEntities(state, step.roles)
        // 정식 인수: 학습이 이 entity를 명시적 TARGET으로 지정하면 distractor 격리를 해제한다
        // (결정 요청만으로는 해제되지 않음 — ABSTAIN 게이트는 registerEntities 이전에 판정).
        classifyRoles(step.roles)["TARGET"]?.firstOrNull()?.second?.let { adopted ->
            val distractors = state.getJSONObject("entities").getJSONArray("distractors")
            val kept = JSONArray()
            for (i in 0 until distractors.length()) {
                if (distractors.getString(i) != adopted) kept.put(distractors.getString(i))
            }
            state.getJSONObject("entities").put("distractors", kept)
        }
        val classes = classifyRoles(step.roles)
        val memories = state.getJSONObject("memories")
        val target = state.getJSONObject("entities").optString("target", "")
        // 권위 스탬프는 step이 권위를 명시 선언했을 때만 찍는다. 미선언 값은 권위 중립("") —
        // 권위 정정의 무효화 범위(비어있지 않은 구권위만)와 대칭이라, 예보와 무관하게 학습된
        // 기억이 예보 정정에 휩쓸리지 않는다.
        val authority = classes["AUTHORITY"]?.firstOrNull()?.second ?: ""
        if (classes["AUTHORITY"] != null) {
            state.getJSONObject("authorities").put(target, authority)
        }
        val scope = classes["SCOPE"]?.firstOrNull()?.second ?: "DEFAULT"
        // 재허용 의미론: scope를 '부여'하는 명시적 학습(GRANT 계열 키)만 철회 목록에서 그
        // scope를 제거한다 — 상태 기술 role이 우발적으로 재허용을 트리거하지 않게 한정.
        val grantedScope = classes["SCOPE"]?.firstOrNull { (k, _) -> "GRANT" in k.uppercase() }?.second
        if (grantedScope != null) {
            val revoked = state.getJSONArray("revoked_scopes")
            val keptScopes = JSONArray()
            for (i in 0 until revoked.length()) {
                if (revoked.getString(i) != grantedScope) keptScopes.put(revoked.getString(i))
            }
            state.put("revoked_scopes", keptScopes)
        }

        val validUntil = classes["UNTIL"]?.firstOrNull()?.second ?: ""

        fun put(lifetime: String, roleKey: String, value: String) {
            val id = "mem-" + safeId(target) + "-" + safeId(roleKey)
            // 의미론: 삭제 뒤에도 사용자의 명시적 재학습(새 출처의 정규 UPSERT)은 새 인스턴스
            // 생성으로 허용한다. tombstone은 유지되고, 시스템 경로(out-of-order 재전달)의
            // 부활만 reconcile 게이트가 차단한다.
            val old = memories.optJSONObject(id)
            if (old != null && old.getString("status") == "ACTIVE") invalidated += id
            memories.put(
                id,
                JSONObject()
                    .put("type", if (lifetime == "ONE_OFF" || lifetime == "TERM") "EPHEMERAL" else "FACT")
                    .put("lifetime", lifetime)
                    .put("value", value)
                    .put("authority", authority)
                    .put("scope", scope)
                    .put("entity", target)
                    .put("origin_event", step.eventId)
                    .put("valid_from", step.virtualTime)
                    .apply { if (validUntil.isNotEmpty()) put("valid_until", validUntil) }
                    .put("status", "ACTIVE"),
            )
            preserved += id
        }

        classes["STABLE"]?.forEach { (k, v) -> put("STABLE", k, v) }
        classes["ONE_OFF"]?.forEach { (k, v) -> put("ONE_OFF", k, v) }
        // 기한형(TERM): 기한 내에는 세션마다 재사용되고(소모되지 않음) 기한 경과 시 만료된다.
        classes["TERM"]?.forEach { (k, v) -> put("TERM", k, v) }
        // 선언된 파생 추론(INFERRED): INFERENCE 유형으로 기록하고, 같은 entity의 확인 기록
        // 기억을 출처(derived_from)로 잇는다 — 근거가 무효화되면 descendant로 함께 무효화.
        classes["INFERRED"]?.forEach { (k, v) ->
            val id = "inf-" + safeId(target) + "-" + safeId(k)
            // 근거는 원 FACT만 — 추론이 추론을 근거로 삼는 사슬 금지 (decide의 stableBasis와 동일 기준).
            val basis = memories.keys().asSequence().sorted().firstOrNull {
                val cand = memories.getJSONObject(it)
                cand.optString("entity") == target &&
                    cand.optString("type") == "FACT" &&
                    cand.optString("status") == "ACTIVE" &&
                    it.contains("CONFIRM", ignoreCase = true)
            }
            memories.put(
                id,
                JSONObject()
                    .put("type", "INFERENCE").put("lifetime", "STABLE")
                    .put("value", v)
                    .put("authority", "")
                    .put("scope", scope)
                    .put("entity", target)
                    .put("origin_event", step.eventId)
                    .apply { if (basis != null) put("derived_from", basis) }
                    .put("valid_from", step.virtualTime)
                    .put("status", "ACTIVE"),
            )
            preserved += id
        }
        // 그 외 잔여 값 role은 STABLE로 보수적 저장 (GOAL/TARGET/AUTHORITY/SCOPE 제외)
        classes["OTHER"]?.forEach { (k, v) -> put("STABLE", k, v) }

        // 선언된 지연 결과: UPSERT가 OUTCOME 클래스 role을 실어오면 대기열에 등록해
        // 이후 가상시간 경과 시 도착하게 한다 (결정 시 등록 경로와 병행, outcome_id 중복 방지).
        classes["OUTCOME"]?.forEach { (_, v) ->
            val outcomeId = "outcome-decl-" + safeId(v)
            val pendingOutcomes = state.getJSONArray("pending_outcomes")
            val exists = (0 until pendingOutcomes.length()).any {
                pendingOutcomes.getJSONObject(it).getString("outcome_id") == outcomeId
            }
            if (!exists) {
                pendingOutcomes.put(
                    JSONObject()
                        .put("outcome_id", outcomeId)
                        .put("requested_at", step.virtualTime)
                        .put("entity", target),
                )
            }
        }

        // 변화 없는 기존 ACTIVE 기억은 보존 목록에 명시
        activeIds(memories).forEach { if (it !in invalidated) preserved += it }
    }

    // ------------------------------------------------------------------
    // E2 Reuse — 재사용 게이트 (REQUEST_DECISION)
    // ------------------------------------------------------------------

    /** 유효성 판정: VG-Ledger의 단일 게이트. 모든 재사용·충돌·복구가 이 판정을 공유한다. */
    private fun isValid(state: JSONObject, item: JSONObject): Boolean {
        if (!gateEnabled) {
            // claim-off: 권위·scope·소모 판정 없음 — 살아있는(ACTIVE) 항목은 통짜 재사용.
            // 일회성은 소모되지 않으므로 유령 항목이 재등장하고, 충돌 시엔 전체 무효화로만
            // 반응해(과잉 복구) 다음 프리필이 0이 된다(전체 재확인 부담).
            return item.getString("status") == "ACTIVE"
        }
        if (item.getString("status") != "ACTIVE") return false
        // 권위 게이트(약 단위): 그 entity의 현재 권위가 확정된 뒤에는 더 오래된 권위의
        // 기억을 재사용하지 않는다. 다른 entity의 정정은 이 항목에 영향을 주지 않는다.
        val current = authorityFor(state, item.optString("entity"))
        if (current.isNotEmpty() && item.getString("authority").isNotEmpty() &&
            item.getString("authority") != current
        ) return false
        // 기한 게이트: 유효기한이 기록된 기억(기한형 TERM 등)은 가상시간이 기한을 지나면
        // 만료되어 재사용되지 않는다 (ISO 문자열 비교 — 결정론적).
        val until = item.optString("valid_until", "")
        if (until.isNotEmpty() && until < state.optString("virtual_time", "")) return false
        // 범위 게이트: 철회된 scope의 기억은 무효.
        val revoked = state.getJSONArray("revoked_scopes")
        for (i in 0 until revoked.length()) {
            if (item.optString("scope") == revoked.getString(i)) return false
        }
        return true
    }

    private fun decide(
        state: JSONObject,
        step: ProbeInputStep,
        selected: MutableSet<String>,
    ): Pair<String, JSONObject?> {
        val memories = state.getJSONObject("memories")
        val entities = state.getJSONObject("entities")
        // 잘못된 대상 보류: 요청된 대상이 알려진 distractor entity면 상태(현재 목표 target 포함)를
        // 오염시키지 않은 채 행동을 삼간다(ABSTAIN) — 이후 연산은 기존 목표를 계속 본다.
        val requestedTarget = classifyRoles(step.roles)["TARGET"]?.firstOrNull()?.second
        val knownDistractors = strings(entities.getJSONArray("distractors")).toSet()
        if (gateEnabled && requestedTarget != null && requestedTarget in knownDistractors) {
            return "ABSTAIN" to null
        }
        registerEntities(state, step.roles)
        val target = entities.optString("target", "")

        // 정직한 degradation: 네트워크 불능 시 낙관적 실행 대신 WAIT.
        // 대기한 이벤트를 기록해 두면, 같은 이벤트가 재전달될 때 조건 회복 후
        // 정확히 한 번만 재시도할 수 있다 (exactly-once 유지).
        if (state.getString("network") != "ONLINE") {
            // 보류 이벤트의 원본 roles를 저장해, 재전달 시 그 시점의 target·근거로 재시도한다.
            state.getJSONObject("waiting_events")
                .put(step.eventId, JSONObject(step.roles.toString()))
            return "WAIT" to null
        }

        // 재사용 게이트: 현재 목표·대상에 연결된 유효 기억만 최소 context로 선택.
        // distractor entity에 연결된 기억은 선택하지 않는다 (CORE-1).
        val distractors = strings(entities.getJSONArray("distractors")).toSet()
        memories.keys().asSequence().sorted().forEach { id ->
            val m = memories.getJSONObject(id)
            if (isValid(state, m) && m.optString("entity") == target && m.optString("entity") !in distractors) {
                selected += id
            }
        }

        if (target.isEmpty() || selected.isEmpty()) return "ASK" to null

        // ONE_OFF 소모: 결정에 사용된 일회성 기억은 첫 사용 후 CONSUMED로 전이해
        // 다음 결정에 재등장하지 않는다 (유령 항목 차단).
        if (gateEnabled) {
            selected.forEach { id ->
                val m = memories.getJSONObject(id)
                if (m.getString("lifetime") == "ONE_OFF") {
                    m.put("status", "CONSUMED").put("consumed_by", step.eventId)
                }
            }
        }

        val action = actionFor(state, step)

        // 사용 기록에서 추론 파생: 이번 결정에 쓰인 STABLE 기억을 근거로
        // "최근 성공 방식" INFERENCE를 기록한다 (derived_from 출처 링크).
        // 이 파생 항목은 근거가 정정·철회되면 descendant로 함께 무효화된다 (CORE-2).
        // 파생 근거는 원 FACT만: INFERENCE를 근거로 삼으면 derived_from이 자기참조가 되어
        // 출처 사슬이 끊긴다.
        val stableBasis = selected.firstOrNull {
            val m = memories.getJSONObject(it)
            m.getString("lifetime") == "STABLE" && m.getString("type") == "FACT"
        }
        if (stableBasis != null) {
            val infId = "inf-" + safeId(target) + "-recent_success"
            memories.put(
                infId,
                JSONObject()
                    .put("type", "INFERENCE")
                    .put("lifetime", "STABLE")
                    .put("value", "reuse_of:" + stableBasis)
                    .put("authority", authorityFor(state, target))
                    .put("scope", memories.getJSONObject(stableBasis).optString("scope", "DEFAULT"))
                    .put("entity", target)
                    .put("origin_event", step.eventId)
                    .put("derived_from", stableBasis)
                    .put("valid_from", step.virtualTime)
                    .put("status", "ACTIVE"),
            )
        }
        return "ACT" to action
    }

    private fun actionFor(state: JSONObject, step: ProbeInputStep): JSONObject {
        val actions = state.getJSONObject("actions")
        val existing = actions.optJSONObject(step.eventId)
        if (existing != null) return JSONObject(CanonicalJson.encode(existing))
        val suffix = safeId(step.eventId)
        val outcomeId = "outcome-$suffix"
        // 2단계 생명주기: 결정 시점은 PROPOSED(draft), 지연 결과 도착이 commit 경계.
        // kill이 그 사이에 와도 완료로 위장되지 않는다 (G3 상태 정직성).
        val action = JSONObject()
            .put("action_id", "action-$suffix")
            .put("idempotency_key", "idem-$suffix")
            .put("commit_state", "PROPOSED")
            .put("outcome_id", outcomeId)
        actions.put(step.eventId, JSONObject(CanonicalJson.encode(action)))
        // 지연 결과 대기열 등록: 결과는 이후 가상시간 경과 시 도착한다 (CORE-6).
        state.getJSONArray("pending_outcomes").put(
            JSONObject()
                .put("outcome_id", outcomeId)
                .put("requested_at", step.virtualTime)
                .put("entity", state.getJSONObject("entities").optString("target", "")),
        )
        return action
    }

    // ------------------------------------------------------------------
    // E3 Exception — 충돌 게이트 (CORRECT / REVOKE)
    // ------------------------------------------------------------------

    private fun correctFromRoles(
        state: JSONObject,
        step: ProbeInputStep,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        registerEntities(state, step.roles)
        val classes = classifyRoles(step.roles)
        val memories = state.getJSONObject("memories")
        val target = state.getJSONObject("entities").optString("target", "")
        val newAuthority = classes["AUTHORITY"]?.firstOrNull()?.second

        if (!gateEnabled) {
            // claim-off 폴백: 영향 특정 불가 → 대상 entity의 기억 전체 재작성(과잉 복구).
            if (newAuthority != null) state.getJSONObject("authorities").put(target, newAuthority)
            memories.keys().asSequence().sorted().forEach { id ->
                val m = memories.getJSONObject(id)
                if (m.optString("entity") == target && m.getString("status") == "ACTIVE") {
                    m.put("status", "INVALIDATED").put("invalidated_by", step.eventId)
                    invalidated += id
                }
            }
            upsertCorrection(state, step, classes, invalidated, preserved)
            return
        }
        // 권위 정정: 새 권위 확정 → 대상 entity의 구권위 기억 + 파생 추론을 무효화(descendant).
        if (newAuthority != null) {
            state.getJSONObject("authorities").put(target, newAuthority)
            memories.keys().asSequence().sorted().forEach { id ->
                val m = memories.getJSONObject(id)
                // 권위 중립(authority 미기재) 기억은 예보 버전과 무관하므로 정정의 영향 밖이다
                // — 재사용 게이트(isValid)의 권위 판정과 같은 기준 (영향 특정 정확도).
                if (m.getString("status") == "ACTIVE" && m.optString("entity") == target &&
                    m.getString("authority").isNotEmpty() && m.getString("authority") != newAuthority
                ) {
                    m.put("status", "INVALIDATED").put("invalidated_by", step.eventId)
                    invalidated += id
                    invalidateDescendants(memories, id, step.eventId, invalidated)
                }
            }
        }
        // 정정된 새 값 기록
        upsertCorrection(state, step, classes, invalidated, preserved)
        activeIds(memories).forEach { if (it !in invalidated) preserved += it }
    }

    private fun upsertCorrection(
        state: JSONObject,
        step: ProbeInputStep,
        classes: Map<String, MutableList<Pair<String, String>>>,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        val memories = state.getJSONObject("memories")
        val target = state.getJSONObject("entities").optString("target", "")
        val authority = authorityFor(state, target)
        // 정정값은 실어온 모든 값 클래스(기한형 포함)를 기록한다 — 빠진 클래스는 조용한 유실이 된다.
        val corrected = classes["ONE_OFF"].orEmpty() + classes["TERM"].orEmpty() +
            classes["STABLE"].orEmpty() + classes["OTHER"].orEmpty()
        val correctedUntil = classes["UNTIL"]?.firstOrNull()?.second ?: ""
        if (corrected.isEmpty() && correctedUntil.isNotEmpty()) {
            // 기한만의 정정(연장·단축): 대상 entity의 기한 보유 기억을 새 기한으로 재기록.
            // 같은 step의 권위 정정으로 방금 무효화된 기억도 인수 대상이다 — '새 지시로 기한
            // 변경'이 값 대체 없이 오면 기존 값을 새 권위·새 기한으로 재기록해 조용한 유실을 막는다.
            var touched = false
            memories.keys().asSequence().sorted().toList().forEach { id ->
                val m = memories.getJSONObject(id)
                // 게이트 팔에서만 '같은 step 권위 무효화분 인수'를 허용한다 — claim-off의
                // 전면 무효화(과잉 복구)까지 정밀 복원하면 A/B 대비가 무의미해진다.
                val justInvalidated = gateEnabled && m.getString("status") == "INVALIDATED" &&
                    m.optString("invalidated_by") == step.eventId
                if (m.optString("entity") == target &&
                    (m.getString("status") == "ACTIVE" || justInvalidated) &&
                    m.optString("valid_until").isNotEmpty()
                ) {
                    invalidated += id
                    touched = true
                    val copy = JSONObject(CanonicalJson.encode(m))
                        .put("valid_until", correctedUntil)
                        .put("origin_event", step.eventId)
                        .put("valid_from", step.virtualTime)
                        .put("status", "ACTIVE")
                    if (justInvalidated) {
                        copy.put("authority", authority)
                        copy.remove("invalidated_by")
                    }
                    memories.put(id, copy)
                }
            }
            // 기한 보유 기억이 전무하면: 기한 없이 학습된 TERM(만료 불능)에 새 기한을 신규
            // 부여한다 — 기한형이 기한 없는 채 영구 재사용되는 침묵 no-op을 막는다.
            if (!touched) {
                memories.keys().asSequence().sorted().toList().forEach { id ->
                    val m = memories.getJSONObject(id)
                    if (m.optString("entity") == target && m.getString("status") == "ACTIVE" &&
                        m.getString("lifetime") == "TERM" && m.optString("valid_until").isEmpty()
                    ) {
                        invalidated += id
                        memories.put(
                            id,
                            JSONObject(CanonicalJson.encode(m))
                                .put("valid_until", correctedUntil)
                                .put("origin_event", step.eventId)
                                .put("valid_from", step.virtualTime),
                        )
                    }
                }
            }
            return
        }
        corrected.forEach { (k, v) ->
            val id = "mem-" + safeId(target) + "-" + safeId(k)
            // 동일 id의 기존 ACTIVE 인스턴스 교체는 무효화로 게시한다 (UPSERT 덮어쓰기와 대칭).
            val oldInstance = memories.optJSONObject(id)
            if (oldInstance != null && oldInstance.getString("status") == "ACTIVE") invalidated += id
            val cls = roleClass(k)
            val lifetime = when (cls) { "ONE_OFF" -> "ONE_OFF"; "TERM" -> "TERM"; else -> "STABLE" }
            memories.put(
                id,
                JSONObject()
                    .put("type", if (lifetime == "ONE_OFF" || lifetime == "TERM") "EPHEMERAL" else "FACT")
                    .put("lifetime", lifetime)
                    .put("value", v)
                    .put("authority", authority)
                    // scope 승계: 정정이 scope를 새로 지정하지 않으면 구 인스턴스의 scope를 잇는다
                    // — 정정된 처방이 그 약의 중단 scope에서 이탈하지 않게(복구 게이트 정합).
                    .put(
                        "scope",
                        classes["SCOPE"]?.firstOrNull()?.second
                            ?: oldInstance?.optString("scope")?.takeIf { it.isNotEmpty() }
                            ?: "DEFAULT",
                    )
                    .put("entity", target)
                    .put("origin_event", step.eventId)
                    .put("valid_from", step.virtualTime)
                    .apply {
                        val until = correctedUntil.ifEmpty { oldInstance?.optString("valid_until").orEmpty() }
                        if (until.isNotEmpty()) put("valid_until", until)
                    }
                    .put("status", "ACTIVE"),
            )
            preserved += id
        }
    }

    private fun revokeScope(
        state: JSONObject,
        step: ProbeInputStep,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        val classes = classifyRoles(step.roles)
        val memories = state.getJSONObject("memories")
        val revokedScope = classes["REVOKED_SCOPE"]?.firstOrNull()?.second
        if (revokedScope == null) {
            // 철회 대상 미지정: 아무것도 무효화하지 않되 유지 항목은 관례대로 명시한다.
            activeIds(memories).forEach(preserved::add)
            return
        }
        appendUnique(state.getJSONArray("revoked_scopes"), revokedScope)
        if (!gateEnabled) {
            // claim-off 폴백: 영향 특정 불가 → 전체 재확인(모든 항목 무효화, 과잉 정지).
            memories.keys().asSequence().sorted().forEach { id ->
                val m = memories.getJSONObject(id)
                if (m.getString("status") == "ACTIVE") {
                    m.put("status", "INVALIDATED").put("invalidated_by", step.eventId)
                    invalidated += id
                }
            }
            return
        }
        // 충돌 게이트: 철회 scope에 걸린 기억만 정밀 특정해 무효화, 나머지는 보존.
        val affectedEntities = mutableSetOf<String>()
        memories.keys().asSequence().sorted().forEach { id ->
            val m = memories.getJSONObject(id)
            val scopeHit = m.optString("scope") == revokedScope
            if (m.getString("status") == "ACTIVE") {
                if (scopeHit) {
                    m.put("status", "INVALIDATED").put("invalidated_by", step.eventId)
                    invalidated += id
                    affectedEntities += m.optString("entity")
                    invalidateDescendants(memories, id, step.eventId, invalidated)
                } else {
                    preserved += id
                }
            } else if (scopeHit && m.getString("status") == "CONSUMED") {
                // 이미 소모된 일회성도 그 scope에서 개시된 in-flight 결과의 취소 근거가 된다
                // — 무효화 게시는 없지만(이미 비활성) 대기 취소 대상 entity에는 포함(STABLE과 대칭).
                affectedEntities += m.optString("entity")
            }
        }
        // 영향 특정 복구: 철회로 무효화된 entity의 '아직 도착하지 않은' 대기 결과를 취소한다
        // (중단된 약의 리필이 나중에 도착해 잔량·프리필을 오염시키지 않게). 취소는 원장에 기재.
        if (affectedEntities.isNotEmpty()) {
            val pending = state.getJSONArray("pending_outcomes")
            val kept = JSONArray()
            for (i in 0 until pending.length()) {
                val entry = pending.getJSONObject(i)
                if (entry.optString("entity") in affectedEntities) {
                    appendUnique(
                        state.getJSONArray("reconciled_events"),
                        "CANCELLED_PENDING:" + entry.getString("outcome_id") + ":" + step.eventId,
                    )
                    // 취소된 대기의 행동은 CANCELLED로 종결 — 미완(PROPOSED) 영구 잔류 방지.
                    val actions = state.getJSONObject("actions")
                    actions.keys().asSequence().sorted().forEach { evId ->
                        val a = actions.getJSONObject(evId)
                        if (a.getString("outcome_id") == entry.getString("outcome_id") &&
                            a.getString("commit_state") == "PROPOSED"
                        ) {
                            a.put("commit_state", "CANCELLED")
                        }
                    }
                } else {
                    kept.put(entry)
                }
            }
            state.put("pending_outcomes", kept)
        }
    }

    /** 복구 게이트의 파생 무효화: origin/provenance가 무효 항목을 가리키는 추론을 함께 무효화.
     *  전이적(worklist)으로 닫는다 — 다단 파생 사슬의 말단이 게이트를 빠져나가지 않게. */
    private fun invalidateDescendants(
        memories: JSONObject,
        parentId: String,
        eventId: String,
        invalidated: MutableSet<String>,
    ) {
        val worklist = ArrayDeque(listOf(parentId))
        while (worklist.isNotEmpty()) {
            val parent = worklist.removeFirst()
            memories.keys().asSequence().sorted().forEach { id ->
                val m = memories.getJSONObject(id)
                if (m.getString("status") == "ACTIVE" && m.optString("derived_from") == parent) {
                    m.put("status", "INVALIDATED").put("invalidated_by", eventId)
                    invalidated += id
                    worklist.addLast(id)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 삭제·역순·지연 — tombstone과 reconcile
    // ------------------------------------------------------------------

    private fun deleteFact(
        state: JSONObject,
        step: ProbeInputStep,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        registerEntities(state, step.roles)
        val classes = classifyRoles(step.roles)
        val memories = state.getJSONObject("memories")
        val target = state.getJSONObject("entities").optString("target", "")
        // 삭제 범위: 값 role(일회성·민감값 등)이 명시되면 그 값의 기억만 삭제하고 entity의
        // 다른 유효 기억은 보존한다. 값 role이 없으면 entity 전체 삭제(공개 13-step 형태).
        val valueSet = (classes["ONE_OFF"].orEmpty() + classes["TERM"].orEmpty() +
            classes["STABLE"].orEmpty() + classes["OTHER"].orEmpty())
            .map { it.second }.toSet()
        memories.keys().asSequence().sorted().toList().forEach { id ->
            val m = memories.getJSONObject(id)
            val hit = m.optString("entity") == target &&
                (valueSet.isEmpty() || m.optString("value") in valueSet)
            if (hit) {
                // 원문은 제거하고 tombstone에는 값 없는 ID만 남긴다 (값 복원 불가).
                memories.remove(id)
                invalidated += id
                appendUnique(state.getJSONArray("tombstones"), id)
                // 삭제된 근거의 파생 추론도 함께 무효화 — CORRECT/REVOKE의 복구 게이트와
                // 같은 출처 역추적을 값 지정 삭제에도 적용한다 (근거 사멸 후 재사용 차단).
                invalidateDescendants(memories, id, step.eventId, invalidated)
            } else if (m.getString("status") == "ACTIVE") {
                preserved += id
            }
        }
        if (valueSet.isEmpty()) {
            appendUnique(state.getJSONArray("tombstones"), "tombstone-" + safeId(target))
            // 삭제된 entity의 미도착 대기·미완 행동을 취소 — 삭제 약의 결과가 나중에
            // 도착해 새 기억을 만들거나 영구 PENDING을 남기지 않게(REVOKE와 대칭).
            val pendingQ = state.getJSONArray("pending_outcomes")
            val keptQ = JSONArray()
            for (i in 0 until pendingQ.length()) {
                val entry = pendingQ.getJSONObject(i)
                if (entry.optString("entity") == target) {
                    appendUnique(
                        state.getJSONArray("reconciled_events"),
                        "CANCELLED_PENDING:" + entry.getString("outcome_id") + ":" + step.eventId,
                    )
                    val actions = state.getJSONObject("actions")
                    actions.keys().asSequence().sorted().forEach { evId ->
                        val a = actions.getJSONObject(evId)
                        if (a.getString("outcome_id") == entry.getString("outcome_id") &&
                            a.getString("commit_state") == "PROPOSED"
                        ) {
                            a.put("commit_state", "CANCELLED")
                        }
                    }
                } else {
                    keptQ.put(entry)
                }
            }
            state.put("pending_outcomes", keptQ)
        }
    }

    private fun reconcileOutOfOrder(
        state: JSONObject,
        step: ProbeInputStep,
        invalidated: MutableSet<String>,
        preserved: MutableSet<String>,
    ) {
        // 늦게 도착한 과거 이벤트 reconcile: 실어온 권위가 현재 권위보다 오래됐거나
        // 대상이 tombstone이면 적용을 명시적으로 기각한다 — 상태 무변형, 부활 없음.
        val classes = classifyRoles(step.roles)
        val carried = classes["AUTHORITY"]?.firstOrNull()?.second
        // 판정 기준 entity: 이 이벤트가 실어온 TARGET(전역 target을 오염시키지 않는 로컬 판독),
        // 없으면 현재 target — 기각 '사유'가 교차 entity 상황에서도 정확하게 기록되도록.
        val targetEntity = classes["TARGET"]?.firstOrNull()?.second
            ?: state.getJSONObject("entities").optString("target", "")
        val current = authorityFor(state, targetEntity)
        val targetTombstoned = targetEntity.isNotEmpty() &&
            strings(state.getJSONArray("tombstones")).contains("tombstone-" + safeId(targetEntity))
        val verdict = when {
            targetTombstoned -> "REJECTED_TOMBSTONED"
            carried != null && current.isNotEmpty() && carried != current -> "REJECTED_STALE_AUTHORITY"
            else -> "REJECTED_OUT_OF_ORDER"
        }
        activeIds(state.getJSONObject("memories")).forEach(preserved::add)
        appendUnique(
            state.getJSONArray("reconciled_events"),
            verdict + ":" + (roleValue(step.roles, listOf("OLDER_EVENT_ID", "OLDER", "OUT_OF_ORDER", "ORIGINAL")) ?: step.eventId),
        )
    }

    private fun arriveDueOutcomes(state: JSONObject, step: ProbeInputStep): Int {
        val pending = state.getJSONArray("pending_outcomes")
        val ledger = state.getJSONArray("outcome_ledger")
        var arrived = 0
        val actions = state.getJSONObject("actions")
        // 도착 판정: 요청 시각이 현재 가상시간보다 과거인 결과만 도착한다
        // (ISO-8601 문자열 비교 — 결정론적. 미도래 항목은 대기열에 남는다).
        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val entry = pending.getJSONObject(i)
            if (entry.getString("requested_at") >= step.virtualTime) {
                remaining.put(entry)
                continue
            }
            val outcomeId = entry.getString("outcome_id")
            ledger.put(
                JSONObject()
                    .put("outcome_id", outcomeId)
                    .put("requested_at", entry.getString("requested_at"))
                    .put("arrived_at", step.virtualTime)
                    .put("arrived_event", step.eventId),
            )
            // 도착한 결과를 기억 원장에 FACT로 기록: 다음 REQUEST_DECISION의
            // selected_context가 도착 전과 달라진다 (늦은 결과가 다음 판단을 바꿈, CORE-6).
            // 귀속은 '등록 시점에 기록된 entity' — 도착 시점의 전역 target이 아니다.
            val target = entry.optString("entity")
            // tombstone 별도 기각은 두지 않는다: entity 전체 삭제가 그 시점의 대기를 전부
            // 취소하므로, tombstone과 공존하는 대기는 반드시 그 이후의 명시적 재학습·재선언
            // 사이클에서 등록된 정당한 결과다 (삭제 원문의 부활은 reconcile 게이트가 담당).
            // 귀속 entity가 없으면(구형 항목) 원장 행만 남기고 기억 생성은 하지 않는다 —
            // 도착 시점의 전역 target으로 오귀속하지 않는 것이 원칙.
            if (target.isNotEmpty()) {
                state.getJSONObject("memories").put(
                    "mem-" + safeId(target) + "-arrival_" + safeId(outcomeId) + "_" + safeId(step.eventId),
                    JSONObject()
                        .put("type", "FACT").put("lifetime", "STABLE")
                        .put("value", "outcome_arrived")
                        // 도착 '사실'은 지시(권위)와 무관한 이벤트 기록 — 권위 중립으로 남겨
                        // 지시 정정이 도착 이력을 무효화하지 않게 한다.
                        .put("authority", "")
                        .put("scope", "DEFAULT").put("entity", target)
                        .put("origin_event", step.eventId)
                        .put("valid_from", step.virtualTime)
                        .put("status", "ACTIVE"),
                )
            }
            // 결과 도착 = commit 경계: 해당 PROPOSED action을 COMMITTED로 승격.
            actions.keys().asSequence().sorted().forEach { evId ->
                val a = actions.getJSONObject(evId)
                if (a.getString("outcome_id") == outcomeId && a.getString("commit_state") == "PROPOSED") {
                    a.put("commit_state", "COMMITTED")
                }
            }
            arrived++
        }
        state.put("pending_outcomes", remaining)
        if (arrived > 0) state.put("last_outcome_arrival", step.virtualTime)
        return arrived
    }

    // ------------------------------------------------------------------
    // 공통 유틸
    // ------------------------------------------------------------------

    private fun resetAll(state: JSONObject) {
        state.put("memories", JSONObject())
        state.put("actions", JSONObject())
        state.put("entities", JSONObject().put("distractors", JSONArray()))
        state.put("tombstones", JSONArray())
        state.put("receipts", JSONArray())
        state.put("evidence", JSONArray())
        state.put("revoked_scopes", JSONArray())
        state.put("pending_outcomes", JSONArray())
        state.put("outcome_ledger", JSONArray())
        state.put("reconciled_events", JSONArray())
        state.put("waiting_events", JSONObject())
        state.put("network", "ONLINE")
        state.put("authorities", JSONObject())
        state.put("decision_request_count", 0)
        state.remove("last_outcome_arrival")
        // RESET은 관측 기저도 초기화한다: epoch 0 + marker 동기화 — RESET부터의
        // 해시·epoch가 기기·재시작 이력과 무관한 입력의 순수 함수가 된다.
        state.put("process_epoch", 0)
        state.put("process_marker", processMarker)
    }

    private fun ensureProcessEpoch(state: JSONObject) {
        val previous = state.optString("process_marker", "")
        if (previous != processMarker) {
            if (previous.isNotEmpty()) {
                state.put("process_epoch", state.getInt("process_epoch") + 1)
            }
            state.put("process_marker", processMarker)
        }
    }

    private fun normalized(source: JSONObject): JSONObject = source.apply {
        if (!has("memories")) put("memories", JSONObject())
        if (!has("actions")) put("actions", JSONObject())
        if (!has("entities")) put("entities", JSONObject().put("distractors", JSONArray()))
        if (!getJSONObject("entities").has("distractors")) {
            getJSONObject("entities").put("distractors", JSONArray())
        }
        if (!has("tombstones")) put("tombstones", JSONArray())
        if (!has("receipts")) put("receipts", JSONArray())
        if (!has("evidence")) put("evidence", JSONArray())
        if (!has("revoked_scopes")) put("revoked_scopes", JSONArray())
        if (!has("pending_outcomes")) put("pending_outcomes", JSONArray())
        if (!has("outcome_ledger")) put("outcome_ledger", JSONArray())
        if (!has("reconciled_events")) put("reconciled_events", JSONArray())
        if (!has("waiting_events")) put("waiting_events", JSONObject())
        if (!has("network")) put("network", "ONLINE")
        // 권위는 entity(약) 단위 map — 한 약의 지시 정정이 다른 약의 기억을 기각하지 않는다.
        if (!has("authorities")) put("authorities", JSONObject())
        if (!has("process_epoch")) put("process_epoch", 0)
        if (!has("process_marker")) put("process_marker", "")
        if (!has("decision_request_count")) put("decision_request_count", 0)
    }


    private fun pendingOrNone(state: JSONObject): String {
        val actions = state.getJSONObject("actions")
        val hasProposed = actions.keys().asSequence().any {
            actions.getJSONObject(it).optString("commit_state") == "PROPOSED"
        }
        // 미완(PROPOSED) 행동 또는 대기 중 결과가 있을 때만 PENDING — 전부 commit됐으면 대기 없음.
        return if (hasProposed || state.getJSONArray("pending_outcomes").length() > 0) "PENDING" else "NO_DECISION"
    }

    /** 상태 digest: process_marker는 재현 불가한 런타임 메타라 해시에서 제외한다 —
     *  같은 입력이면 기기·프로세스 이력과 무관하게 같은 해시 체인이 나온다(결정론 입증). */
    private fun digestOf(state: JSONObject): String {
        val copy = JSONObject(CanonicalJson.encode(state))
        copy.remove("process_marker")
        return Sha256.canonicalJson(copy)
    }

    /** entity(약) 단위 권위 조회 — 미기록 entity는 권위 중립. */
    private fun authorityFor(state: JSONObject, entity: String): String =
        state.getJSONObject("authorities").optString(entity, "")


    private fun activeIds(memories: JSONObject): List<String> =
        memories.keys().asSequence().sorted().filter {
            memories.getJSONObject(it).getString("status") == "ACTIVE"
        }.toList()

    private fun requestedNetwork(roles: JSONObject): String {
        val allowed = listOf("OFFLINE", "ONLINE", "DELAYED")
        val sortedKeys = roles.keys().asSequence().sorted().toList()
        fun valuesOf(keys: List<String>) =
            keys.mapNotNull { roles.opt(it)?.toString()?.uppercase() }
        val networkValues = valuesOf(sortedKeys.filter { roleClass(it) == "NETWORK" })
        val otherValues = valuesOf(sortedKeys.filter { roleClass(it) != "NETWORK" })
        // 정확 일치 우선 → NETWORK 클래스 role 값의 substring → 나머지 값의 substring.
        // (표현 가변 대비 폴백 — 복구 값을 못 읽어 WAIT에 고착되는 것이 최악의 실패.
        //  다른 role 값의 우발적 매칭이 NETWORK role 값을 이기지 못하게 순서를 고정한다.)
        (networkValues + otherValues).firstOrNull { it in allowed }?.let { return it }
        for (values in listOf(networkValues, otherValues)) {
            for (v in values) {
                allowed.firstOrNull { it in v }?.let { return it }
            }
        }
        return "UNKNOWN"
    }

    private fun roleValue(roles: JSONObject, keyHints: List<String>): String? {
        roles.keys().asSequence().sorted().forEach { key ->
            val k = key.uppercase()
            if (keyHints.any { it in k } && !roles.isNull(key)) return roles.get(key).toString()
        }
        return null
    }

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifEmpty { "value" }

    private fun appendUnique(array: JSONArray, value: String) {
        if (strings(array).none { it == value }) array.put(value)
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun copyArray(array: JSONArray): JSONArray = JSONArray(CanonicalJson.encode(array))
}
