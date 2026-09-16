# 약지기 선언에서 파생된 구현 계약 (동결 문면 ↔ 코드 정합 의무)

선언 V3(제출본)의 동결 문구를 지키기 위해 채비 코드베이스에서 반드시 변경·신설해야 할 항목.
검토 6번(구현 정합) 판정 기반 — 동결 후 구현 불가 판명은 재앙이므로 제출 전 확정.

1. **약별 지시 버전 (CRITICAL)** — E1 "약 단위의 지시 버전이 권위".
   core의 current_authority를 전역 단일값 → **entity별 map**으로 변경. isValid의 권위 게이트와
   correctFromRoles·reconcileOutOfOrder의 stale 판정이 항목의 entity 기준 권위와 비교하도록.
   (전역 유지 시: 항생제 정정이 혈압약 기억 전부를 기각하는 설계상 확정 버그.)

2. **기한형 EPHEMERAL** — E1 "기한 내 세션마다 재사용, 기한 경과 시 만료".
   lifetime "TERM" 신설(첫 사용 소모형 ONE_OFF와 별개): valid_until 필드 + isValid에 시간 술어
   (item.valid_until < state.virtual_time → invalid). '이번 주만 항생제'는 TERM으로 저장.
   기존 ONE_OFF 소모 의미론은 유지(다른 용도 — 예: 1회용 처방전 제출).

3. **잔량 정수** — E2 "복용 확인 1회당 잔량 정수 감산, 임계치 이하 리필 draft".
   core 변경 불요 — 도메인 레이어 패턴(채비 travel_minutes_40→45와 동일): 복용 확인 =
   stock_N→N-1 UPSERT, 임계치 비교·draft 생성은 도메인이 결정론 규칙으로 수행.
   임계치는 처방 학습 시 기억에 기록(threshold_N).

4. **승인된 draft만 도착** — E2 "승인된 draft는 … 도착 이벤트로 반영".
   승인 = pending 등록 트리거: [draft 승인] 행동이 OUTCOME 클래스 role을 실은 UPSERT를
   emit(기존 outcome-decl- 경로) → 등록. 미승인 draft는 등록 자체가 없어 도착하지 않음
   (부정 약속은 동결 문면에 없음 — 구조로 자연 성립).

5. **중단 시 리필 대기 취소** — E4 "미도착 리필 대기의 취소".
   pending_outcomes 항목에 entity(약) 링크 추가(등록 시점 target 기록). REVOKE(복용 중단)가
   해당 entity·scope에 연결된 미도착 pending을 대기열에서 제거하고 취소를 원장에 기록.

6. **권한 재허용** — 대상 사용자 "허용·거부·회수·재허용 … 유효성 재판정".
   UPSERT의 SCOPE_GRANTED가 revoked_scopes에서 해당 scope를 제거(재허용 의미론).
   isValid는 revoked_scopes를 동적 참조하므로 남은 ACTIVE 기억은 자동 재유효화되고,
   회수 시 INVALIDATED된 항목은 재학습으로만 복귀(부활 아님 — tombstone 원칙과 무충돌).

7. **최종확인 별도 이벤트** — E2 "최종확인은 별도 이벤트로 원장에 기재".
   도메인 [복용 최종확인] = 별도 emit(원장 기재)로 프리필과 구분 관찰. CORE-3 지표(확인 감소)와
   분리 집계.

8. **오프라인 이원화** — 모바일 절 "로컬 판단은 오프라인 지속, 리필 draft 전송만 보류·1회 재시도".
   probe 경로의 WAIT 의미론(숨은 변형 안전)은 유지하되, 도메인 로컬 판단(체크리스트 확정·복용
   기록)은 UPSERT 계열로 emit되어 오프라인에서도 진행. 전송 성격 결정(리필 draft)만
   REQUEST_DECISION → WAIT/재전달 경로 사용. 화면 문구가 이 구분을 표시.

9. **알림 권한 실경로** — Android 13+ POST_NOTIFICATIONS runtime permission을 manifest·UI에
   실제 경로로 구현(채비의 위치 권한 역할 대체). targetSdk 35 기준 runtime 요청 필수라 정합.
   Probe 경로의 REVOKE_SCOPE 합성 이벤트와 이원 설계(채비 계약 5와 동일 원칙).

10. **의료 프레이밍 가드** — 앱 화면·문서 어디에도 용량 결정·복약 지시 표현 금지. 체크리스트는
    "기록된 지시의 표시·대조", 버튼명은 "복용 기록"(행위 통제 아님). 데모·기술노트에 "의료 조언
    아님·완전 합성" 문구 고정.
