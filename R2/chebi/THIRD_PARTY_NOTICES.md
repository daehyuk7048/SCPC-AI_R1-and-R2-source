# Third-Party Notices — 약지기 (SCPC 2026 R2 제출물)

이 앱이 사용하는 제3자 구성요소·도구와 라이선스/출처 고지입니다.

## 런타임 의존성

| 구성요소 | 출처 / 버전 | 라이선스 | 비고 |
|---|---|---|---|
| scpc-probe-starter-3.0.0-draft.aar (`app/libs/`) | SCPC 2026 R2 Candidate Kit (운영 제공물) | 대회 규정에 따름 | Kit에 포함되는 운영 제공 binary — 참가자 저작물이 아니며, 재현 빌드 의존성으로 원본 그대로 동봉 |
| Kotlin Standard Library | JetBrains Kotlin 2.x (Gradle 툴체인이 포함) | Apache License 2.0 | |
| Android SDK / platform API (`org.json` 포함) | Google, compileSdk 35 | Android SDK License | 플랫폼 제공 API만 사용 — 별도 AndroidX 라이브러리 의존 없음 |

## 빌드 도구

- Android Gradle Plugin 8.7.3, Gradle 8.9 — Apache License 2.0
- Eclipse Adoptium JDK 17 — GPLv2 with Classpath Exception

## AI 코딩 도구 사용 고지

- 본 제출물의 설계·구현·검증에 Anthropic Claude Code (Claude Fable 5 모델)를
  AI 코딩 도구로 사용했습니다. 미션 설계·코드·문서는 참가자의 지시·검토·승인 하에
  작성되었으며 최종 책임은 참가자에게 있습니다.
- 데이터 전송 범위: 소스 코드·대회 공개 Kit 문서·합성 데이터만 도구에 제공했으며,
  개인정보·실사용자 데이터는 포함되지 않습니다 (앱 데이터는 전부 합성).

## 백엔드

- 별도 백엔드 서버 없음 — 앱은 온디바이스로만 동작하며 네트워크 상태는 Probe
  계약(SET_NETWORK)의 가상 상태로만 다룹니다.
