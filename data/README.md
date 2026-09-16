# SCPC 2026 Final — Agent Harness (harness.py)

DACON SCPC 2026 Final 제출물입니다. `harness.py` 단일 파일로, task JSON(개인 기기 agent 요청)을
읽어 요구되는 answer JSON(focal_id / target / control / content_scope / policy / plan_events)을
결정론적으로 생성합니다. 외부 모델·API 호출은 없으며, 제공된 `FixedSLMClient` facade를 보조
evidence로만 사용하고 모든 answer 필드는 harness 로직이 직접 만듭니다.

**동봉된 `submission.csv`는 Public 리더보드 최고 점수 제출본이며, 아래 명령 한 번으로
byte 단위 동일하게 재생성됩니다.**

## 1. 실행 명령어

```bash
python harness.py
```

인자·환경변수 없이 위 한 줄이 전부입니다. 실행하면 스크립트와 같은 폴더에 `submission.csv`
(DACON 업로드 형식, 단일 셀 JSON)가 생성되고, dev 데이터가 있으면 자체 점검
(focal/target/control raw-match)도 함께 출력됩니다.

## 2. 필요한 파일 배치

```
작업폴더/
├── harness.py               # 본 제출 코드 (참가자 작성 파일은 이것 하나입니다)
├── screening_tasks.jsonl    # 주최측 공개 데이터 (필수)
├── dev_tasks.jsonl          # 주최측 공개 데이터 (선택 — dev 자체 점검용)
├── dev_answers.json         # 주최측 공개 데이터 (선택 — dev 자체 점검용)
└── submission.csv           # 실행 결과로 생성됨
```

데이터 파일은 `harness.py` 기준 `.`, `./data`, `../data`, `..` 네 위치에서 자동 탐색하므로,
공개 데이터 폴더 구조를 그대로 두어도 됩니다. `screening_tasks.jsonl`만 있으면 실행됩니다.

## 3. Python 버전 및 의존성

- **Python 3.13.9**에서 개발·검증했습니다. `3.10+`이면 동작합니다(사용 문법: `X | Y` 타입 표기).
- **외부 의존성 없음** — 표준 라이브러리(`csv`, `json`, `re`, `pathlib`, `typing`)만 사용합니다.
  `pip install`이 필요한 패키지가 없으며, 네트워크 접근도 하지 않습니다.

## 4. submission.csv 재생성 방법

```bash
python harness.py    # 같은 폴더에 submission.csv 생성
```

생성된 파일은 동봉된 최고 점수 제출본과 **byte 단위로 동일**합니다(제출 전 2회 연속 실행
diff로 검증). 별도의 전처리·후처리 단계가 없습니다.

## 5. 난수·샘플링 및 재현 조건

- 코드 경로에 **난수·샘플링·시간 의존이 전혀 없습니다** (`random`/`time` 미사용, dict/set
  순회 순서 의존 없음). 동일 입력에 대해 항상 byte 단위 동일 출력을 냅니다.
- 제출 meta의 `temperature=0.0`, `seed=42`는 규정상 선언 값이며, 실제 코드에는 확률적 요소가
  없어 seed 설정이 결과에 영향을 주지 않습니다.
- task 처리 순서는 `(session_id, turn_index, task_id)` 정렬로 고정되어 있습니다. 검증 환경처럼
  `FinalHarness.answer_task(task, session)`을 stream 순서로 직접 호출해도 포함된 로컬 runner와
  동일한 답안이 나오는 것을 확인했습니다(700/700 일치).

## 구조 (baseline 권장 구조와 동일)

`FinalHarness.answer_task(task, session)`이 task 하나를 받아 answer JSON 하나를 반환합니다.
검증 환경처럼 task stream 순서로 `answer_task`를 직접 호출해도 되고, 포함된 로컬 runner
(`run_harness`)를 사용해도 동일한 답안이 나옵니다.

| 구성 요소 | 역할 |
| --- | --- |
| `prepare(tasks)` | 실행 상태 초기화. stream 안의 `persistent_memory_write` profile을 미리 적재해, 기록 turn보다 앞서 정렬된 recall도 profile을 찾을 수 있게 합니다(장기 메모리 의미론). |
| `choose_focal` | marker task는 `focal_resolution_trace`의 phase→marker→ref 사슬을 따라, non-marker task는 visible history 문장이 어느 후보 WM 코드를 확정하는지 파싱해 중심 object를 정합니다. |
| `decide_control` | proceed / amend / hold / ask 판단(아래 상세). |
| `infer_target` | control과 정합적인 최종 수신처/채널/저장소 결정(아래 상세). |
| `build_content_scope` | mode·allowed/excluded_fields·확인 필요 여부. |
| `build_policy` | risk_flags·violations·requires_confirmation. |
| `build_plan_events` | (control, scope) 템플릿 기반 단계 계획. args는 공개 ontology의 의미 bucket만 사용합니다. |
| `update_session_memory` | 현재 task의 memory write와 마지막 결정을 세션에 반영합니다. |

`FixedSLMClient.summarize_task()`의 결과는 `audit_tags` 보조 신호로만 결합하며, 채점 대상
필드의 판단 근거는 전부 task 구조(records, objects, trace, history)입니다.

## 판단 로직 요약

### control — proceed | amend | hold | ask
1. 프롬프트 마지막의 **"단," 교정 절**의 의도를 일반적·패러프레이즈 강건한 의미 검출기로
   분류합니다 (내부 처리 유지→proceed, 전제 붕괴→hold, 확인 우선→ask, 축소·요약→amend).
2. record 수준의 하드 블록(동의 철회, safety mode, 보안 경고)은 가족 로직 이전에 일괄
   hold를 강제합니다.
3. "단," 절이 없어도 visible history에 확인-우선 제약이 명시된 turn은 ask로 처리합니다.
4. 중립 action 절은 프롬프트 전체에서 가족(MATERIAL / LADDER / FASTPATH / LATEST / DEVICE)
   으로 분류한 뒤, 가족별 record→control 맵으로 결정합니다. record 우선순위는 가족별로
   다릅니다: **LADDER**(guardrail ladder)는 authority 상태가 1순위 블로커이고,
   **MATERIAL / LATEST**(무엇을 보낼지 선택)는 명시적 share-boundary 지시가 authority
   상태보다 우선합니다 — boundary record 자체가 행동 지시이기 때문입니다.

### target
control과 정합적으로: proceed+내부 갱신→`memory_store`, amend→`resolved_target`, 명시적
사용자 교정 ask/hold→`user`. `approved_channel_or_visible_recipient` 모호성은 focal object의
`attrs.recipient`가 곧 승인 채널입니다(전 채널 uniform). `dispatch_authority_check` 상태와
`target_changed_after_turn` 재지정을 함께 처리합니다. **세션 메모리**: 앞선 turn의
`persistent_memory_write` profile을 `memory_key`로 저장해 두고, recall task는 문맥에 따라
저장된 채널(조명→`dusk_room`, 검진/복약→`health_channel`, 지난 성공 재사용→
`last_success_target`, 사내 규정→`approval_channel`, 그 외→`preferred_channel`)로 라우팅합니다.

### content_scope / policy / plan_events
`mode`는 control+구조 템플릿(hold→none, amend→redacted, proceed→status_only/summary/raw,
ask→기본 summary에 정책·경계·수신처 신호에 따른 세분화)으로 정합니다. `excluded_fields`는
focal의 `attrs.contains`와 세션 공유 정책으로, `risk_flags`는 구조 신호로 도출합니다.
plan은 (control, mode)별 verb 시퀀스 템플릿이며 안전 확인 단계가 실행 단계 앞에 오도록
구성합니다. ask의 세부 args(`clarify_precondition` vs `route_resolution_required`)는 계산된
전제-변경 신호를 따릅니다.

## 일반화·규정 준수

- **하드코딩 없음.** task_id/session_id 답안표가 없으며(id 리터럴 감사 0건), 모든 규칙은
  task 구조 또는 반복되는 의미 패턴에 걸립니다. 기기 설정 이름 같은 값도 focal object의
  attrs에서 읽습니다.
- 규칙은 **공개 dev 분할로만** 역설계했습니다(필드 의미와 템플릿 구조 학습 용도).
- **미지 값 강건성**: record 값 비교는 의미 클래스 substring(예: `redacted*boundary`,
  `*pending*`, `*incomplete*`, `*confirmed*`, local/internal boundary 계열)으로 정규화되어,
  이름이 다른 새 변형 값에도 같은 의미로 동작합니다. focal 해석은 phase/marker 이름 변형,
  누락된 phase(공개된 `latest_phase_rule`로 재유도), ref-code 표기 변형에 대해 우아하게
  강등되며, 비정상 입력(빈 records, null, 비문자열 값)에도 스키마 유효 답안을 냅니다.
- `meta`: `fixed_slm_policy=local_fixed_slm_only`, `model_id=scpc-final-fixed-slm-local-facade`,
  `uses_external_api=false`, `temperature=0.0`, `seed=42`.

## 로컬 dev 점수 (baseline 노트북 채점기, 공개 dev 120개)

| overall | focal | target | control | content_scope | policy | plan |
|---|---|---|---|---|---|---|
| **0.960** | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 |

(`semantic_response`/`counterfactual`은 서버 전용 채점이라 로컬 상한이 ≈0.96입니다. 제공된
baseline harness는 같은 채점기에서 0.088입니다.)

## 알려진 한계

- 이전 세션에서 기록된 profile이 현재 stream에 없는 person-recall은 저장 채널이 task 어디에도
  없어, 요청 문맥 기반 최선 추정 상수로 강등됩니다(구조상 불가피한 유일한 추측 지점).
- 일부 결정은 공개 dev가 두 대안을 구분하지 못하는 지점이 있으며, 이 경우 형제 분기의
  검증된 동작과 절 의미론에 근거해 일관된 쪽을 선택했습니다.
