# SCPC 2026 R2 종합 분석 — 엔지니어링 제약·채점 역학 통합 보고

전제 확인: 아래는 5개 영역 분석의 사실(파일·줄 근거 있음)만 조합한 것이며, 추측인 부분은 `[추측]`으로 표기했다. CORE 의미 정의는 07_SELF_SCORING_AND_REHEARSAL(L49-56)과 SELF_SCORE_EXAMPLE.json의 expected_property를 정본으로 삼았다(리허설 영역 분석의 "CORE-4=currentness 축" 류 해설은 lookup 점수와 07 정의를 혼동한 느슨한 주석이므로 폐기).

---

## (1) CORE anchor 플레이북 (기여점수 내림차순)

Q 환산은 PUBLIC_SCORING_LOOKUP.json 기준 완전 선형(anchor×max/4, 반올림 없음). 검산: Q1=24(CORE-3/4/5/6 각 6), Q2=14(3:5, 4:5, 6:4), Q3=12(1:6, 3:6), Q4=14(2:7, 4:7), Q5=8(1:3, 3:2, 4:3), Q6=8(5:4, 6:4) — 합 80 일치.

공통 anchor 사다리(07 L36-42): **0**=반대행동·관찰불가 / **1**=준비된 happy path만, 새 값·재시작에서 붕괴 / **2**=정상 케이스는 되지만 장기 연결·범위·회복 미흡 / **3**=공개 변형 전부에서 상태·행동·증거 일관 / **4**=처음 보는 표면 변형 + late outcome에서도 3 유지 + **실패 경계가 명시적**. anchor 4에 comparison·소유권 입증 불필요(07 L44-45) — 순수 견고성으로 도달 가능.

### CORE-4 — 21점 (anchor당 5.25) : change-point 대응과 scoped recovery
- **측정**: 충돌·변화 지점에서 ACT/ASK/WAIT/ABSTAIN/replan을 정직하게 선택하고, 영향받은 부분만 수정해 E4로 회복하는가 (07 L54, SELF_SCORE 'change-point response and scoped recovery'). Q1:6+Q2:5+Q4:7+Q5:3.
- **13-step 대응 지점**: step 5 CORRECT_FACT(V1→V2), step 6 REVOKE_SCOPE(REVOKED/PRESERVED 분리), step 8 SET_NETWORK(OFFLINE→WAIT), step 11 DELIVER_OUT_OF_ORDER(구 authority 거부).
- **앱이 해야 할 것**:
  - 0→1: CORRECT/REVOKE를 받아 state가 실제로 바뀌고 다음 REQUEST_DECISION이 새 값 사용.
  - 1→2: 전체 reset이 아닌 부분 무효화 — `invalidated_state_ids`에 영향 descendant만, `preserved_state_ids`에 나머지를 명시적으로 나열.
  - 2→3: OFFLINE·충돌 상황에서 낙관적 ACT 대신 WAIT/ASK/ABSTAIN 전이 + 공개 변형(PRACTICE-A/B) 전부에서 일관.
  - 3→4: role 키·값이 처음 보는 문자열이어도 동작(불투명 문자열 처리) + "언제 못 하는지"(예: authority 미확정 시 ASK)를 receipt·화면에 남김. SELF_SCORE difference에 독립 변형 시험 기록.
- **export 증거**: evidence/state/invalidation.json, recovery.json, authority.json + step별 receipt. invalidated/preserved 집합이 화면 표시와 일치해야 함(G6).

### CORE-3 — 19점 (anchor당 4.75) : 반복 부담 감소 + 안전확인 유지
- **측정**: E2 재사용 시 새 값·표현·표면에서도 사용자 반복 설명 부담이 줄고, 필수 안전확인은 생략되지 않는가 (07 L53). Q1:6+Q2:5+Q3:6+Q5:2.
- **대응 지점**: step 3 ADVANCE_SESSION(새 surface)+step 4 REQUEST_DECISION — 새 세션에서 기존 goal/entity/stable value를 재사용하되 distractor B를 선택하지 않아야 함. ONE_OFF_VALUE는 1회 소모 후 재적용 금지(lifetime 의미론).
- **앱이 해야 할 것**:
  - 0→1: 세션 경계 넘어 stable fact가 영속(SharedPreferences commit 등)되고 재사용됨.
  - 1→2: stable vs one-off lifetime 구분 — ONE_OFF는 첫 사용 후 소모 표시, 이후 판단에서 배제.
  - 2→3: kill/relaunch 후에도 재사용 성립 + 안전확인(commit 전 PROPOSED 상태 유지)이 반복 시에도 생략 안 됨.
  - 3→4: 제3의 surface·다른 entity 이름·다른 순서에서 relation 유지(PRACTICE-B 취지, 07 L18).
- **export 증거**: evidence/state/facts.json(lifetime 필드 포함), context_selection.json — "무엇을 재사용했고 무엇을 다시 물었는지"가 receipt로 추적 가능해야 함.

### CORE-6 — 14점 (anchor당 3.5) : late outcome 반영 + ledger 무결성
- **측정**: 뒤늦게 도착한 결과(DELAYED_OUTCOME)가 다음 판단을 실제로 바꾸고, event→action→outcome ledger 3자가 일치하는가 (07 L56). Q1:6+Q2:4+Q6:4.
- **대응 지점**: step 12 ADVANCE_TIME(+7일, DELAYED_OUTCOME_ARRIVED) → PROPOSED action이 COMMITTED+outcome_id로 전이, 이후 EXPORT에 반영.
- **앱이 해야 할 것**:
  - 0→1: virtual_time 구동(실제 시계 금지). ADVANCE_TIME 수신 시 pending outcome을 도착 처리.
  - 1→2: outcome이 ledger(evidence/ledger/outcomes.json)에 기록되고 action.outcome_id 채움.
  - 2→3: outcome 도착이 **다음 REQUEST_DECISION의 결과를 실제로 변경** — 단순 기록이 아니라 판단 입력이 되어야 함. 화면·state·export·receipt 4자 일치.
  - 3→4: 독립적인 late trade-off 변형(공개 13-step에 없는 타이밍·값)에서도 유지 + outcome 미도착 시의 정직한 PENDING 경계 명시.
- **export 증거**: outcomes.json + step별 receipt_ids + auto_check_evidence_ids, EVIDENCE_INDEX step_ids 역매핑.

### CORE-5 — 10점 (anchor당 2.5) : restart reconciliation + exactly-once
- **측정**: 강제종료·duplicate·out-of-order 후에도 정확히 한 번 실행과 local continuity (07 L55). Q1:6+Q6:4.
- **대응 지점**: step 9 PROCESS_KILL_RELAUNCH(실제 kill, state_before==state_after 증명), step 10 REPLAY_EVENT(기존 action 반환→CONFIRMED_COMPLETE, 새 action 생성 금지), step 11 out-of-order(부활 금지).
- **앱이 해야 할 것**:
  - 0→1: 영속 저장 + 재실행 후 상태 복구(process_epoch 증가 메커니즘 — 프로세스 마커 UUID 패턴).
  - 1→2: eventId 기반 idempotency ledger — REPLAY 시 동일 action_id/idempotency_key/outcome_id 재반환.
  - 2→3: kill 시점에 PROPOSED였던 action을 완료로 승격하지 않고 PENDING 유지, state 해시 before==after로 무손실 증명. 진행 중 write에서 죽었으면 추정 없이 RECOVERY_REQUIRED.
  - 3→4: OFFLINE+kill+replay 복합(공개 13-step 후반이 정확히 이 조합) + 임의 순서 변형에서 유지.
- **export 증거**: state hash 체인(step N after == step N+1 before), action idempotency 필드, receipt.

### CORE-1 — 9점 (anchor당 2.25) : distractor 배제 + 최소 맥락
- **측정**: wrong-goal/entity distractor 불변성, 현재 판단에 필요한 최소 context만 사용 (07 L51). Q3:6+Q5:3.
- **앱이 해야 할 것**: REQUEST_DECISION 시 `selected_context_ids`에 목표 관련 state ID만 나열(distractor ENTITY_B 제외). **많이 넣는 것이 감점** — 과거 전부를 context에 넣는 설계는 여기서 실패. distractor 추가 전후 판단 결과 동일함을 증거로.
- **export 증거**: context_selection.json, target_entity.json. distractor는 별도 저장 격리 `[추측: MISSION_ADAPTER 예시의 memory.entityId 필드가 힌트일 수 있음]`.

### CORE-2 — 7점 (anchor당 1.75) : current authority·delete 우위
- **측정**: 정정·철회·삭제가 descendant까지 반영되고 재시작 후 stale 미사용 (07 L52). Q4:7 단독.
- **앱이 해야 할 것**: DELETE_FACT → 비가역 tombstone(원문·복원값 금지, ID·시각·범위만), export·relaunch·out-of-order 재전달 어디서도 원문 부활 금지. CORRECT 후 V1 참조 판단 금지. export 전체를 grep해 원문 부재 자가검증.
- **export 증거**: tombstones.json — G4 gate와 직결이므로 점수(7점) 대비 gate 리스크가 커서 절대 버릴 수 없음.

**우선순위 결론**: CORE-4+CORE-3=40점(전체 Q의 50%). 단일 코드 경로로는 **REQUEST_DECISION의 품질**(최소 context 선택 + 최신 authority + one-off 소모 + delayed outcome 반영 + 정직한 WAIT/ASK)이 CORE-1·3·4·6을 관통하므로 결정 로직에 투자 효율이 최대. 선형 환산이므로 한 CORE 3→4보다 낮은 CORE 0→2 인상이 대체로 이득이나, CORE-4는 1단계당 5.25점이라 예외적으로 끝까지 밀 가치가 있음.

---

## (2) 엔지니어링 제약 지도 (구현 체크리스트)

### A. Production Core (모든 것의 기반 — Probe 전용 mock 절대 금지)
- [ ] 영속 state 저장소: 단일 canonical JSON 직렬화(키 정렬 결정론) + 동기 commit(). state SHA-256(소문자 64hex) 계산 가능 구조.
- [ ] fact 모델: source·authority(version)·scope·lifetime(stable/one-off) 필드 보유.
- [ ] 판단 엔진: decision_state 8종(ACT/ASK/WAIT/ABSTAIN/PENDING/FAILED/CONFIRMED_COMPLETE/NO_DECISION) 전이. 온디바이스 결정론 권장(modelConfigured=false, NOT_USED) — 80 step 최대 입력에서 step당 1회 호출도 quota 60 초과하므로 모델 미사용이 구조적으로 안전.
- [ ] action ledger: action_id/idempotency_key(eventId 기반)/commit_state(NONE/PROPOSED/COMMITTED/CANCELLED/FAILED)/outcome_id. exactly-once 보장.
- [ ] tombstone 저장소: 삭제 ID·시각·범위·version만. upsert·out-of-order 부활 차단 로직.
- [ ] virtual time 축: 모든 lifetime·delayed outcome을 입력 virtual_time으로 구동. System.currentTimeMillis 사용 금지 구간.
- [ ] process_epoch: 프로세스당 마커(UUID) 저장, 불일치 시 epoch+1.
- [ ] 네트워크 상태 머신: ONLINE/OFFLINE/DELAYED/UNKNOWN — roles **값**에서 파싱(키 이름 의존 금지). OFFLINE에서 kill/replay/out-of-order/advance_time/export 전부 동작(공개 13-step은 step 8 이후 온라인 복구 없음).

### B. Probe 연결
- [ ] ProbeAdapter 5개 메서드 구현: runtimeState / onRunStarted / executeStep(→step 결과 JSONObject 17필드) / onRunFinished(→evidence ID 목록) / onRunAborted. public no-arg constructor.
- [ ] manifest `<meta-data org.scpc.r2.probe.ADAPTER_CLASS>` 만 등록 — service·permission 직접 선언 금지(AAR merger가 주입).
- [ ] AAR implementation(files(...)) 연결, 재구현·재서명 금지.
- [ ] public 진입 UI: content description 정확히 `SCPC_PROBE_IMPORT` / `SCPC_PROBE_RUN` / `SCPC_PROBE_EXPORT` 3개, 키보드·터치·UIAutomator 접근 가능, 오류·진행·결과 정직 표시. protected 경로와 **같은 adapter·같은 core** 호출.
- [ ] LAUNCHER category exported activity 존재 + 첫 실행 크래시 제로(runnerctl이 monkey로 재실행).
- [ ] 13 operation 전부를 roles 임의 값(불투명 문자열)에 대해 일반 처리. role 키 집합 하드코딩 금지(STABLE_VALUE↔STABLE_PREFERENCE처럼 표현이 바뀜).
- [ ] step 결과 17필드 정확히: step_id, event_id, operation, session_id, virtual_time, process_epoch, network_state, decision_state, selected/invalidated/preserved_state_ids, state_before/after_sha256, action(null 또는 4필드 완전 객체 — 빈 {} 금지), tombstone_ids, receipt_ids, auto_check_evidence_ids. additionalProperties:false — 여분 필드 즉시 invalid.
- [ ] state 해시 체인: step N after == step N+1 before; KILL·REPLAY에서 before==after.
- [ ] version_code는 **문자열** 타입으로 출력(int 넣으면 스키마 위반).

### C. Identity·계수
- [ ] runtime identity: 앱이 자기 package/version/서명 인증서 SHA-256/APK SHA-256/source_archive_sha256 보고. 수기 입력 금지 — 런타임 실측 또는 도구 자동 주입. legacy 3메서드(releaseBinding 등)는 미구현이 안전(APK 실측과 충돌 시 실행 거부).
- [ ] cumulative_invocations 카운터(0..60): model 미사용이면 0 유지, decision request 수를 모델 호출로 신고 금지.
- [ ] source_archive_sha256 순환 절단 절차: 소스 zip에서 생성물·해시 파일 제외 규칙 설계.

### D. Evidence·Export (참가자 구현 몫 — sample에 없음)
- [ ] evidence ID: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`, ID당 파일 1개, media_type 4종(json/txt/png/mp4)만.
- [ ] MISSION_ADAPTER evidence_path가 가리키는 실제 파일 전부 실존: evidence/state/{current_goal,facts,authority,invalidation,recovery,tombstones,context_selection,target_entity}.json + evidence/ledger/outcomes.json (예시 관례).
- [ ] EVIDENCE_INDEX: 모든 evidence → step_ids 최소 1개 매핑. 상대경로만(선행 /, ../, 공백·한글 금지).
- [ ] SAMPLE_EXPORT 총 비압축 20MiB 이하, file_manifest 5개 이상. 폴더는 FINALIZE 도구가 생성(미리 만들지 말 것).

### E. MISSION_ADAPTER.json
- [ ] role_bindings 10개 전부(PRIMARY_GOAL~EPHEMERAL_VALUE) × {mission_meaning, production_state_field, evidence_path}.
- [ ] operation_bindings 13개 전부 × {production_callback, observable_evidence}.
- [ ] source_paths 최소 2개(adapter 구현 + parity 테스트).
- [ ] assets에 1개만(≤1MiB), SOURCE.zip과 byte-for-byte 동일, build 복사본 미포함.

### F. 사용자 통제·E1-E4·comparison
- [ ] 앱 UI에서 새 값·정정·철회·삭제가 실제 계획·판단·상태 변경. 현재 지시 > 오래된 routine.
- [ ] E1→E4: 최소 4 episode, 3 session 경계, 1회 이상 실제 재실행, downstream causal change 2지점 이상, 합성 event+virtual time으로 30-40분 재현.
- [ ] full/claim-off comparison: 같은 snapshot bytes를 **물리적으로 분리된 두 저장공간**에 복제, mechanism on/off 외 전 조건 동일, 상호 오염 제로. 참가자 직접 구현. AUTO-CHECK에서 안 돌지만 C3 8점(CII 최대 단일 항목 + causal floor)의 유일한 입증 수단.
- [ ] 선택 permission 거부 경로에서도 E1-E4 완주 가능.
- [ ] 금지: 점수·PASS/FAIL·anchor류 출력, pack ID/surface string 분기, Probe 전용 모델·서버, restricted pack 보존, Runner URI 외 storage 접근.

### G. 제출물 7종
APP.apk(연습에 쓴 그 파일) / SOURCE.zip(adapter·test·MISSION_ADAPTER 포함, build·IDE cache 제외) / MISSION_AND_TECHNICAL_NOTE.pdf / INSTALL_AND_USE_GUIDE.pdf(first-reader 단독 재현) / BUILD_AND_SUBMISSION_INFO.md / SAMPLE_EXPORT/ / DEMO_VIDEO.mp4(≤3분). 문서 필수 포함 10항목의 첫째가 T+48 선언과의 일치.

---

## (3) Mission 설계에 걸리는 제약

채점 역학이 Mission 선택을 다음과 같이 강하게 제약한다:

1. **10개 semantic role이 자연스럽게 존재하는 도메인이어야 한다.** 도메인에 다음이 전부 유기적으로 있어야 함: 장기 목표(PRIMARY_GOAL), 목표 대상 entity(TARGET), 헷갈리기 쉬운 유사 entity(DISTRACTOR), 오래 유지되는 선호(STABLE_VALUE), 1회성 제약(ONE_OFF_VALUE), 정정 가능한 권위 값(CURRENT_AUTHORITY V1→V2), 부분 철회 가능한 범위(REVOKED/PRESERVED_SCOPE 쌍), 며칠 뒤 도착하는 결과(DELAYED_OUTCOME), 삭제 요구가 자연스러운 민감 값(EPHEMERAL_VALUE). 하나라도 억지스러우면 MISSION_ADAPTER 매핑과 mission_meaning이 어색해지고 Judge 검증에서 약점이 된다.

2. **13-step 시퀀스가 이야기로 성립해야 한다.** "설정→학습→새 세션에서 distractor 등장→판단→정정→부분 철회→삭제→오프라인→강제종료→중복·역순 이벤트→7일 경과→결과 도착→export"가 사용자 여정 E1-E4로 자연 번역되는 시나리오. 특히 **7일 뒤 delayed outcome이 다음 판단을 바꾸는 것이 도메인 본질**이어야 CORE-6이 산다(예: 반복 루틴·추적·계획류 도메인).

3. **완전 합성 + app-local이어야 한다.** 실제 계정·결제·발송·외부 서비스 금지 — 부작용은 preview/draft/simulation까지. 즉 "실제로 뭔가를 보내는" 도메인은 시뮬레이션 형태로 재설계 가능해야 함.

4. **오프라인이 기본 동작 모드여야 한다.** 공식 probe 후반 5 step이 전부 OFFLINE — 판단·복구·export가 네트워크 무관이어야 하므로 Mission의 핵심 가치가 서버 의존이면 구조적으로 불리. 온디바이스 결정론이 사실상 강제에 가까움(quota 60/80step 산술 포함).

5. **Signature mechanism은 '끌 수 있고 차이가 관찰되는' 형태여야 한다.** C3(8점, causal floor: C3<4면 CII≤9)는 claim-off 짝비교로 인과 이득 재현을 요구 — mechanism이 앱 전체에 녹아 있으면 끌 수 없고, 꺼도 차이가 안 보이면 이득 입증 실패. Mission 선언의 claim(C1)부터 "off 시 무엇이 나빠지는가"를 측정 가능하게 써야 함. CII 14+는 late-horizon과 unseen surface 이득 둘 다 필요.

6. **G7 모바일 필연성 3요소 전부**: 모바일 경험 필요성 + restart·local continuity + 별도 mobile constraint 1개 이상. 데스크톱으로도 되는 도메인은 gate 리스크.

7. **Q profile 균형 제약**: SELECTED-REVIEW 진입이 "Q total + 모든 profile buffer 동시 만족" — 특정 CORE가 죽는 도메인(예: distractor가 성립 안 하거나 삭제가 무의미한 도메인)은 총점이 높아도 선정 탈락 가능.

8. **30-40분 재현 + first-reader 재현성**: 여정이 복잡하면 INSTALL_GUIDE 단독 재현이 깨지고 SELECTED-REVIEW에서 anchor 확인 자체가 막힘. 화면 수 최소, 상태 전이 명확한 단순한 도메인이 유리.

**종합**: "개인 장기 루틴/계획을 학습해 반복 부담을 줄이되, 정정·삭제·지연 결과에 즉각 순응하는 온디바이스 assistant"류의 형태가 채점 구조와 정합. 채점기는 도메인의 화려함이 아니라 상태 lifecycle 의미론만 본다.

---

## (4) 빌드·실행 경로 (Windows, Android Studio 미설치)

### 설치 순서 (Day 0 최우선)
1. **JDK 17** (Temurin 등) — JAVA_HOME 설정. apkanalyzer·gradle·apksigner 전부 의존.
2. **Android cmdline-tools latest** → `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\` 배치, ANDROID_HOME/ANDROID_SDK_ROOT 설정.
3. `sdkmanager`로: `platform-tools`(adb), `platforms;android-35`, `build-tools;35.0.0`(정확히 이 버전 — apk_release_info.py가 경로 하드코딩), `emulator`, `system-images;android-35;google_apis;x86_64`(**google_apis 필수** — runnerctl이 `settings get global wifi_on/mobile_data`가 '0'/'1'을 반환하지 않으면 실행 거부).
4. **Python 3.10+** (3.13.9 기설치 확인됨) + candidate_kit requirements.txt.
5. AVD 생성(`avdmanager`) 후 emulator 부팅 확인, `adb get-state` = device.
6. **release keystore 생성**(keytool) — debug 서명 제출 금지. keystore는 자체 보관.

### 빌드 루프
- sample_app으로 스택 검증: `gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9 wrapper가 자동 다운로드).
- 자기 앱: compileSdk/targetSdk 35, minSdk 28, JVM 17, AAR를 app/libs로. AndroidX 최소화(sample은 의존성 제로)가 빌드 속도·리스크에 유리.
- release 서명 APK = APP.apk 후보.

### 리허설 루프 (반복 회전이 5일 성패 결정)
1. `make_local_integration_fixture.py`로 PROBE_INPUT.json + ASSIGNMENT.json 생성 (**15분 만료** — 생성 즉시 실행).
2. `runnerctl.py run` — Runner APK+APP.apk 설치, pm clear, content push, PREPARE/BEGIN/STEP/FINISH, step당 75초 폴링, KILL step은 호스트가 am force-stop+monkey.
3. 결과 3종 수집 → expected_relations 체크리스트 대조 → SELF_SCORE.py.
4. 2회차부터 PUBLIC_RUN 폴더 정리 필수(fixture·runnerctl 양쪽이 비어있지 않으면 거부).

### Windows 특유 패치/우회 (사전 확정 필요)
- **runnerctl.py collect()의 디렉터리 fsync가 Windows에서 PermissionError → 수집된 결과까지 삭제됨** (runnerctl.py:294-305). 대안 2택: (a) 로컬 사본 패치(디렉터리 fsync try 무시 — kit 원본 JSON은 seal 때문에 수정 금지지만 .py 실행 사본은 별도 폴더 복사 후 패치) (b) WSL2에서 실행(이 경우 adb 서버는 Windows adb.exe 경유 또는 `adb -H` 결정 필요 — 미검증 open item).
- apkanalyzer/apksigner가 확장자 없는 경로 호출이라 Windows 네이티브에서 .bat 미해석 가능성 — FINALIZE/VALIDATE 단계도 동일 계열 이슈, 조기 시험 필요.
- 에뮬레이터 성능: 75초 폴링 데드라인 — HAXM/WHPX 가속 확인.

### 제출 파이프라인
작업폴더(APP.apk, SOURCE.zip, MISSION_LOCK.json, PUBLIC_RUN/) → FINALIZE_SAMPLE_EXPORT.py(해시 전부 자동 계산) → VALIDATE PASS → **그 후 APK를 절대 재빌드·재서명하지 않음**(마지막 수정 시 공개 연습부터 재실행).

---

## (5) 리스크 레지스터 (치명도 순)

| # | 리스크 | 성격 | 근거 |
|---|---|---|---|
| 1 | Probe 전용 mock·사전계산 결과·pack ID/surface string 분기 | **실격**(parity gate FAIL) | 08 L22, L215-218, L232 |
| 2 | PROBE_RESULT에 PASS/FAIL·anchor·Q·cut류 출력 | 규칙 위반 | 06 L66-73, 08 L154-156 |
| 3 | 삭제 원문의 export·relaunch 후 재등장 / tombstone에 복원값 | G4 gate + CORE-2 전멸 | 03 L145-158, 05 L86 |
| 4 | 연습 후 APK 재빌드·재서명 제출(파일 지문 불일치) | 제출 무효성 | 03 L12-14 |
| 5 | SHA-256·지문·attestation 수기 입력/예시값 복사 | 명시적 검사 항목, 발각 구조 | 04 L208, 06 L48-55 |
| 6 | 화면·state·action·outcome·receipt 모순 | G6 evidence integrity FAIL — 기능 추가보다 일관성 훼손이 치명 | 03 L141-142 |
| 7 | export/판단의 네트워크 의존 → 공식 probe 마지막 5 step 전멸 | CORE-4/5/6 동반 붕괴 | 13-step에 온라인 복구 없음 |
| 8 | 'fake kill'(화면 초기화만) / 영속화 미흡 | 불인정 | 08 L176-177 |
| 9 | 실제 시계 사용 → ADVANCE_TIME 7일 점프 재현 불가 | CORE-6 0점 경로 | virtual_time 규약 |
| 10 | role 키·값 하드코딩 → 히든 변형에서 붕괴(anchor 3→1) | Q 대폭 손실 | 07 L88-90, roles 표현 가변 |
| 11 | 스키마 정밀도 실수: version_code int, action 빈 객체, 여분 필드, 대문자 hex | additionalProperties:false 즉시 invalid | PROBE_RESULT.schema |
| 12 | Windows runnerctl 결함 미대응 → 리허설 루프 자체 불가 | 검증 0회로 제출 | runnerctl.py:294-305 |
| 13 | full/claim-off 격리 오염 / comparison 미구현 | C3<4 → CII≤9 (11점 상실) | 03 L185-187, 05 L190 |
| 14 | 선택 permission 거부 경로 미구현 | E1-E4 확인 불가 판정 | 03 L9 |
| 15 | LAUNCHER activity 부재·첫 실행 크래시 | relaunch 실패로 run 전체 사망 | runnerctl monkey 의존 |
| 16 | 특정 Q profile 편중 → SELECTED-REVIEW buffer 탈락 | 상위권 진입 실패 | 05 L112 |
| 17 | INSTALL_GUIDE first-reader 재현 실패 | anchor 확인 차단 | 04 L69-77 |
| 18 | T+48 선언과 최종 문서 표현 불일치 | 문서 필수항목 1번 위반 | 04 L54-65 |

---

## (6) 5일 일정 스케치 (Mission 확정 후 기준)

**D1 — 환경 + 뼈대 관통**
- 오전: JDK17+SDK 설치, 에뮬레이터 부팅, sample_app 빌드·runnerctl 13-step 완주(runnerctl Windows 패치/WSL 결정 포함). *이날 리허설 루프가 안 돌면 전체 일정 붕괴 — 최우선.*
- 오후: 자기 앱 스캐폴드(release key 서명) + SampleCore 의미론을 기반으로 production core 골격(state 저장소·canonical JSON·해시 체인·process_epoch). adapter 연결해 13-step "형식적" 완주(스키마 valid한 PROBE_RESULT).

**D2 — Core 의미론 = CORE-4·3 (40점)**
- fact 모델(authority/scope/lifetime), CORRECT/REVOKE의 scoped invalidation(invalidated/preserved 정밀 분리), 세션 간 재사용+ONE_OFF 소모, distractor 배제 context 선택, OFFLINE→WAIT 정직 전이.
- 저녁: 13-step 재실행, expected_relations(A그룹) 기계 체크리스트 대조.

**D3 — CORE-5·6 (24점) + tombstone**
- idempotency ledger(REPLAY→CONFIRMED_COMPLETE), out-of-order 부활 차단(tombstone+version 비교), kill 무손실 복구(before==after), ADVANCE_TIME delayed outcome→판단 변경→ledger 반영.
- DELETE_FACT 비가역 + export 원문 부재 grep 검증.
- PRACTICE-A/B 리허설 + 자체 변형(다른 role 값·순서·제3 surface) 1세트 제작 — anchor 4 근거.

**D4 — 사용자 UI·E1-E4·comparison·evidence**
- 오전: 실제 사용자 화면(E1-E4 여정, 정정·삭제 UI, 사용자 통제) — Probe와 같은 core 호출 입증 구조.
- 오후: full/claim-off 격리 comparison 모드(분리 저장공간 복제) + evidence export 기능(evidence/state/*, ledger/*, receipt) + EVIDENCE_INDEX 정합 + MISSION_ADAPTER.json 정밀 작성.
- permission 거부 경로·offline·uninstall/reinstall 자가시험.

**D5 — 동결·문서·제출**
- 오전: 최종 기능 동결 → 소스 zip 확정(해시 순환 절단 절차) → 최종 서명 APK → **공개 13-step + 변형 재실행**(이후 APK 불변) → PUBLIC_PROBE_RESULT 확보.
- 오후: FINALIZE_SAMPLE_EXPORT.py → VALIDATE PASS → PDF 2종·MD(문서 필수 10항목, T+48 선언 문구 일치 확인)·DEMO_VIDEO(≤3분) → self-check 10항목 전량 수행 → 제출.
- 버퍼: 각 일자 저녁의 리허설 실패분 이월 처리. D5 오전까지 코드 변경 여지를 남기되, "마지막 빌드 후 반드시 공개 연습 재실행" 원칙 고정.

**일정 원칙**: 화면·기능 수 확장 금지 — README_FIRST '우선 완성 7항목'과 AUTO-CHECK 8관계를 완주 기준으로 삼고, 매일 저녁 runnerctl 리허설 1회 이상으로 회귀 검증. SELF_SCORE는 낙관 편향 도구이므로 expected_relations를 기계 체크리스트화해 evidence_ids로 판정 근거를 남긴다.