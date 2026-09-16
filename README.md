# SCPC 2026 AI Challenge — 1차·2차 예선 및 본선 진출

2026 Samsung Collegiate Programming Challenge **AI 챌린지**(데이콘 운영) 참가 기록입니다. 참가자 닉네임 **SDH**.
1차 예선 1,845명 중 **18위**(Public 25위 → 히든 점수 합산 18위, 상위 40명 통과) → 2차 예선 **11위**로 본선 진출 → 본선 발표(10분) + 질의응답(10분).
두 라운드 모두 **외부 LLM API·네트워크 호출 없이 온디바이스 결정론 로직**으로 풀었습니다.

| 라운드 | 과제 | 제출물 | 결과 |
|---|---|---|---|
| 1차 예선 (R1) | 개인 기기 AI 에이전트의 요청 판단 하네스 | `data/harness.py` (Python, 1,131줄, 의존성 0) | Public LB **0.088(baseline) → 0.7381(첫 제출) → 0.8925(최종)**, 로컬 dev 0.960 · Public 25위 → 최종 **18위 / 1,845명** (상위 40명 통과) |
| 2차 예선 (R2) | 온디바이스 AI 앱 미션 설계·구현 | Android 앱 **"약지기"** (Kotlin, 2,433줄, 단위 테스트 43개) | **11위**, 본선 진출 |
| 본선 | 발표 + 질의응답 | `R2/mission/SCPC2026_본선발표자료_SDH.pdf` | 수상권 미달 |

---

## 1. 1차 예선 — Agent Harness (`data/`)

**과제.** 개인 기기(스마트폰) 에이전트에게 들어온 요청 task JSON(프롬프트, 기기 기록, 객체, focal 해석 trace, 세션 메모리)을 읽고, 여섯 축의 answer JSON을 생성한다: `focal_id`(중심 객체) · `target`(수신처/채널/저장소) · `control`(proceed / amend / hold / ask) · `content_scope` · `policy` · `plan_events`. 채점은 dev 120개(공개) + screening(비공개), 일부 축은 서버 전용.

**접근.** 학습 모델 대신 **task 구조에서 답을 유도하는 결정론적 규칙 체계**를 택했습니다. 규칙은 공개 dev 분할로만 역설계했고, task_id/session_id 답안표는 없습니다(id 리터럴 감사 0건).

```text
task JSON ──▶ prepare()            stream 안의 persistent_memory_write 를 먼저 적재 (장기 메모리 의미론)
          ──▶ choose_focal()       marker task: phase→marker→ref 사슬 / non-marker: history 문장 파싱
          ──▶ decide_control()     ① "단," 교정 절의 의도 검출 (내부 유지→proceed, 전제 붕괴→hold,
                                     확인 우선→ask, 축소·요약→amend)  ② record 하드 블록(동의 철회·safety·
                                     보안 경고)은 무조건 hold  ③ 중립 절은 5개 가족(MATERIAL/LADDER/
                                     FASTPATH/LATEST/DEVICE)으로 분류 후 가족별 record→control 맵
          ──▶ infer_target()       control 과 정합: proceed+내부→memory_store, amend→resolved_target,
                                     ask/hold→user; 세션 메모리 recall 은 문맥별 채널 라우팅
          ──▶ build_content_scope / build_policy / build_plan_events   (control, mode) 템플릿
          ──▶ update_session_memory()
```

**설계에서 신경 쓴 것**

- **미지 값 강건성** — record 값 비교를 의미 클래스 substring(`redacted*boundary`, `*pending*`, `*confirmed*` …)으로 정규화해 이름이 다른 새 변형에도 같은 의미로 동작. phase/marker 이름 변형, 누락 phase, 비정상 입력(빈 records, null)에도 스키마 유효 답안.
- **재현성** — `random`/`time` 미사용, dict 순회 의존 없음. `python harness.py` 한 줄로 최고 점수 제출본이 byte 단위 동일하게 재생성됨(2회 연속 diff 검증). 검증 환경처럼 `answer_task`를 stream 순서로 직접 호출해도 700/700 일치.
- **제공 SLM facade는 보조 신호로만** — `FixedSLMClient.summarize_task()` 결과는 `audit_tags`에만 결합, 채점 필드의 근거는 전부 task 구조.

**과정.** v1 → v30까지 42개 변형 파일이 `data/harness_v*.py`에 남아 있습니다(v2 세션 메모리, v3 가족 분류, v5 스윕, v8 history 제어 …). 리더보드 A/B 실험 9회, 자체 적대적 점검 10회 이상. v20~v30의 `_t3hold`, `_sbufirst`, `_laddersbu`, `_privacyuser` 같은 접미사는 판단 가설 하나를 격리해 리더보드로 검증한 흔적입니다.

| 로컬 dev (120개) | overall | focal | target | control | content_scope | policy | plan |
|---|---|---|---|---|---|---|---|
| 최종 | **0.960** | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 |
| 제공 baseline | 0.088 | | | | | | |

(`semantic_response`/`counterfactual`은 서버 전용 채점이라 로컬 상한 ≈ 0.96) — 실행법·판단 로직 상세는 [`data/README.md`](data/README.md).

---

## 2. 2차 예선 — 약지기 (`R2/`)

**미션.** 만성 처방(혈압약 상시)과 단기 처방(이번 주만 항생제)을 함께 복용하는 어르신이, 아침·저녁 복용 세션마다 오늘 먹을 약과 수량을 확인하고 잔량을 관리하는 **진화형 복약 관리** 앱. 복약 도구의 실패는 세 방향입니다 — 매번 처음부터 다시 묻거나 반대로 지난 목록을 통째로 재사용해 만료된 약이 유령처럼 재등장함(반복), 프로세스 종료·중복·역순 이벤트로 기록이 사라지거나 이중 기재됨(중단), 처방 정정·중단·권한 회수가 온 날에도 과거 방식을 밀어붙임(변화). 본질은 **"어떤 기억이 지금도 유효한지 판별하고, 무효가 된 부분만 고치는 것"** 으로 정의했습니다.

> 의료 가드: 이 앱은 의료 조언·진단·복약 지시가 아니며 용량·복용 여부를 스스로 정하지 않습니다. 기록된 의사 지시의 최신 버전·기한·중단 범위를 기억·대조해 표시할 뿐이고, 복용은 항상 사용자의 최종확인으로만 확정됩니다. 모든 데이터는 합성입니다.

### 아키텍처 — 두뇌 하나, 장부 둘

```text
 [사용자 화면]                    [공식 검증 도구 · 대회 Runner]
  MainActivity.kt                        (Probe Mode)
       │                                      │
       ▼                                      ▼
  MedDomain.kt                      ChebiProbeAdapter.kt
  버튼 → 표준 사건(이벤트) 변환       대회 규격 → 같은 사건 형식
       └──────────────┬───────────────────────┘
                      ▼
               ChebiCore.kt  ─  execute(사건) 하나가 모든 판단 (모델·난수·실시계 없음)
                      │
                      ▼
            AndroidStateStore  ─  폰 안의 JSON 장부 (기억 장부 · 행동 장부 · 도착 대기 큐 · 흔적 · 지문)
```

화면과 공식 검증 도구는 **입구만 다르고 같은 `execute()`를 부릅니다.** 화면 전용 판단 코드가 없다는 것을 `SameAdapterPathTest`로 검증하고, 13단계 표준 시험을 화면 조작과 공식 도구로 각각 돌렸을 때 **강제 종료 전 8단계의 상태 지문이 바이트까지 동일**함을 실측했습니다.

### Signature mechanism — 유효 조건이 붙은 기억 장부 (VG-Ledger)

기억 한 건마다 네 가지 조건이 붙습니다: **지시 버전**(`authority`, 약마다 따로 관리) · **유통기한**(`valid_until`) · **묶음**(`scope`, 끊으면 같이 꺼짐) · **상태**(ACTIVE / INVALIDATED / CONSUMED). 이 조건을 세 지점에서 검문합니다.

| 검문 | 언제 | 하는 일 | 예 |
|---|---|---|---|
| ① 재사용 게이트 | 세션 시작 | 조건을 모두 통과한 기억만 오늘 목록에 올림 | 7일 지난 항생제, 정정 전 처방은 제외 |
| ② 충돌 게이트 | 정정·중단·권한 회수 | 전체 초기화가 아니라 **어긋난 것만** 무효 | 항생제 정정이 혈압약을 못 건드림 |
| ③ 복구 게이트 | 무효 처리 후 | `derived_from` 연결선을 역추적해 파생 짐작·미도착 리필 대기까지 정리, 나머지는 보존 목록으로 명시 | 중단한 약의 리필이 나중에 도착해 잔량을 오염시키지 않음 |

무결성 장치: 매 사건 후 장부 전체를 정규화 직렬화해 SHA-256 **지문 사슬**로 연결(바꿔치기 즉시 노출), 행동은 PROPOSED→COMMITTED **2단계**(강제 종료를 틈탄 완료 위장 차단), 같은 사건 번호는 그대로 echo(**exactly-once**), 삭제는 원문 제거 + 흔적만(뒤늦은 옛 사건의 부활 차단), 판단은 사건에 실린 **가상 시각**만 사용(기기 시계 조작 무관, "3일 뒤"를 시연에서 즉시 재현).

### 검증

| 항목 | 내용 |
|---|---|
| 단위 테스트 | 43개 (`ChebiCoreTest` 37 · `UiFlowReplayTest` 4 · `SameAdapterPathTest` 2) |
| 공식 Runner 13단계 시험 | 실기기 완주 5회 (학습·정정·삭제·오프라인·강제 종료·중복·역순 도착·시간 경과·내보내기) |
| 비공개 시나리오 4벌 (자체 제작) | A 10단계 · B 11 · C 19 · D 17 — 앱이 본 적 없는 낱말로 작성, 공식 도구로 실기기 전 항목 통과 (`R2/practice_chebi/`) |
| 화면 자동 검사 | 35개 항목, 새로 설치한 상태에서 전부 통과 |
| 자체 점검 | 6라운드 144건 처리 |
| 짝비교 실험 (gate on / off) | 스위치 `gateEnabled` 하나만 다른 두 코어에 같은 8단계 사건 열 → 처방 정정의 **영향 특정 정확도 1.0 vs 0.4**, 정정 후 생존 목록 4건 vs 1건, 만료 항생제 유령 재사용 0 vs 발생 |

기술 스택: Kotlin 2.0.21 · Android SDK 35 (minSdk 28) · Gradle 8.9 · 런타임 외부 라이브러리 0 (Kit AAR + 플랫폼 API만). 미션 선언·기술 노트·설치 가이드는 `R2/mission/`, 제출 문서는 `R2/SUBMISSION_FINAL/`.

---

## 3. 본선

발표 10분 + 질의응답 10분. 발표 자료(`SCPC2026_본선발표자료_SDH.pdf`), 발표 스크립트(`PRESENTATION_SCRIPT.md`), 예상 문답 22개(`FINALS_QA_PREP.md`)가 `R2/mission/`에 있습니다.

결과는 수상권 미달이었고, 가장 큰 교훈은 **"왜 AI 에이전트인가"라는 질문에 답하지 못했다**는 것입니다. 만드는 능력과 설명하는 능력은 별개였고, 이후 프로젝트에서는 개발 전에 핵심 개념을 글로 정의하고 설명 연습을 개발 과정에 포함하고 있습니다.

---

## 4. 저장소 구조

```text
data/                 # R1 (1차 예선) — Agent Harness
├── harness.py        #   최종 제출본 (Python 3.10+, 외부 의존성 없음)
├── harness_v*.py     #   개선 과정 v1 → v30 (가설별 변형 포함)
├── README.md         #   실행 방법·판단 로직 설명
└── 2차/              #   R2 Kit 분석 노트, Mission Lock
R2/                   # R2 (2차 예선) — Android 앱 "약지기"
├── chebi/            #   Kotlin 소스 (Gradle 단일 모듈, 단위 테스트 43개)
├── mission/          #   Mission 선언·기술 노트·설치 가이드·본선 발표 자료·Q&A 대비
│   └── drafts/       #   초안·이전 버전·문서 리뷰 기록
├── practice_chebi/   #   자체 제작 비공개 시나리오 4벌(MED_A~D)·연습 입력·검증 스크립트
└── SUBMISSION_FINAL/ #   제출 문서 (BUILD_AND_SUBMISSION_INFO, 가이드·기술 노트 PDF)
```

## 5. 이 저장소에 포함하지 않은 것

대회 참가 약관에 따라 **주최 측 배포 자료는 재배포하지 않습니다.** 아래 파일은 데이콘 대회 페이지에서 직접 받으세요.

- R1 데이터: `screening_tasks.jsonl`, `dev_tasks.jsonl`, `dev_answers.json`, baseline 노트북, `TERMS_GUIDE.md`
- R2 Candidate Kit: `SCPC2026_R2_CANDIDATE_RELEASE_v3` 전체 (공식 Runner APK, `scpc-probe-starter-*.aar`, `public_harness/`, 샘플 앱)
- 데이터에서 파생된 산출물: `submission*.csv`, 공식 Runner 실행 결과(`RUN_*`, `EVIDENCE_*`, `SAMPLE_EXPORT`)
- 앱 서명 키(`R2/keystore/`), 빌드 산출물(APK, `.gradle/`, `build/`), 데모 영상

## 6. 실행

**R1** — `data/data/` 아래에 `screening_tasks.jsonl`을 두고 `python data/harness.py`. 같은 폴더에 `submission.csv`가 생성됩니다. 상세는 [`data/README.md`](data/README.md).

**R2** — Kit의 `scpc-probe-starter-3.0.0-draft.aar`를 `R2/chebi/app/libs/`에 넣은 뒤 `./gradlew :app:assembleRelease` (JDK 17). 서명 키가 없으면 unsigned APK로 빌드됩니다. 단위 테스트는 `./gradlew :app:testDebugUnitTest`. 상세는 [`R2/SUBMISSION_FINAL/BUILD_AND_SUBMISSION_INFO.md`](R2/SUBMISSION_FINAL/BUILD_AND_SUBMISSION_INFO.md).

## 라이선스

`R2/chebi/LICENSE` 및 `THIRD_PARTY_NOTICES.md` 참고.
