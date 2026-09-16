# BUILD_AND_SUBMISSION_INFO.md — 약지기 (SCPC 2026 예선 2차)

- **참가자(데이콘 닉네임):** SDH · **Mission 이름:** 약지기 - 진화형 복약 관리
- **앱:** 라벨 "약지기", applicationId `com.sdh.chebi`, versionCode 1 / versionName 1.0

**앱 성격 고지(의료 가드):** 이 앱은 의료 조언·진단·복약 지시가 아니며, 용량·복용 여부를 스스로 정하지 않는다. 기록된 의사 지시의 최신 버전·기한·중단 scope를 기억·대조해 표시할 뿐이고, 복용은 항상 사용자의 최종확인으로만 확정된다. 처방·복용 기록 등 앱 데이터 일체는 완전 합성이다(합성 노인 페르소나 "대혁" — 실존 인물·실제 환자 데이터와 무관).

## 1. 소스 구조 개요

Gradle 단일 모듈(`:app`) Kotlin 프로젝트. 내부 패키지명 `com.sdh.chebi`는 개발 초기 명칭의 잔재이며, 화면·문서상 이름은 "약지기"다.

```text
chebi/
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradlew(.bat) · gradle/wrapper/            # Gradle 8.9 wrapper 동봉(별도 설치 불필요)
├── LICENSE · THIRD_PARTY_NOTICES.md           # 라이선스·제3자·AI 도구 고지
└── app/
    ├── build.gradle.kts                        # 빌드·서명 설정(§4)
    ├── libs/scpc-probe-starter-3.0.0-draft.aar # Kit 운영 제공물(§6)
    └── src/
        ├── main/AndroidManifest.xml            # POST_NOTIFICATIONS 권한, Probe ADAPTER_CLASS meta-data
        ├── main/assets/MISSION_ADAPTER.json    # 참가자 작성 어댑터 연결정보(소스 asset 원본)
        ├── main/java/com/sdh/chebi/
        │   ├── ChebiCore.kt          # production core "VG-Ledger": 기억·행동 원장, 온디바이스 결정론 판단(모델·난수·실시계 없음)
        │   ├── MedDomain.kt          # 도메인 파사드: UI 행동을 합성 이벤트로 만들어 Probe와 동일한 core.execute() 경로로 전달
        │   ├── ChebiProbeAdapter.kt  # 공식 Probe Mode 어댑터(공개 UI 실행과 같은 실행부), per-step evidence 실파일 기록
        │   └── MainActivity.kt       # E1–E4 여정 UI, 공개 Probe 3버튼, 알림권한 게이트, 의료 가드 고지
        └── test/java/com/sdh/chebi/  # 단위 테스트 43개
            ├── ChebiCoreTest.kt          # core 의미론 테스트
            ├── UiFlowReplayTest.kt       # UI 흐름을 이벤트로 재생하는 회귀 테스트
            └── SameAdapterPathTest.kt    # 공개 UI와 protected Probe가 동일 실행부를 쓰는지 검증
```

## 2. 요구 도구 (소스 파일에서 실확인한 버전)

| 도구 | 버전 | 근거 |
|---|---|---|
| JDK | 17 (Eclipse Temurin 권장) | `app/build.gradle.kts` compileOptions/kotlinOptions = 17 |
| Gradle | 8.9 (wrapper 동봉) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 | 루트 `build.gradle.kts` |
| Kotlin | 2.0.21 | 루트 `build.gradle.kts` |
| Android SDK | compileSdk 35 / targetSdk 35 / minSdk 28, Platform 35 | `app/build.gradle.kts` |
| build-tools | 35.0.0 (`apksigner`, 자체 서명 시) | Kit 요구사항과 동일 |

의존성은 Kit AAR + Android 플랫폼 API가 전부이며 런타임 외부 라이브러리가 없다(테스트 전용: junit 4.13.2, org.json 20240303 — 첫 빌드 시 google()/mavenCentral() 접근 필요). **주의:** AGP는 비ASCII 문자가 포함된 경로에서 빌드를 거부할 수 있으므로 소스는 ASCII 경로에 압축 해제한다(참가자 Windows 환경 실측). SDK 위치는 `ANDROID_HOME` 환경변수 또는 소스 루트의 `local.properties`(`sdk.dir=...`)로 지정한다.

## 3. 빌드 절차

```text
# 소스 루트에서 (Windows는 gradlew.bat)
gradlew :app:assembleRelease
# 산출물: app/build/outputs/apk/release/ 아래 APK
# 단위 테스트: gradlew :app:testDebugUnitTest
```

## 4. 서명 — keystore는 제출물에 포함되지 않음

Kit 규정대로 signing private key·keystore·비밀번호는 참가자가 소유·보관하며 SOURCE.zip을 포함한 어떤 제출물에도 넣지 않는다.

`app/build.gradle.kts`의 서명 방식(파일 실확인): 소스 루트 **밖** 상대경로 `../keystore/keystore.properties`를 `java.util.Properties`로 읽어, 파일이 존재하면 그 안의 `storeFile`·`storePassword`·`keyAlias`·`keyPassword` 4개 값으로 release signingConfig를 구성한다. 파일이 없으면 서명 설정 블록 자체를 만들지 않으므로 **secret 없이도 빌드는 그대로 성공**하고 unsigned APK가 나온다.

심사측이 재현 빌드에 자체 keystore로 서명하는 방법(둘 중 하나):

1. 소스 루트의 상위 폴더에 `keystore/keystore.properties`를 만들고 위 4개 키에 자체 keystore 경로·값을 기입한 뒤 `:app:assembleRelease` 실행 → 서명된 APK.
2. 그대로 unsigned APK를 빌드한 뒤 build-tools 35.0.0의 apksigner로 서명:
   `apksigner sign --ks <자체 keystore> --out APP.apk app-release-unsigned.apk`

## 5. 재현성 (byte-reproducible build)

core는 실시계·난수·모델을 쓰지 않는 결정론 구현이고 release 빌드는 minify 없이 고정 입력만 사용하므로, **같은 소스·같은 도구 버전(§2)·같은 keystore로 재빌드하면 APK의 SHA-256이 동일**하다. 참가자 환경에서 clean 후 재빌드로 반복 확인했다. 확인 명령:

```text
gradlew clean :app:assembleRelease
# Windows PowerShell
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\release\app-release.apk
# macOS/Linux
sha256sum app/build/outputs/apk/release/app-release.apk
```

APK 전체 해시에는 서명 블록이 포함되므로, 심사측이 자체 keystore로 서명하면 제출 APK와 전체 해시는 달라진다. 이 경우 unsigned 산출물끼리 비교하거나 `apkanalyzer`로 내용을 대조하면 된다. SHA-256·인증서 지문 값 자체는 Kit 규정에 따라 문서에 수기로 기재하지 않는다 — 제출 완성 도구가 실파일에서 계산한다.

## 6. Kit 제공 AAR

`app/libs/scpc-probe-starter-3.0.0-draft.aar`는 SCPC 2026 R2 Candidate Kit에 포함된 **운영 제공물의 원본 그대로**이며(무수정 동봉), 참가자가 제출하는 binary가 아니다. 재현 빌드의 컴파일 의존성이므로 SOURCE.zip에 포함했다.

## 7. AI coding tool·라이선스 고지

상세는 소스 루트의 `LICENSE`와 `THIRD_PARTY_NOTICES.md` 참조. 요지: 본 제출물의 설계·구현·검증에 AI 코딩 도구 **Anthropic Claude Code**를 사용했으며, 미션 설계·코드·문서는 참가자의 지시·검토·승인 하에 작성되었고 최종 책임은 참가자에게 있다. 도구에 전송한 데이터 범위는 **소스 코드·대회 공개 Kit 문서·합성 데이터뿐**이며 개인정보·실사용자 데이터는 없다(앱 데이터는 전부 합성). 런타임 제3자 구성요소는 Kit AAR, Kotlin 표준 라이브러리(Apache 2.0), Android 플랫폼 API가 전부다.

## 8. model/backend/endpoint — 없음

이 앱은 온디바이스 결정론 구현이다. 외부 model·backend·network endpoint를 사용하지 않으며(어댑터 신고값: modelConfigured=false, 모델 호출 0회), 네트워크 단절·회복은 Probe 계약(SET_NETWORK)의 가상 상태로만 다룬다. 따라서 동결 대상 backend 배포본(immutable deployment)·routing·config가 존재하지 않고, 평가에 심사자의 개인 계정·API key·유료 구독이 필요하지 않다.

## 9. 제출물 7종과 생성 방법

| 제출물 | 생성 방법 |
|---|---|
| `APP.apk` | 본 소스에서 §3 빌드 + §4 참가자 keystore 서명 |
| `SOURCE.zip` | 사람이 작성한 source·build/config·license 자료만 압축 — `build/`·`.gradle/`·keystore 제외, `MISSION_ADAPTER.json`은 `app/src/main/assets/`의 소스 원본 1개만 포함 |
| `MISSION_AND_TECHNICAL_NOTE.pdf` | 참가자 작성 — 동결된 Mission 선언과 일치하는 Mission·E1–E4·architecture·Signature mechanism(VG-Ledger)·mobile counterfactual |
| `INSTALL_AND_USE_GUIDE.pdf` | 참가자 작성 — 처음 보는 심사자가 설치→Reset→run→restart→export→comparison을 재현하는 절차 |
| `BUILD_AND_SUBMISSION_INFO.md` | 본 문서 |
| `SAMPLE_EXPORT/` | 작업폴더에 `APP.apk`·`SOURCE.zip`·**데이콘 발급 `MISSION_LOCK.json`(무수정 보관본)**·`PUBLIC_RUN/`(공식 Runner의 공개 13단계 실행 결과 `PROBE_RESULT.json`, `public_harness/make_local_integration_fixture.py`로 준비한 `PROBE_INPUT.json`, 앱 export 기능이 남긴 `evidence/`)를 모은 뒤 `candidate_kit/FINALIZE_SAMPLE_EXPORT.py SAMPLE_EXPORT` 실행 — index 3종·RUNTIME_IDENTITY·SHA-256은 도구가 자동 생성하며 수기 입력하지 않음. 완성본은 `VALIDATE_SAMPLE_EXPORT.py`(읽기 전용)로 재확인 |
| `DEMO_VIDEO.mp4` | 앱 화면 녹화(3분 이내) — E1–E4·변화·restart·통제를 보여 주는 참고 영상이며 실제 APK 확인을 대신하지 않음 |