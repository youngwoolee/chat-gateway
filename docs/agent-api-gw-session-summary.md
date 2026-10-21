# Agent API G/W 설계 — 세션 방식 핵심 요약

2026-10-20 코드·FE 연동 문서 대조본. **[반영]은 이번 코드에 적용한 변경**입니다. G/W는 인증·중계, B/E는 대화·실제 업무를 담당합니다. 내부 API·신규 경로·기한은 아래 미확정 항목을 합의한 뒤 적용합니다.

## 1. 구조와 책임

```text
FE → G/W(MVC + Tomcat + Spring Cloud Gateway) → Agent B/E → 대화·업무 처리
        ├─ Eureka → agent-session-service → Redis / Hazelcast
        └─ PFC/MCI → 로그인·추가 인증·홈페이지 연동 쿠키
```

| 구성 | 책임 |
|---|---|
| G/W | 세션 인증·연장, 추가 인증 검증·소비 요청, 검증된 신원 전달, HTTP/SSE 중계 |
| B/E | thread 소유권·이력, 단계별 입력·선택 화면·resume, 업무에 필요한 인증 확인, 실제 실행·중복 방지·결과 관리 |
| 세션 서비스 | 세션·인증 문맥·인증 요청·소비 이력 저장 및 원자 변경. 저장소 장애 전환·정합성 |

G/W는 대화 이전 API·금융 업무 실행 Adapter를 제공하지 않으며 `TRANSITION`·`EXECUTING`을 관리하지 않습니다. **인증 결과의 1회 소비와 금융 업무의 실행 중복 방지는 서로 다른 책임**입니다.

## 2. 세션·로그인

| 항목 | 규칙 |
|---|---|
| 세션 ID | 신규 CSPRNG 32바이트 base64url. restart는 이전 SID를 비밀키로 한 HMAC-SHA256으로 재시도 가능한 새 SID 도출. 저장소에는 해시만 보관 |
| 쿠키 | `__Host-sh-session`: HttpOnly, Secure, SameSite=Lax, Path=/, Domain 없음 |
| 세션 정보 | 신원·회원/게스트·채널·ACTIVE/REVOKED·활동/만료 시각·`guestSubject`·`threadId` |
| 만료 | 마지막 인정 활동 + 5분. 유효성 확인·연장은 세션 서비스에서 원자 처리 |
| 인정 활동 | 세션 POST, 채팅·도구 요청. **[반영] 단계별 입력·선택 화면 클릭 로그도 포함**. MFA resume는 채팅 활동으로 처리 |
| 연장 제외 | GET 조회·heartbeat·SSE 수신·단순 창 열기 자체. 다만 창을 열 때 세션 POST를 호출하면 인정 활동으로 연장됩니다. 같은 활동 키 재시도로 추가 연장하지 않음 |
| threadId | FE 생성 후 세션에 저장. 회원 전환/연장 시 유지. B/E 소유권·이력 관리. 인증 수단은 아님 |

**일반 인증 흐름**

1. G/W가 세션 쿠키로 서버 상태·만료·채널을 확인합니다. 유효 회원이면 새 `_O`가 있어도 기존 신원을 사용합니다.
2. 신규 세션 생성 또는 `/api/chat`의 `X-Requested-Login: true` 요청에서만 `_O` 3종을 PFC로 검증합니다.
3. 게스트→회원은 세션 서비스의 `replace-guest`로 기존 세션 폐기·회원 저장을 한 번에 처리합니다. 새 SID를 발급하고 실제 사용한 `_O`를 삭제합니다.
4. 기존 게스트 ID는 `guestSubject`에 유지해 B/E에 전달합니다. B/E가 해당 thread의 게스트 소유권을 확인해 회원에게 연결합니다. 다른 회원 소유면 거절합니다.

기존 대화에서 세션 부재·만료·폐기는 401이며 `_O`로 자동 복구하지 않습니다. FE는 새 대화 선택 시 **기존 SSE 종료 → 새 thread 생성 → 새 키로 POST restart** 순서로 진행합니다. 유효 회원 세션의 자동 계정 변경은 지원하지 않습니다.

게스트→회원 전환은 POST `/api/chat`의 `X-Requested-Login: true`에서만 수행합니다. 헤더 생략/false는 `_O`가 있어도 게스트를 유지합니다. true에서 `_O` 누락·검증 실패는 오류이며 기존 게스트 유지, B/E 호출 없음. 유효 회원은 기존 인증을 사용합니다. 헤더는 단일 true/false만 허용하며 중복·다른 값은400입니다. threadId·channelId·기존 게스트 식별자를 유지하고 PFC PID로 회원 세션을 생성하며 SID를 교체합니다. 이 헤더는 B/E에 전달하지 않습니다. `/api/auth/session`은 기존 게스트를 전환하지 않지만 최초 생성 시 `_O` 검증으로 새 회원 세션을 만들 수 있습니다.

현행 코드에서 세션 POST는 자연 만료된 ACTIVE 기록이 남아 있으면 새 세션을 생성할 수 있습니다. 이는 기존 세션의 부활이 아닙니다. 폐기(REVOKED)·알 수 없는 SID는401입니다. 유효 세션의 새 대화는 restart를 사용하며, 만료·쿠키 유실은 재인증/신규 생성 절차를 따릅니다. 신규 생성 요청의 동일 키 재시도는 새 SID를 재발급하지 않고400가 될 수 있으므로, 모든 POST가 이전 응답을 재생한다는 의미는 아닙니다.

쿠키 유실 시 회원은 플랫폼 재인증 후 POST session에 기존 threadId를 전달해 **새 회원 세션**을 만듭니다. B/E가 동일 회원의 대화인지 확인해야 합니다. 재인증할 수 없는 게스트는 새 threadId로 새 대화를 시작하며 기존 guestSubject를 복원하지 않습니다. threadId만으로 인증/SID를 조회·복원하지 않습니다. 쿠키가 없으면 이전 세션을 특정해 폐기할 수 없으므로 만료 정책을 따릅니다. iOS 웹뷰 쿠키 동작은 실기기 검증이 필요합니다.

## 3. FE API

| 방식·경로 | 용도 |
|---|---|
| POST `/api/auth/session?channelId=<uuid>&threadId=...` | 신규 인증·기존 세션 재사용 (게스트→회원 전환 없음) |
| POST `/api/auth/session/restart` | `{threadId}`로 새 대화. 유효 회원 신원 승계·새 SID, 게스트는 새 신원. 200 + 세션 응답/쿠키 |
| POST `/api/chat` | 채팅·SSE. **[반영] resume도 이 경로 사용** |
| POST `/api/chat/threads/{tid}/tools/{tool}` | 도구 요청 중계 |
| GET `/api/chat/threads`, `/api/chat/threads/{tid}/messages`, `/api/chat/threads/{tid}/artifacts/{aid}` | 목록·이력·아티팩트 |
| POST `/api/click` | `{threadId, scenarioId, state, component}` 검증 후 세션 연장 및 Gateway JSON 로그 기록, 200 |

POST는 동일 Origin·Fetch Metadata를 검사하며 자동완성 조회 외에는 `Idempotency-Key`가 필요합니다. 클릭마다 새 키를 만들고 재전송에는 같은 키를 사용합니다. 클릭 API는 유효 세션과 필수 식별자를 검사하며, 이 호출만으로 업무 실행·소유권 변경을 허용하지 않습니다. Gateway가 세션·threadId를 검증하고 활동을 연장한 뒤 JSON 애플리케이션 로그를 기록하며 200와 만료시간 헤더를 반환합니다. B/E는 호출하지 않습니다. 기존 수집기 형식의 AUDIT JSON 파일에 message.name=click, chatbot=new-chatbot, sessionId=threadId, channelId, userId=검증된 회원 PID(게스트 null), login, 선택 buttonInfo를 기록합니다. 이탈 분석용 event·receivedAt·key·threadId·userType·scenarioId·state·component도 message 안에 보존합니다. 인증 쿠키·인증 헤더와 임의 최상위 입력은 기록하지 않습니다. buttonInfo는 buttonType/buttonNm/buttonUrl/buttonUtterance/phoneNumber 문자열만 허용하며 component·버튼 필드에 민감정보를 보내지 않습니다. 같은 키 재시도는 세션을 추가 연장하지 않지만 로그는 중복 기록될 수 있어 수집/분석 시 channelId·threadId·key로 식별합니다. 200는 로그 호출 완료이며 수집 시스템의 영구 적재 완료를 보장하지 않습니다. logging.jsonfile.path 아래 ${spring.application.name}-kafka-json.log에 기록하며 일별 분리·14일 보관합니다. 큐 포화 시 대기하며 종료 시 최대 5초 배출합니다. 200은 수집기의 영구 적재를 보장하지 않습니다. 상태값과 시나리오 ID는 필수 문자열, component는 식별 문자열/객체이고 추가 필드는 기록하지 않습니다. 본문 최대1MiB. 클릭에는 실행 문맥을 발급하지 않습니다.

신규 세션은 허용된 채널이 필요하고 기존 세션은 채널 생략이 가능합니다. 채널 불일치는 403입니다. 신규 생성 시 threadId를 생략하면 GW가 UUID를 생성해 응답합니다. 유효 세션 재사용/채팅/도구/클릭에서 다른 threadId를 지정하면400이며, 새 대화는 restart로 교체합니다. 채팅 body는 문자열 `threadId`를 포함하고 최대 1MiB이며 원문을 중계합니다. 별도 resume 경로가 필요하면 FE/B/E와 확정합니다.

세션 응답은 `{userType, expiresAt, serverTime, threadId}`, 만료 헤더는 `X-Chat-Auth-Expires-At`·`X-Server-Time`이며 시각 값은 UTC epoch milliseconds입니다. GET 세션 조회와 `/api/auth/activity`는 제거했습니다. FE는 세션 POST 응답/대화·클릭 응답 헤더의 기한을 사용합니다.

### 새 대화 restart

`POST /api/auth/session/restart`, body `{threadId: 새 대화 ID}`, `Idempotency-Key` 필수. 고객번호·회원 여부·채널은 body로 받지 않고 서버 세션에서 승계합니다. 세션 서비스가 유효성 확인 → 기존 폐기 → 새 세션 저장 → 재시도 기록을 원자 처리합니다. 회원 신원만 유지하고 guestSubject·업무별 추가 인증은 새 대화로 승계하지 않습니다. 게스트는 새 신원을 만듭니다. 최초 restart 만료는 처리 시각+5분입니다.

`_O` 검증과 PFC 호출은 없으며 restart 자체에서는 `_I`를 발급하지 않습니다. 이후 회원 채팅/도구 응답에서 기존 규칙으로 발급합니다. 기존 이력은 B/E에 남습니다. 만료·부재·폐기된 세션은401, 같은 thread 또는 같은 키의 다른 thread 요청은400입니다.

동일 key+thread 재시도는 이전/새 쿠키 모두 같은 **아직 유효한 후속 세션**을 반환하고 만료를 연장하지 않습니다. 최초 교체로 폐기된 이전 SID는 이 재시도에만 허용하며 채팅에는 사용할 수 없습니다. 후속 세션이 만료/폐기되면 재시도도401입니다. GW는 이전 SID를 HMAC 키로 사용하고 길이 구분된 요청 키·thread를 결합해 같은 새 SID를 재현합니다. 서비스는 SID 원문을 보관하지 않고 이전/후속 해시별 재시도 매핑을 후속 세션 만료까지 유지합니다. 재시도에는 FE가 처음의 threadId와 key를 유지해야 합니다.

## 4. [반영] 통합인증: resume에서 검증

```text
B/E → G/W: 필요한 수만큼 인증 요청 등록(key, 업무 식별·고객 대조값)
G/W → B/E → FE: authReqId, SDK 입력값, expiresAt
FE: 단계별 입력·선택 화면 클릭 시 활동 로그 → SDK 인증 → tranID/txId/수단 수신
FE → G/W: resume body + X-Mfa-Result
G/W: 모든 항목 검증·고객 대조 → 세션 서비스에서 전부 원자 소비
  ├─ 성공: 원본 인증 헤더 제거 → 검증된 authReqId 목록과 원문 body를 B/E로 전달 → SSE
  └─ 인증 실패: 아무것도 소비하지 않고 실패 authReqId와 403, B/E 호출·SSE 시작 안 함
```

### 인증 요청 등록

B/E → G/W `POST /internal/v1/auth/operations`를 유지하고 입력·응답을 변경합니다. 서비스 인증과 유효한 `X-Auth-Context`로 회원·채널·thread를 확인합니다.

| 항목 | 개정 계약 |
|---|---|
| 입력 | `key, operationId, version, interfaceId, actionHash` 및 `ssn, iPin, code, usage, content` 고객 대조값. 필드별 필수 여부는 수단별 합의 |
| 횟수 | 한 업무에도 여러 등록 허용. 서로 다른 인증은 서로 다른 key 사용. 현행 등록 검증은 ssn/iPin 중 하나 이상과 code/usage/content 필수(각 최대2048자) |
| 멱등 | 같은 회원·채널·thread·업무 범위의 같은 key/내용이면 같은 authReqId·SDK 입력값·기한 반환. 내용이 다르면 409 |
| 보관 | authReqId별 신원·업무 결합·고객 대조값·기한·소비 정보. 민감값은 보호해 저장하고 로그·오류·검증 완료 헤더에 노출하지 않음 |
| 응답 | `{authReqId, sdkInput, expiresAt}`. SDK용 암호화 주민번호 포함 여부는 PFC 계약 확인 후 결정. 평문 대조값은 반환하지 않음 |
| 수명 | 현재 등록 시점+10분(설정값). 등록 재시도·클릭으로 인증 요청 기한을 자동 연장하지 않음. 플랫폼 합의 후 조정 |

등록 시 유효한 인증 문맥(기본10분)을 확인하되, 등록 건의 수명은 등록 시점부터 별도 계산합니다. `chat.mfa` 기본값은 등록10분·검증 결과2분·최대8건·헤더4096자·수단 FINANCE_CA/SHINHAN입니다. 클릭은 **로그인 세션만** 연장합니다. 실제 플랫폼 기한·수단은 합의 후 설정을 조정합니다.

### 결과 헤더와 검증

피드백에서 허용한 **padding 없는 base64url JSON 배열 방식으로 구현**했습니다. RFC 목록을 동시에 받지 않으며 FE도 이 형식을 사용해야 합니다.

```text
X-Mfa-Result: base64url(UTF-8 JSON 배열, padding 없음)
디코딩 예: [{"authReqId":"ar_1","tranID":"T001","txId":"X001","method":"FINANCE_CA"},
            {"authReqId":"ar_2","tranID":"T002","method":"SHINHAN"}]
```

| 규칙 | 처리 |
|---|---|
| 형식 | 고정 이름의 헤더 하나. txId가 없는 수단은 생략. 중복 authReqId·중복 헤더·잘못된 구조는 거절. 현재 최대8건·base64url 헤더4096자, 설정으로 조정 |
| 검증 | 유효 회원 세션, 등록 건의 고객·채널·thread·업무 결합·기한·미소비 확인. 수단별 플랫폼에서 결과 진위·등록 요청 및 고객 대조값 일치를 확인 |
| 인증 결과 기한 | 기존 원칙인 최초 검증+2분과 플랫폼 결과 기한 중 짧은 값 유지. 재시도로 연장하지 않음. 등록 대기 수명과 구분. 현행 코드는 전체 성공 후 소비 영수증에 기한을 고정하며, 부분 실패 중 성공 항목의 최초 검증 시각은 저장하지 않음. 부분 실패를 포함한 최초 검증부터 2분을 강제하려면 추가 상태 계약 필요. B/E 적용 시점 기한 확인도 연동 계약에서 확정 |
| 전부 성공 | 검증 후 세션·등록 기한·미소비를 다시 검사하고 **목록 전체를 한 원자 명령으로 소비**. 동시에 들어와도 중복 소비 금지 |
| 일부 실패 | 소비 없음. 본인 요청 중 실패한 authReqId를 반환. 다른 사용자 등록 정보는 노출하지 않음 |
| B/E 전달 | 입력 `X-Mfa-Result`와 외부가 위조한 `X-Verified-Auth-Requests` 제거. G/W가 `X-Verified-Auth-Requests: ar_1, ar_2` 생성 |
| B/E 확인 | 해당 업무에 필요한 authReqId가 전부 포함되는지 검사. 헤더 누락·부분 인증으로 업무 실행 금지. 실제 실행 중복 방지는 B/E 담당 |

**일괄 소비는 세션 서비스의 책임입니다.** PFC의 개별 검증 호출 자체가 토큰을 소비한다면 “한 건 실패 시 아무것도 소비하지 않음”을 G/W만으로 보장할 수 없습니다. 비소비 검증 또는 플랫폼 일괄 처리 가능 여부를 구현 전에 확정합니다.

검증 실패 후에는 등록이 유효·미소비인 경우 같은 authReqId로 다시 인증할 수 있도록 설계합니다. 단, 플랫폼의 실패 후 tx 재사용 가능 여부는 별도입니다. 이미 소비된 결과는 다른 resume에 사용하지 않습니다. 전달 실패 재시도는 **동일 resume 키·본문·인증 목록에만 기존 검증 결과를 재사용**하는 방식으로 구현했습니다. 재전달도 세션·영수증 유효성을 세션 서비스에서 확인합니다. 세션 서비스 보존 기간과 B/E 멱등 처리는 상대 서비스에 적용해야 합니다.

기존 `POST /api/auth/mfa`, G/W → B/E `/internal/v1/auth/mfa-results`, 전달 ACK 기반 조회는 제거했습니다. 기존 `operations/{operationId}` 조회 계약도 제거했으며, 별도 조회가 필요하면 authReqId 기준으로 다시 합의합니다.

## 5. 신원 전달·홈페이지·SSE

| 항목 | 규칙 |
|---|---|
| B/E 신원 헤더 | `X-Authenticated-User-Id`(pid/guest), `X-Authenticated-User-Type`, `X-Authenticated-Channel-Id` |
| 게스트 연결 | `X-Authenticated-Guest-Id`: 현재/이전 guest ID. 처음부터 회원이면 없음. B/E가 thread 소유권 확인 |
| 서비스 인증 | 방향별 Bearer. 사용자 쿠키·인증 원문은 B/E에 전달하지 않음. 외부 신원·검증 완료 헤더는 제거 후 재생성 |
| 인증 문맥 | `X-Auth-Context`: 신원·채널·thread·기한에 결합한 내부 자격증명. SID와 별개, 현행 기본 10분. 회원+threadId 요청에만 발급하며 게스트·목록 조회·클릭은 제외 |
| 홈페이지 연동 | 회원 세션 POST·채팅/도구 응답에 `_I` 3종 자동 발급. 별도 홈페이지 이동 API 없음. 홈페이지가 검증해 자체 세션 생성 |
| `_I` 예외 | GET·게스트·restart·클릭 로그에는 발급하지 않음. SSE 시작 전 발급, 스트림 중 갱신 없음. 발급 실패는 요청 오류이며 이미 처리한 세션은 롤백하지 않음 |
| SSE | 인증·MFA 검증/소비 후 시작. 원문 중계, 자연 세션 만료로 끊지 않음. 응답 대기·데이터 간격 기본 30초로 B/E heartbeat 필요 |

`_I`는 현행 대상 요청마다 발급합니다. 유효기간·일회성·재발급 시 이전 값 유지·동시 요청 규칙은 플랫폼 확인이 필요하며 장시간 무활동 후 홈페이지 로그인은 별도 검증해야 합니다.

## 6. 오류와 구현 상태

공통 오류: `{"error":{"code":403,"status":"PERMISSION_DENIED","message":"","failedAuthReqIds":["ar_1"]}}`. **[반영] failedAuthReqIds는 항목별 인증 실패에만 추가**하며 인증 원문·고객 대조값은 포함하지 않습니다.

| 오류 | 처리 |
|---|---|
| 400 / 형식·크기·thread/key 충돌 | 입력·요청 키 확인. 무조건 재실행하지 않음 |
| 401 / 세션 만료·부재 | 새 인증·새 대화 안내. 늦은 401에서 쿠키 일괄 삭제하지 않음 |
| 403 / MFA 실패·만료·불일치 | SSE 전 거절. 등록 건이 유효하면 해당 인증 재시도, 로그인 세션 유지 |
| 503 / 플랫폼·B/E 통신·세션 서비스 장애 | 성공·소비 완료로 가정하지 않음. 쿠키 삭제·게스트 우회 금지 |

| 구분 | 현재 상태 |
|---|---|
| 구현됨 | 세션 인증·원자 교체 계약, 신원 헤더, `_I` 자동 발급, 공식 MVC SSE 중계, Eureka 세션 클라이언트 |
| **이번 변경 구현됨** | 복수 등록·key 멱등·대조값 보관, resume 헤더 검증·일괄 소비·실패 ID, Gateway 클릭 로그 기록·세션 threadId 저장·조회/activity 제거, 구 MFA API/콜백 제거 |
| **계약 확인 필요** | FE의 /api/chat resume·base64url 형식 적용, 수단별 비소비 검증·실패 재시도, 등록/결과 기한, SDK 암호화 주민번호, 클릭 필드·로그 수집/보관 계약 |
| 외부 연동 미완료 | 실제 PFC·SDK·B/E, 세션 서비스 API·원자성·Redis/Hazelcast 장애 전환. local/mock 테스트는 실제 외부 연동 검증을 의미하지 않음 |

피드백의 3개 변경을 **설계와 코드에 반영했습니다.** 운영 연동에는 세션 서비스의 복수 등록·SDK 입력 고정·resume 영수증·일괄 소비 원자 명령 구현이 필요합니다. local만 검증용 메모리·PFC mock을 제공하며 실제 SDK 암호화는 구현하지 않았습니다. 나머지 세션·대화 소유권·업무 실행 책임 분리는 유지합니다.

요청·응답 필드의 기계 판독 명세는 `docs/openapi/external-api.yaml`과 `docs/openapi/python-internal-api.yaml`을 사용합니다. 정책·책임·외부 연동 계약의 기준은 이 문서입니다.

프론트용 GW 자체 성공 응답은200이며 클릭은 `{"status":"OK"}`를 반환합니다. GW 오류는400 INVALID_ARGUMENT(형식·크기·thread/key 충돌),401 UNAUTHENTICATED,403 PERMISSION_DENIED(권한·추가 인증 실패),503 UNAVAILABLE(의존 서비스 장애)로 묶습니다. 경로/메서드 오류404/405와 예상하지 못한 서버 오류500은 유지합니다. failedAuthReqIds는 필요한 경우 유지합니다. B/E가 반환한 HTTP 상태·본문은 변환하지 않으며 SSE 시작 후에는 HTTP 코드를 변경하지 않습니다. 내부 세션 서비스 및 B/E용 인증 등록 API의 세부 오류 계약은 유지합니다.

### 자동완성 (이전 챗봇 계약 반영, 2026-10-21)

| 항목 | 계약 |
|---|---|
| FE 호출 | POST `/api/chat/auto`, JSON `{queryText, size?, channel?}` |
| 검증·기본값 | queryText 필수·공백 불가, size 기본10/범위1~1000, channel 기본 `sol-chat` |
| 내부 호출 | Eureka `search-autocomplete-service` → POST `/api/autocomplete/chat` |
| 접근 | 유효 게스트·회원 세션 필요. 같은 Origin. 세션 연장·Idempotency-Key·PFC 인증 없음 |
| 성공 | 200 `{code:"SUCCESS", message:"성공", data:{totalCount, chatResponse:[...]}}` |
| 항목 필드 | highlight, score, auto_result, weight, boost, nori_result, ngram_result 유지 |
| 오류 | 입력400, 세션401, 검색 서비스 통신·잘못된 응답503 |
| 로컬 검증 | `AUTOCOMPLETE_BASE_URL`의 mock 서버를 SimpleDiscoveryClient로 조회, 기본 localhost:8001 |

빈 결과는 totalCount=0/chatResponse=[]이다. FE는 빈 입력·포커스 해제에서 호출을 생략하고 디바운스한다. highlight에 HTML이 포함될 수 있으므로 FE에서 허용 태그만 렌더링해야 한다. 검색용 channel은 기존 검색 인덱스 선택값이며 세션 인증 채널을 변경하지 않는다.
`X-Verified-Login`은 사용하지 않으며 외부 입력을 제거한다. 회원 여부는 GW가 설정한 X-Authenticated-User-Type/Id와 이전 게스트 식별자로 전달한다.

### PFC 이전 프로젝트 분석 (미연결 항목, 2026-10-21)

| 흐름 | 확인된 기존 규격 |
|---|---|
| MCI 전송 | 사내 Network.mciapi().sync, URI `/pfc/platform-receiver/v1/genesis-receive` |
| 서명키 조회 | CBA00002: `{SYS_BNE_CCD:"CBA"}` → SGN_KEY |
| 요청 검증값 | SCCrypto MAC_SHA_256, UTF-8 `(PlatformVerify_O + "CBA" + salt)`, HEX 서명값 |
| 고객 인증 | CBA00001: SYS_CD=PlatformSystemCode_O, CTS_KEY=PlatformAuthentication_O, VRF_VL=PlatformVerify_O, CHAB_VRF_VL=계산된 서명 → CLNN(PID) |
| 홈페이지 쿠키 | KMS 암호화 `(PID + yyyyMMddHHmm)`와 별도 MAC. 기존 시간 자르기 substring(0,11)·HEX 형식·타임존·키 설정은 플랫폼 확인 필요 |
| 추가 인증 | CBA00003은 stateId/verifyCode/ticketId 목록 입력. 현재 authReqId/tranID/txId와의 매핑·비소비 검증 여부가 확인되지 않음 |

현재 비local PFC는 계속503으로 차단하며 local만 mock을 사용한다. 실제 연결 완료를 의미하지 않는다. 사내 `ent.genesisframework:network-starter`, `crypto-starter`, 적용 버전/BOM·SDK·KMS 설정이 필요하다. 기존 템플릿은 responseCode를 읽고도 성공 여부를 판정하지 않으므로 정상/인증실패/시스템오류 코드를 확인해야 한다. 기존 코드의 키·MAC 디버그 출력과 예외 시 게스트 전환은 가져오지 않는다. 회원 PID는 승인된 클릭 AUDIT 필드에만 기록한다. 키/secret은 문서나 채팅에 전달하지 않고 운영 비밀 설정으로 주입해야 한다.

## Staging 채널 접근 제한 (2026-10-21)

단일 G/W에서 **검증된 세션의 channelId**로 Agent B/E 목적지를 선택합니다. staging 채널·주소는 서버 설정이며 production은 기존 정책을 유지합니다.

| 항목 | 규칙 |
|---|---|
| staging 입장 | PFC로 확인된 PID가 허용 목록에 있는 회원만 허용. 게스트·미허용 고객 403 |
| 허용 목록 | Document DB의 channelId + customerId + enabled. MongoDB 호환 어댑터 구현, 실제 제품·접속 정보 미확정 |
| 캐시 | GW 메모리, 저장 시점부터 1분, 최대 10,000건. 허용/거절 결과만 캐싱 |
| 검사 | 세션 생성·재사용·재시작 및 보호된 요청마다 확인. 캐시 만료 시 DB 재조회 |
| 장애 | 유효 캐시는 만료까지 사용. 캐시 부재·만료 시 DB 오류는 503이며 허용으로 대체하지 않음 |
| 목적지 | staging 채널은 설정된 staging B/E, 나머지는 기존 production B/E. 요청 채널이 세션과 다르면 거절 |
| local | DB 없이 테스트 고객 목록 사용. 운영 DB와 실제 B/E 주소는 별도 주입 |

목록 삭제는 최대 캐시 TTL 후 새 요청부터 반영되며 이미 시작한 SSE는 끝까지 전달합니다. 관리자가 DB 원본과 channelId/customerId 고유 인덱스를 준비합니다.

## 외부 세션 서비스 연동 계약

GW가 호출하는 제안 계약이며 실제 서비스 연동 완료를 의미하지 않습니다. Eureka `agent-session-service`, Bearer `SESSION_SERVICE_CREDENTIAL`, 기본 경로 `/internal/v1/authority/`입니다. `ready`만 GET(204), 나머지는 POST JSON(성공 200)입니다. 응답은 envelope 없는 객체이며 nullable 필드도 포함합니다. 시각/TTL은 epoch milliseconds/milliseconds, 최종 만료 판정은 서비스 신뢰 시각 기준입니다. 자동 retry·redirect·운영 메모리 fallback은 없습니다.

| 명령 | 입력 | 성공 |
|---|---|---|
| ready | 없음 | 204 |
| find-session | hash | Session / 404 SESSION_NOT_FOUND |
| create-session | session, creationKey(nullable) | Session |
| touch-session | hash, now, ttl, activityKey | Session |
| replace-guest | hash, key, replacement, now | Session |
| restart-session | hash, key, threadId, replacementHash, now, ttl | Session |
| save-grant / find-grant | grant / hash, now | ExecutionGrant |
| register-auth-request | request, now | AuthRequest |
| find-auth-request | id | AuthRequest / 403 MFA_REQUIRED |
| save-sdk-input | id, sdkInput, now | AuthRequest |
| find-resume | sessionHash, key | ResumeReceipt / 404 RESUME_NOT_FOUND |
| consume-auth-requests | sessionHash, key, fingerprint, threadId, proofs, now | ResumeReceipt |


객체 필드의 기준은 `Session.java`, `AuthRequest.java`, `AuthorityStore.java`입니다. comparison의 주민번호 등 민감값은 보호 저장하며 요청 본문을 로그로 남기지 않습니다. resume 영수증은 금융 업무 결과가 아닙니다.

| 명령 | 보장 |
|---|---|
| create/touch | 생성 키·활동 키 중복 처리와 저장/연장을 함께 수행. 만료 부활·동일 활동 재연장 금지 |
| replace-guest | 유효 ACTIVE 게스트·동일 채널/threadId·기존 guestSubject 연결 확인 후 폐기와 회원 저장을 함께 처리. 동시 교체 하나만 성공 |
| register-auth-request | 고객·채널·thread·업무·key 범위에 최초 등록 고정. 같은 입력은 같은 id·기한, 내용 변경은409. 같은 업무의 다른 key는 별도 등록 허용 |
| save-sdk-input | 최초 SDK 입력 고정·만료 검사. PFC.start도 authReqId 기준 멱등이어야 함 |
| consume-auth-requests | 유효 회원 세션 재검사 → 목록 전체의 고객·채널·thread·등록 기한·미소비·proof 재사용 확인 → 전부 소비와 영수증 저장을 단일 원자 명령으로 처리 |
| 동일 resume 재시도 | 같은 세션·key·fingerprint에만 기존 영수증 반환. 세션과 영수증 만료 재검사, 기한 연장 없음. 다른 key로 소비 결과 재사용 금지 |

소비 실패 시 어떤 항목도 변경하지 않는다. 만료·proof 충돌은403, 이미 다른 resume에서 소비되었으면409. 최초 영수증 expiresAt은 모든 등록·Proof 기한 중 최솟값이다. 검증 기한 상한(기본2분)은 서비스도 정책으로 검증해야 한다. 로그아웃/만료를 이유로 사용 이력을 먼저 지워 재사용을 허용하면 안 된다. 등록·proof·영수증의 보존 기간 및 개인정보 정리 정책은 서비스 운영 계약에서 확정한다.

Redis/Hazelcast 장애 전환·다중 인스턴스 중에도 같은 원자성·최신 상태를 보장해야 한다. 확정 불가 시503을 반환한다. Gateway의 local synchronized 테스트가 실제 분산 원자성을 검증하지는 않는다.

### restart-session

기존 세션이 ACTIVE·미만료인지 확인하고 새 thread가 다른지 검사한다. 회원은 subject/userType/channel을 승계, 게스트는 새 subject를 만든다. guestSubject는 null, 새 idHash는 replacementHash, 새 기한은 now+ttl이다. 기존 폐기·신규 저장·(이전 해시,key)/(후속 해시,key)의 thread/후속 해시 매핑을 하나의 원자 작업으로 처리한다.

재시도 매핑이 있으면 thread를 비교(다르면409)하고 현재 후속 세션의 유효성을 확인해 반환한다. 이때 전달된 새 replacementHash는 사용하지 않고 최초 결과를 유지한다. 서로 다른 key의 동시 교체는 하나만 성공해야 한다. 현재 thread와 같은 새 요청409, 최초 부재·만료·폐기401, 후속 만료·폐기 후 재시도401. 매핑은 후속 세션 만료까지 유지하며 TTL을 연장하지 않는다. SID 원문은 저장하지 않는다. GW가 HMAC-SHA256으로 같은 SID를 재현한다. 이 원자 API가 실제 세션 서비스에 구현되어야 운영 restart가 동작한다.

### 내부 오류 계약

`{"error":{"code":403,"status":"MFA_INVALID","message":"","failedAuthReqIds":["ar_1"]}}`.
HTTP 상태와 error.code는 같아야 합니다. 401 UNAUTHENTICATED, 403 PERMISSION_DENIED/MFA_REQUIRED/MFA_INVALID/MFA_EXPIRED, 409 CONFLICT/OPERATION_CHANGED를 해석합니다. find-session/find-resume의 명시된 404만 부재이며 일반 404·통신·파싱 오류는 503입니다. failedAuthReqIds는 소비 요청에 제출된 목록만 반환하며 다른 고객·미등록 정보를 노출하지 않습니다.

### 운영 설정 보충

- staging: `chat.staging.channels`에 채널별 B/E URL, `chat.staging.mongo-uri`와 `database`로 DB 연결. `channel_customer_access` 컬렉션에 channelId/customerId/enabled 저장, channelId+customerId 고유 인덱스 필요. GW는 조회만 하며 목록 관리 API는 없습니다.
- 클릭: 수집기용 최상위 `@timestamp/level/thread_name/logger_name/message` 구조. `buttonInfo`는 선택 객체, 알려진 5개 필드는 문자열/null이며 미정의 필드는 기록하지 않습니다. `sessionId=threadId`는 과거 인증 세션키와 의미가 다르므로 수집 분석 계약을 확인합니다. 자동 버튼 개인정보 마스킹은 보장하지 않습니다.
- 로그 큐: 8192건, INFO 자동 폐기 없이 포화 시 대기. 200은 큐 등록/로그 호출 성공이며 디스크 영구 저장·수집 완료를 보장하지 않습니다.
