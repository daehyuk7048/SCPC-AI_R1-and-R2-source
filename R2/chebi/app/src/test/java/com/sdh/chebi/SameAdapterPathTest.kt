package com.sdh.chebi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.scpc.r2.probe.CanonicalJson
import org.scpc.r2.probe.ProbeInputStep
import java.io.File
import java.nio.file.Files

/**
 * same-adapter 계약(08_PROBE_MODE_CONTRACT §7): 공개 UI와 protected Probe 실행경로가
 * 모두 같은 candidate adapter를 호출함을 보인다.
 *
 * 실행부의 실체는 [ChebiProbeAdapter.executeStepWith] 하나뿐이다 —
 * protected 경로(ChebiProbeAdapter.executeStep)와 공개 UI 경로(MainActivity.runProbeSteps →
 * probeAdapter.executeStep)가 모두 이 함수로 수렴하며, 아래 테스트가
 * (1) 그 단일 실행부의 결정론·증거 계약과 (2) 두 진입경로의 소스 수준 연결을 검증한다.
 */
class SameAdapterPathTest {

    private fun step(index: Int, op: String, roles: Map<String, String> = emptyMap()): ProbeInputStep {
        val rolesJson = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }
        return ProbeInputStep(
            index, "STEP-$index", op, "S-A", "2026-01-01T00:0$index:00Z", "EV-$index", rolesJson, "", "",
        )
    }

    private val journey = listOf(
        step(1, "RESET_AND_START"),
        step(
            2, "UPSERT_FACT",
            mapOf(
                "PRIMARY_GOAL" to "G", "TARGET_ENTITY" to "ENTITY_A",
                "STABLE_VALUE" to "BLUE", "ONE_OFF_VALUE" to "MORNING",
            ),
        ),
        step(3, "REQUEST_DECISION", mapOf("PRIMARY_GOAL" to "G", "TARGET_ENTITY" to "ENTITY_A")),
    )

    @Test
    fun `single shared executor - identical results and evidence files on both invocations`() {
        fun runThrough(): Pair<List<String>, File> {
            val dir = Files.createTempDirectory("chebi-adapter-test").toFile()
            val core = ChebiCore(InMemoryStateStore(), "proc-shared")
            val results = journey.map {
                CanonicalJson.encode(ChebiProbeAdapter.executeStepWith(core, dir, it))
            }
            return results to dir
        }
        // 같은 입력이면 어떤 진입경로든(둘 다 이 실행부를 호출하므로) 결과가 문자 그대로 같다.
        val (first, dirA) = runThrough()
        val (second, dirB) = runThrough()
        assertEquals(first, second)

        // 증거 계약: 파일 stem 집합 == 결과에 신고된 evidence+receipt ID 집합 (1:1).
        val reported = journey.indices.flatMap { i ->
            val r = JSONObject(first[i])
            (0 until r.getJSONArray("auto_check_evidence_ids").length())
                .map { r.getJSONArray("auto_check_evidence_ids").getString(it) } +
                (0 until r.getJSONArray("receipt_ids").length())
                    .map { r.getJSONArray("receipt_ids").getString(it) }
        }.toSet()
        listOf(dirA, dirB).forEach { dir ->
            val stems = dir.listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
            assertEquals(reported, stems)
        }
    }

    @Test
    fun `both entry points route through the adapter in source`() {
        // 소스 수준 연결 검증: 공개 UI(MainActivity)는 probeAdapter.executeStep을,
        // protected 경로(ChebiProbeAdapter.executeStep)는 companion executeStepWith를 경유한다.
        // 주석 제거 + 공백 내성 매칭으로 서식 변경·주석 처리에 강인하게 검사한다.
        fun liveCode(path: String): String =
            File(path).readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .lineSequence().joinToString("\n") { it.substringBefore("//") }

        val mainSrc = liveCode("src/main/java/com/sdh/chebi/MainActivity.kt")
        assertTrue(
            "공개 UI 실행이 candidate adapter를 경유해야 한다",
            Regex("probeAdapter\\s*\\.\\s*executeStep\\s*\\(").containsMatchIn(mainSrc),
        )
        assertTrue(
            "공개 UI가 adapter lifecycle(onRunStarted)을 사용해야 한다",
            Regex("probeAdapter\\s*\\.\\s*onRunStarted\\s*\\(").containsMatchIn(mainSrc),
        )
        val adapterSrc = liveCode("src/main/java/com/sdh/chebi/ChebiProbeAdapter.kt")
        assertTrue(
            "protected 경로가 공유 실행부(executeStepWith)를 경유해야 한다",
            Regex("executeStepWith\\s*\\(\\s*core\\(context\\)").containsMatchIn(adapterSrc),
        )
    }
}
