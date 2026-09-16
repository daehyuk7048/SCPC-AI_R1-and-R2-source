package com.sdh.chebi

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.ProbeInputParser
import org.scpc.r2.probe.ProbeRunContext
import java.io.File

/**
 * 약지기 — 대혁의 진화형 복약 관리 (E1 학습 → E2 재사용 → E3 예외 → E4 복구).
 *
 * 의료 가드: 이 앱은 의료 조언·진단·복약 지시가 아니다. 기록된 의사 지시의 최신 버전을
 * 기억·대조해 표시할 뿐이며, 복용은 항상 사용자의 최종확인으로만 확정된다. 모든 데이터는
 * 완전 합성이다.
 *
 * 모든 사용자 행동은 MedDomain을 통해 합성 이벤트가 되어 Probe와 같은 production
 * core(VG-Ledger)로 들어간다. 점수·PASS/FAIL·anchor류는 계산·표시하지 않는다.
 */
class MainActivity : Activity() {

    private lateinit var domain: MedDomain

    private lateinit var stateLine: TextView
    private lateinit var checklistView: TextView
    private lateinit var lastEvent: TextView
    private lateinit var metricLine: TextView
    private lateinit var ledgerView: TextView
    private lateinit var comparisonView: TextView
    private lateinit var probeStatus: TextView
    private lateinit var recordBtn: Button
    private lateinit var finalCheck: CheckBox
    private lateinit var notifyBtn: Button
    private lateinit var permissionBtn: Button

    private var sessionActive = false
    private var lastPrefill = 0
    private var lastManual = 0
    private var lastSessionDecision = "NO_DECISION"

    /** 방금 누른 버튼의 짧은 이름 — 모든 결과 알림 앞에 붙어 어떤 기능이 실행됐는지 밝힌다. */
    private var currentAction: String = ""

    /** 토스트 단일 슬롯: 연타 시 이전 토스트를 취소하고 최신 결과만 보여준다(큐 적체 방지). */
    private var activeToast: Toast? = null

    private fun showToast(text: String) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** 버튼 라벨에서 괄호·설명을 뗀 동작 이름 ("혈압약 처방 등록 (지시 v1…) — 상시" → "혈압약 처방 등록"). */
    private fun shortAction(label: String): String =
        label.substringBefore(" (").substringBefore(" —").trim()

    private fun attributed(value: String): String =
        if (currentAction.isEmpty()) value else "▶ $currentAction: $value"

    /** 행동 결과 알림: 상단 줄과 토스트에 동시 표시 — 스크롤 위치와 무관한 즉시 피드백.
     *  누른 버튼 이름이 항상 앞에 붙어 결과의 출처가 화면에서 헷갈리지 않는다. */
    private var lastEventMessage: String = ""
        set(value) {
            field = value
            lastEvent.text = attributed(value)
            if (value.isNotEmpty()) showToast(attributed(value))
        }

    private var probeStatusMessage: String = ""
        set(value) {
            field = value
            probeStatus.text = attributed(value)
            if (value.isNotEmpty()) showToast(attributed(value))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        domain = MedDomain(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
        }

        root.addView(caption("약지기 — 대혁의 복약 관리", 19f, bold = true))
        root.addView(caption("본 앱은 의료 조언이 아닌 완전 합성 시뮬레이션입니다. 복용은 항상 사용자의 최종확인으로만 확정됩니다.", 10f))
        stateLine = caption("", 12f).also(root::addView)
        // 최근 이벤트는 강조 패널: 버튼과 대등한 시인성으로 '방금 무슨 일이 일어났는지'가 보인다.
        lastEvent = caption("최근 이벤트: (없음)", 13f, bold = true).apply {
            setBackgroundColor(0xFFFFF3D6.toInt())
            setTextColor(0xFF5A4500.toInt())
            setPadding(20, 16, 20, 16)
        }.also(root::addView)

        // ---- E2 오늘의 복용 세션 ---------------------------------------
        root.addView(caption("오늘의 복용 세션 (E2 재사용)", 15f, bold = true))
        checklistView = caption("", 14f).also(root::addView)
        root.addView(button("복용 세션 시작 — 유효한 처방만 프리필") { startSession() })
        finalCheck = CheckBox(this).apply {
            text = "복용 전 최종확인: 약·수량을 직접 확인했습니다 (프리필로 건너뛰지 않음)"
            textSize = 12f
            setOnCheckedChangeListener { _, checked ->
                if (checked && sessionActive) {
                    runCatching { domain.confirmBeforeTaking() }
                    currentAction = "최종확인"
                    lastEventMessage = "기록되었습니다 — '복용 기록' 버튼이 열렸습니다."
                }
                renderRecordEnabled()
            }
        }
        root.addView(finalCheck)
        recordBtn = button("복용 기록") { recordTaking() }.also(root::addView)
        metricLine = caption("", 12f).also(root::addView)

        // ---- E1 학습 ----------------------------------------------------
        root.addView(caption("학습 (E1 — 사실·추론·기한형 구분)", 15f, bold = true))
        root.addView(button("혈압약 처방 등록 (지시 v1 · 잔량 6 · 임계 5) — 상시") { run { domain.learnBpPrescription() } })
        root.addView(button("항생제 등록 (7일 기한 · 잔량 14 · 임계 2) — 기한형") { run { domain.learnAbxCourse() } })
        notifyBtn = button("복용 알림 예약 (아침 8시) — 알림 경로") {
            run { domain.learnNotifySchedule() }
        }.also(root::addView)
        permissionBtn = button("알림 권한 요청 (OS 실제 권한)") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }.also(root::addView)
        root.addView(button("민감 진단 메모 기억 (합성)") { run { domain.learnPrivateNote() } })

        // ---- E3 예외 ----------------------------------------------------
        root.addView(caption("상황 변화 (E3 예외 → E4 복구)", 15f, bold = true))
        root.addView(button("처방 정정 v2 (혈압약 지시 변경) — 이 약의 성분만 재계산") { run { domain.correctBpPrescription() } })
        root.addView(button("항생제 복용 중단 — 그 약·미도착 리필만 무효화") { run { domain.stopAbx() } })
        root.addView(button("알림 scope 철회 — 알림 파생 성분만 무효화") { run { domain.revokeNotifyScope() } })
        root.addView(button("네트워크 온/오프 전환") { toggleNetwork() })
        root.addView(button("보류 결정 재전달 (연결 회복 후 1회 재시도)") { run { domain.replayWaited() } })
        root.addView(button("리필 draft 승인 (혈압약) — 승인만이 도착 대기 등록") { approveRefill() })
        root.addView(button("반나절 전진 — 다음 복용 슬롯") { run { domain.advanceHalfDay() } })
        root.addView(button("3일 전진 — 리필 도착·기한 관찰") { run { domain.advanceDays(3) } })
        root.addView(button("새 세션 시작 (배우자 약은 별개 대상)") { run { domain.startNewSession() } })
        root.addView(button("배우자 약에 대한 결정 요청 (보류 시연)") { run { domain.requestSpouseDecision() } })
        root.addView(button("민감 메모 삭제 — 원문 제거, tombstone만") { run { domain.deletePrivateNote() } })
        root.addView(button("전체 초기화 (Reset)") { resetJourney() })

        // ---- 기억 원장 --------------------------------------------------
        root.addView(caption("기억 원장 (열람)", 15f, bold = true))
        ledgerView = caption("", 12f).also(root::addView)

        // ---- 짝비교 ------------------------------------------------------
        root.addView(caption("짝비교 실험실 (full vs claim-off)", 15f, bold = true))
        root.addView(button("비교 실행 — 같은 시작 상태·같은 이벤트 열") { runComparison() })
        comparisonView = caption("", 11f).also(root::addView)

        // ---- 증거 -------------------------------------------------------
        root.addView(button("증거 내보내기 (evidence/*)") { exportEvidence() })

        // ---- Probe 공개 점검 -------------------------------------------
        root.addView(caption("공개 점검 (Probe rehearsal)", 15f, bold = true))
        root.addView(probeButton("공개 입력 가져오기", "SCPC_PROBE_IMPORT") { importInput() })
        root.addView(probeButton("공개 입력 실행", "SCPC_PROBE_RUN") { runProbeSteps() })
        root.addView(probeButton("결과 내보내기", "SCPC_PROBE_EXPORT") { exportProbeResult() })
        probeStatus = caption(
            "PROBE_INPUT.json 경로: ${getExternalFilesDir(null)?.absolutePath}", 11f,
        ).also(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아온 직후 등 — 권한 전이를 행동 이전에 반영한다.
        if (::domain.isInitialized) render()
    }

    /** 실제 OS 권한의 중도 회수·재허용 전이를 원장·안내에 자동 반영 (선언: 권한 = 실제 입력). */
    private fun syncNotifyPermission(granted: Boolean) {
        val prefsPerm = getSharedPreferences("medkeeper-ui-meta", MODE_PRIVATE)
        val wasGranted = prefsPerm.getBoolean("notify_was_granted", granted)
        if (wasGranted && !granted) {
            runCatching { domain.revokeNotifyScope() }
            lastEvent.text = "알림 권한이 회수되어 알림 파생 성분을 무효화했습니다 — 재허용 후 알림 예약을 다시 등록하세요."
        } else if (!wasGranted && granted) {
            lastEvent.text = "알림 권한이 다시 허용되었습니다 — 알림 예약을 다시 등록해 주세요."
        }
        prefsPerm.edit().putBoolean("notify_was_granted", granted).apply()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------
    // 행동 핸들러
    // ------------------------------------------------------------------

    private fun run(action: () -> JSONObject?) {
        runCatching {
            val r = action()
            lastEventMessage = if (r == null) "재전달할 보류 결정이 없습니다." else
                "${r.getString("operation")} → ${r.getString("decision_state")}" +
                    " (선택 ${r.getJSONArray("selected_context_ids").length()}건," +
                    " 무효화 ${r.getJSONArray("invalidated_state_ids").length()}건)"
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun startSession() {
        runCatching {
            // 이 슬롯에 등재된 약이 없으면 세션을 열지 않는다 — 다른 약으로 대상이 넘어가
            // 체크리스트와 모순되는 프리필을 표시·기록하는 일이 없게(지표 정직성).
            if (domain.composeChecklist().rows.none { it.listedToday }) {
                sessionActive = false
                lastEventMessage = "이 슬롯에 등재된 약이 없습니다 — 학습 버튼으로 등록하거나 직접 확인 목록을 확인하세요."
                return@runCatching
            }
            val r = domain.startSession()
            val decision = r.getString("decision_state")
            val list = domain.composeChecklist()
            // 로컬 판단(체크리스트 확정·복용 기록)은 오프라인에서도 이어진다 — WAIT는 전송
            // 성격(원격 결정)의 보류일 뿐 세션 진행을 막지 않는다(선언 모바일 절 이행).
            sessionActive = decision == "ACT" || decision == "WAIT"
            lastSessionDecision = decision
            // 프리필 수는 선언된 지표 정의(체크리스트: 슬롯 등재 행 + 반영된 도착 수)로 단일화
            // — 결정 응답의 selected 개수와 이원화되지 않게 한다.
            lastPrefill = list.prefillCount
            lastManual = list.manualCount
            finalCheck.isChecked = false
            lastEventMessage = when (decision) {
                "ACT" -> "${domain.slotLabel()} 세션 열림 — 프리필 ${lastPrefill}건, 최종확인 후 복용을 기록하세요."
                "WAIT" -> "오프라인 — 로컬 확인·복용 기록은 계속합니다. 전송이 필요한 결정만 보류되어 연결 회복 후 '재전달'로 1회 재시도됩니다."
                "ASK" -> "유효한 처방 기억이 없어 질문합니다: 학습 버튼으로 등록해 주세요."
                "ABSTAIN" -> "관리 대상이 아닌 약 — 행동을 보류합니다."
                else -> "세션 시작: $decision"
            }
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun recordTaking() {
        runCatching {
            val results = domain.recordTaking()
            domain.recordSessionMetric(lastPrefill, lastManual, finalCheck.isChecked, lastSessionDecision)
            val drafts = results.count { it.getString("operation") == "REQUEST_DECISION" }
            lastEventMessage = "복용 기록 완료 (${results.size}건 기록" +
                (if (drafts > 0) ", 잔량 임계 도달 → 리필 draft ${drafts}건 생성" else "") + ")"
            sessionActive = false
            finalCheck.isChecked = false
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun toggleNetwork() {
        runCatching {
            val online = domain.core.snapshotState().optString("network", "ONLINE") == "ONLINE"
            run { domain.setNetwork(!online) }
        }.onFailure {
            lastEventMessage = "오류: ${it.message}"
            render()
        }
    }

    private fun approveRefill() {
        runCatching {
            val r = domain.approveRefill(MedDomain.MED_BP)
            lastEventMessage = if (r == null) "승인할 리필 draft가 없습니다 — 잔량이 임계 아래로 내려가면 draft가 생성됩니다."
            else "리필 draft 승인 — 준비완료가 가상시간 경과 후 도착합니다."
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun resetJourney() {
        runCatching {
            domain.resetAll()
            sessionActive = false
            lastPrefill = 0
            lastManual = 0
            finalCheck.isChecked = false
            lastEventMessage = "전체 초기화 완료 — 새 원장에서 시작합니다."
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun runComparison() {
        runCatching {
            val report = domain.runComparison()
            comparisonView.text = comparisonSummary(report)
            lastEventMessage = "짝비교 완료 — 두 갈래 모두 정상 완주 (아래 관찰 지표)"
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    private fun exportEvidence() {
        runCatching {
            val files = domain.exportEvidence(filesDir) +
                (getExternalFilesDir(null)?.let { domain.exportEvidence(it) } ?: emptyList())
            lastEventMessage = "증거 ${files.size}개 파일 기록 완료 — evidence/ (내부+외부 저장소)"
        }.onFailure { lastEventMessage = "오류: ${it.message}" }
        render()
    }

    // ------------------------------------------------------------------
    // 렌더링
    // ------------------------------------------------------------------

    private fun render() {
        runCatching { renderInner() }.onFailure { stateLine.text = "표시 오류: ${it.message}" }
    }

    private fun renderInner() {
        val state = domain.core.snapshotState()
        stateLine.text = "세션 ${domain.sessionId()} (${domain.slotLabel()}) · 가상시각 ${domain.virtualTimeIso()}" +
            " · 네트워크 ${state.optString("network", "ONLINE")}" +
            " · epoch ${state.optInt("process_epoch", 0)}" +
            " · 판단 ${state.optInt("decision_request_count", 0)}회"

        val list = domain.composeChecklist()
        // 색·굵기로 상태가 한눈에 들어오게: 등재 약은 굵게, 임계 경고는 적색, 도착 반영은 녹색.
        val checklistHtml = buildString {
            append("오늘 <b>${domain.slotLabel()}</b> 체크리스트:<br>")
            list.rows.forEach { r ->
                val stockText = r.stock?.let { s ->
                    "잔량 <b>$s</b>" + (r.threshold?.let { t ->
                        if (s <= t) " <font color=\"#C62828\"><b>(임계 $t 이하 — 리필 필요)</b></font>" else ""
                    } ?: "")
                } ?: "잔량 기록 없음"
                val name = if (r.listedToday) "<b>${r.label}</b>: ${r.prescription}"
                else "${r.label}: <font color=\"#9E9E9E\">목록 제외</font>"
                append("&nbsp;&nbsp;· $name — $stockText<br>")
                append("&nbsp;&nbsp;&nbsp;&nbsp;<font color=\"#757575\"><small>${r.note}</small></font><br>")
            }
            if (list.arrivals > 0) {
                append("&nbsp;&nbsp;· <font color=\"#1B5E20\"><b>지연 결과 반영: 도착 ${list.arrivals}건</b></font><br>")
            }
        }
        checklistView.text = Html.fromHtml(checklistHtml.removeSuffix("<br>"), Html.FROM_HTML_MODE_LEGACY)

        // 알림 권한(OS 실제 경로): Android 13+에서만 runtime — 없으면 알림 예약 경로가 닫힌다.
        val granted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        syncNotifyPermission(granted)
        notifyBtn.isEnabled = granted
        permissionBtn.isEnabled = !granted
        permissionBtn.text = if (granted) "알림 권한: 허용됨 (OS)" else "알림 권한 요청 (OS 실제 권한)"

        renderRecordEnabled()

        val m = domain.metrics()
        metricLine.text = if (m.length() == 0) "세션 기록 없음" else {
            val last = m.getJSONObject(m.length() - 1)
            "누적 세션 ${m.length()}회 · 최근: 프리필 ${last.getInt("prefill_count")}건 / " +
                "직접 확인 ${last.getInt("manual_input_count")}건 / 최종확인 ${
                    if (last.getBoolean("safety_confirmed")) "유지" else "미확인"
                }"
        }

        ledgerView.text = Html.fromHtml(ledgerHtml(state), Html.FROM_HTML_MODE_LEGACY)
        val report = domain.lastComparison()
        comparisonView.text = if (report != null) comparisonSummary(report) else "(비교 기록 없음)"
    }

    /** 가능행동 실물: 최종확인 전에는 복용 기록 버튼이 물리적으로 잠긴다. */
    private fun renderRecordEnabled() {
        recordBtn.isEnabled = sessionActive && finalCheck.isChecked
        recordBtn.text = when {
            !sessionActive -> "복용 기록 (세션을 먼저 시작하세요)"
            !finalCheck.isChecked -> "복용 기록 (최종확인 필요)"
            else -> "복용 기록"
        }
    }

    private fun ledgerHtml(state: JSONObject): String {
        val memories = state.optJSONObject("memories") ?: JSONObject()
        val valid = domain.core.validMemories().keys
        val lines = memories.keys().asSequence().sorted().map { id ->
            val mem = memories.getJSONObject(id)
            // 상태 배지에 색을 입혀 유효/무효가 목록에서 즉시 구분되게 한다.
            val flag = when {
                id in valid -> "<font color=\"#1B5E20\"><b>[유효]</b></font>"
                mem.getString("status") == "CONSUMED" -> "<font color=\"#757575\"><b>[소모]</b></font>"
                mem.getString("status") == "INVALIDATED" -> "<font color=\"#C62828\"><b>[무효]</b></font>"
                mem.optString("valid_until").isNotEmpty() -> "<font color=\"#E65100\"><b>[만료]</b></font>"
                else -> "<font color=\"#757575\"><b>[정지]</b></font>"
            }
            "$flag <b>${mem.getString("value")}</b> (${mem.getString("type")}/${mem.getString("lifetime")}" +
                ", ${mem.optString("entity")}, scope ${mem.optString("scope")}" +
                ", 지시 ${mem.optString("authority").ifEmpty { "-" }})"
        }.toList()
        val tombstones = state.optJSONArray("tombstones") ?: JSONArray()
        return buildString {
            if (lines.isEmpty()) append("(기억 없음)<br>") else lines.forEach { append("$it<br>") }
            if (tombstones.length() > 0) {
                append("<font color=\"#757575\">삭제 기록(tombstone, 원문 없음): " + (0 until tombstones.length())
                    .joinToString(", ") { tombstones.getString(it) } + "</font><br>")
            }
        }.removeSuffix("<br>")
    }

    private fun comparisonSummary(report: JSONObject): String {
        val full = report.getJSONObject("full_arm")
        val off = report.getJSONObject("claim_off_arm")
        fun row(label: String, key: String) =
            "  $label: full ${full.opt(key)} vs off ${off.opt(key)}"
        return buildString {
            appendLine("같은 시작 상태 + 같은 8단계 이벤트 열 (격리 저장공간):")
            appendLine(row("만료 항생제 유령 재사용", "s5_expired_ghost_reuse"))
            appendLine(row("만료 후 판단", "s5_decision"))
            appendLine(row("정정 영향 특정 정밀도", "s6_impact_precision"))
            appendLine(row("정정 후 프리필 수", "s7_prefill_final"))
            appendLine(row("episode 완주", "episode_completed"))
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Probe 공개 점검 — protected 경로와 같은 파서·같은 candidate adapter 경유.
    // ------------------------------------------------------------------

    private val probeAdapter = ChebiProbeAdapter()
    private var lastRunResults: JSONArray? = null
    private var lastRunPackId: String = ""
    private var activeRunForAbort: ProbeRunContext? = null

    private fun inputFile() = File(getExternalFilesDir(null), "PROBE_INPUT.json")
    private fun importedFile() = File(filesDir, "imported_probe_input.json")
    private fun resultFile() = File(getExternalFilesDir(null), "PROBE_RESULT.json")

    private fun importInput() {
        runCatching {
            val src = inputFile()
            check(src.isFile) { "PROBE_INPUT.json이 없습니다: ${src.absolutePath}" }
            val text = src.readText()
            val doc = ProbeInputParser.parsePublic(text)
            importedFile().writeText(text)
            probeStatusMessage = "가져오기 완료: ${doc.steps.size}개 step (pack ${doc.probePackId})"
        }.onFailure { probeStatusMessage = "오류: ${it.message}" }
    }

    private fun runProbeSteps() {
        runCatching {
            val f = importedFile()
            check(f.isFile) { "먼저 공개 입력을 가져오세요." }
            val doc = ProbeInputParser.parsePublic(f.readText())
            lastRunResults = null
            lastRunPackId = ""
            val prefs = getSharedPreferences("medkeeper-ui-meta", MODE_PRIVATE)
            val runSeq = prefs.getInt("public_run_seq", 0) + 1
            check(prefs.edit().putInt("public_run_seq", runSeq).commit()) { "run 순번 저장 실패" }
            val run = ProbeRunContext(
                assignmentId = "public-ui-assignment",
                candidateId = doc.candidateId,
                instanceId = doc.instanceId,
                runId = "public-ui-run-%04d".format(runSeq),
                probePackId = doc.probePackId,
                missionId = doc.missionId,
                releaseAttestationId = doc.releaseAttestationId,
            )
            activeRunForAbort = run
            // protected 경로와 동일한 lifecycle: onRunStarted → executeStep* → onRunFinished.
            probeAdapter.onRunStarted(this, run)
            val results = JSONArray()
            doc.steps.forEach { step -> results.put(probeAdapter.executeStep(this, run, step)) }
            val evidenceCount = probeAdapter.onRunFinished(this, run).size
            lastRunResults = results
            lastRunPackId = doc.probePackId
            probeStatusMessage = "실행 완료: ${doc.steps.size}개 step, 증거 ID ${evidenceCount}개 — " +
                "'결과 내보내기'로 PROBE_RESULT.json을 저장하세요."
        }.onFailure {
            runCatching { activeRunForAbort?.let { r -> probeAdapter.onRunAborted(this, r, it.message ?: "run failed") } }
            probeStatusMessage = "실행 실패: ${it.message} — 다시 실행하세요."
        }
        render()
    }

    private fun exportProbeResult() {
        runCatching {
            val results = checkNotNull(lastRunResults) { "먼저 실행하세요." }
            // 점수·판정류 없는 결과 저장 (계약의 SCPC_PROBE_EXPORT 정의).
            resultFile().writeText(
                JSONObject()
                    .put("artifact_kind", "public_ui_probe_result")
                    .put("probe_pack_id", lastRunPackId)
                    .put("step_results", results)
                    .toString(2),
            )
            probeStatusMessage = "내보내기 완료 — ${resultFile().absolutePath}"
        }.onFailure { probeStatusMessage = "오류: ${it.message}" }
    }

    // ------------------------------------------------------------------
    // 위젯 헬퍼
    // ------------------------------------------------------------------

    private fun caption(text: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, if (bold) 24 else 2, 0, 2)
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            // 어떤 버튼의 결과인지 알림에 귀속시키기 위해 동작 이름을 먼저 기록한다.
            setOnClickListener {
                currentAction = shortAction(label)
                onClick()
            }
        }

    private fun probeButton(label: String, description: String, onClick: () -> Unit): Button =
        button(label, onClick).apply { contentDescription = description }
}
