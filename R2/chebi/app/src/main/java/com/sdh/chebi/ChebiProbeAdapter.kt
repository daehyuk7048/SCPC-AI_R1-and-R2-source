package com.sdh.chebi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeAdapter
import org.scpc.r2.probe.ProbeInputStep
import org.scpc.r2.probe.ProbeRunContext
import org.scpc.r2.probe.ProbeRuntimeState
import java.io.File
import java.util.UUID

/**
 * 공식 Probe Mode 어댑터 — 앱 UI가 사용하는 것과 동일한 production core(VG-Ledger)를
 * 호출한다. Probe 전용 로직·mock 없음.
 *
 * same-adapter 계약: 공개 UI의 probe 실행(MainActivity)과 protected Probe 실행이 모두
 * 이 클래스의 executeStep을 경유한다 — 실행부의 실체는 companion의 [executeStepWith]
 * 하나뿐이며, 그 사실을 SameAdapterPathTest가 검증한다.
 */
class ChebiProbeAdapter : ProbeAdapter {

    override fun runtimeState(context: Context): ProbeRuntimeState =
        ProbeRuntimeState(
            // 약지기는 온디바이스 결정론 구현이다. 외부 model/backend를 사용하지 않으며
            // 판단 요청 수는 product state로만 추적한다(모델 호출로 신고하지 않음).
            modelConfigured = false,
            cumulativeInvocations = 0,
        )

    override fun onRunStarted(context: Context, run: ProbeRunContext) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // 같은 run의 재개(kill 후 relaunch)면 이전 step들의 증거·ID 집계를 보존해야 한다 —
        // 증거 파일 stem 집합과 run ID 집계는 run 전체 결과와 1:1이어야 하므로.
        val isNewRun = prefs.getString("active_run_id", null) != run.runId
        if (isNewRun) {
            // 정리를 먼저, run 전환 표시(active_run_id)를 마지막 durable write로 —
            // 그 사이에 프로세스가 죽어도 재개 시 정리가 다시 수행된다(원자성).
            evidenceDir(context).deleteRecursively()
            prefs.edit()
                .putString(KEY_RUN_IDS, "[]")
                .putString("active_run_id", run.runId)
                .commit()
        }
        evidenceDir(context).mkdirs()
    }

    override fun executeStep(
        context: Context,
        run: ProbeRunContext,
        step: ProbeInputStep,
    ): JSONObject {
        val result = executeStepWith(core(context), evidenceDir(context), step)
        accumulateRunIds(context, result)
        return result
    }

    override fun onRunFinished(context: Context, run: ProbeRunContext): List<String> {
        // run 경계 집계: 이 run에서 실제 실행된 step들이 신고한 ID만 반환한다
        // (영속 원장 전체가 아니라 — run 선두가 RESET이 아니어도 결과와 1:1로 맞는다).
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = JSONArray(prefs.getString(KEY_RUN_IDS, "[]"))
        if (ids.length() > 0) {
            return (0 until ids.length()).map(ids::getString)
        }
        return core(context).evidenceIds()
    }

    override fun onRunAborted(context: Context, run: ProbeRunContext, reason: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString("last_abort_reason", reason)
            .remove("active_run_id")
            .commit()
    }

    private fun core(context: Context): ChebiCore =
        ChebiCore(AndroidStateStore(context, NAMESPACE_FULL), PROCESS_MARKER)

    /** kill을 견뎌야 하므로 동기 commit으로 step마다 즉시 영속화한다. */
    private fun accumulateRunIds(context: Context, result: JSONObject) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ids = JSONArray(prefs.getString(KEY_RUN_IDS, "[]"))
        val known = (0 until ids.length()).map(ids::getString).toMutableSet()
        listOf("auto_check_evidence_ids", "receipt_ids").forEach { key ->
            val arr = result.getJSONArray(key)
            for (i in 0 until arr.length()) {
                val id = arr.getString(i)
                if (known.add(id)) ids.put(id)
            }
        }
        prefs.edit().putString(KEY_RUN_IDS, ids.toString()).commit()
    }

    companion object {
        private const val PREFERENCES = "chebi-run"
        private const val KEY_RUN_IDS = "current_run_ids"
        // 약지기 전용 namespace — 전환 이전(채비) 잔류 상태와 격리된 새 저장공간.
        const val NAMESPACE_FULL = "medkeeper-core-full"
        val PROCESS_MARKER: String = UUID.randomUUID().toString()

        fun evidenceDir(context: Context): File =
            // 앱 전용 외부 폴더: release(non-debuggable) 빌드에서도 adb pull로 회수 가능해야
            // SAMPLE_EXPORT의 evidence 디렉토리를 앱이 만든 실파일로 채울 수 있다. 내용은
            // 합성 데이터의 step 관찰 기록뿐이며 민감 원문은 tombstone 원칙상 애초에 없다.
            File(context.getExternalFilesDir(null) ?: context.filesDir, "probe_evidence")

        /**
         * 두 진입경로(공개 UI · protected Probe)가 공유하는 유일한 실행부.
         * core 실행 + step 증거·receipt 실파일 기록(파일 stem == 결과에 신고된 ID, 1:1).
         */
        fun executeStepWith(core: ChebiCore, evidenceDir: File, step: ProbeInputStep): JSONObject {
            val result = core.execute(step)
            evidenceDir.mkdirs()
            val evidenceIds = result.getJSONArray("auto_check_evidence_ids")
            for (i in 0 until evidenceIds.length()) {
                File(evidenceDir, evidenceIds.getString(i) + ".json")
                    .writeText(
                        JSONObject()
                            .put("artifact_kind", "chebi_step_evidence")
                            .put("step_result", JSONObject(CanonicalJson.encode(result)))
                            .toString(2),
                    )
            }
            val receiptIds = result.getJSONArray("receipt_ids")
            for (i in 0 until receiptIds.length()) {
                File(evidenceDir, receiptIds.getString(i) + ".json")
                    .writeText(
                        JSONObject()
                            .put("artifact_kind", "chebi_step_receipt")
                            .put("receipt_id", receiptIds.getString(i))
                            .put("step_id", result.getString("step_id"))
                            .put("event_id", result.getString("event_id"))
                            .put("operation", result.getString("operation"))
                            .put("virtual_time", result.getString("virtual_time"))
                            .put("state_after_sha256", result.getString("state_after_sha256"))
                            .toString(2),
                    )
            }
            return result
        }
    }
}

/**
 * 영속 상태 저장소. namespace 분리로 full/claim-off 상태공간을 물리적으로 격리한다
 * (comparison mode에서 서로 다른 SharedPreferences 파일 사용).
 */
class AndroidStateStore(context: Context, namespace: String) : ChebiStateStore {
    private val preferences =
        context.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override fun load(): JSONObject =
        preferences.getString("production_state_json", null)?.let(::JSONObject) ?: JSONObject()

    override fun save(state: JSONObject) {
        check(
            preferences.edit()
                .putString("production_state_json", CanonicalJson.encode(state))
                .commit(),
        ) { "cannot persist chebi production state" }
    }
}
