# SCPC 2026 AI 챌린지 — R1 / R2 소스

2026 Samsung Collegiate Programming Challenge : AI 챌린지(데이콘 운영) 1차 예선(R1)·2차 예선(R2) 제출 코드와 본선 발표 자료입니다.
참가자 닉네임 **SDH**. 외부 유료 LLM API·네트워크 호출 없이 모두 온디바이스/결정론 로직으로 동작합니다.

## 구성

```
data/                 # R1 (1차 예선) — Agent Harness
├── harness.py        #   최종 제출본 (Python 3.10+, 외부 의존성 없음)
├── harness_v*.py     #   개선 과정 버전들 (v1 → v30)
├── README.md         #   실행 방법·설계 설명
└── 2차/              #   R2 kit 분석 노트, Mission Lock
R2/                   # R2 (2차 예선) — Android 앱 "약지기"
├── chebi/            #   Kotlin 소스 (Gradle 단일 모듈, 단위 테스트 포함)
├── mission/          #   Mission 선언·기술 노트·설치 가이드·본선 발표 자료
├── practice_chebi/   #   직접 만든 공개 연습 입력(MED_*/PRACTICE_*)과 검증 스크립트
└── SUBMISSION_FINAL/ #   제출 문서 (BUILD_AND_SUBMISSION_INFO, 가이드 PDF)
```

## 이 저장소에 포함하지 않은 것

대회 참가 약관에 따라 **주최 측 배포 자료는 재배포하지 않습니다.** 아래 파일은 데이콘 대회 페이지에서 직접 받으세요.

- R1 데이터: `screening_tasks.jsonl`, `dev_tasks.jsonl`, `dev_answers.json`, baseline 노트북, `TERMS_GUIDE.md`
- R2 Candidate Kit: `SCPC2026_R2_CANDIDATE_RELEASE_v3` 전체 (공식 Runner APK, `scpc-probe-starter-*.aar`, `public_harness/`, 샘플 앱)
- 데이터에서 파생된 산출물: `submission*.csv`, 공식 Runner 실행 결과(`RUN_*`, `EVIDENCE_*`, `SAMPLE_EXPORT`)
- 앱 서명 키(`R2/keystore/`), 빌드 산출물(APK, `.gradle/`, `build/`), 데모 영상

## 실행

**R1** — `data/data/` 아래에 `screening_tasks.jsonl`을 두고 `python data/harness.py` 를 실행하면 같은 폴더에 `submission.csv`가 생성됩니다. 자세한 내용은 `data/README.md`.

**R2** — Kit의 `scpc-probe-starter-3.0.0-draft.aar`를 `R2/chebi/app/libs/`에 넣은 뒤 `./gradlew assembleRelease`. 서명 키가 없으면 debug 서명으로 빌드됩니다. 빌드·구조 설명은 `R2/SUBMISSION_FINAL/BUILD_AND_SUBMISSION_INFO.md`.

## 라이선스

`R2/chebi/LICENSE` 및 `THIRD_PARTY_NOTICES.md` 참고.
