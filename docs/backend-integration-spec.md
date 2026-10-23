# Agent B/E 연동 명세

2026-10-23

| 구분 | G/W 및 B/E 엔드포인트 | 용도 |
|---|---|---|
| A. 대화 | `POST /api/chat` | 대화 한 턴 실행·interrupt 재개 |
| B. 목록 | `GET /api/chat/threads` | 사용자 대화 목록 |
| C. 이력 | `GET /api/chat/threads/{tid}/messages` | 메시지 이력 |
| D. 위자드 | `POST /api/chat/threads/{tid}/tools/{tool-name}/{tool-route}` | 화면 데이터 조회 |

이 문서는 G/W→B/E 인터페이스 규격임. [세션 규격](agent-api-gw-session-summary.md) · [통합인증 규격](unified-auth-spec.md).

## 공통

FE→G/W의 모든 세션 요청은 query `channelId`로 채널을 선택함. G/W는 채널별 세션을 검증하고 B/E에는 `X-Authenticated-Channel-Id`로 전달함. 세션 선택용 channelId query는 upstream에서 제외함. 로그인 세션은 start(`POST /api/auth/session`)로 준비함. FE는 MEMBER이면 목록 → 선택한 대화의 메시지 이력 순으로 조회하고 GUEST이면 새 대화를 시작함.

### 헤더

| 헤더 | 형식 | 필수 | 값 |
|---|---|---|---|
| X-Authenticated-User-Id | string | Y | G/W가 확인한 고객 ID. G 시작13자리 게스트 / P 시작10자리 회원 |
| X-Authenticated-Channel-Id | uuid | Y | 세션의 채널 ID |
| X-Verified-Request-Id | string | 인증 성공 시 | PFC 검증에 성공한 요청의 interruptId |

사용자 정의 헤더는 위 세 개만 사용함. Content-Type 등 표준 HTTP 헤더는 전송 형식에 맞게 설정함. 신원·검증 헤더는 G/W가 직접 생성하며 FE가 보낸 값으로 인증하지 않음. B/E는 G/W를 통한 요청을 처리하며 업무 요청의 중복 처리를 담당함.

### G/W→FE 응답 헤더

G/W는 유효 세션을 확인한 응답에 `X-Chat-User-Type`, `X-Chat-Auth-Expires-At`, `X-Server-Time`을 설정함. JSON·SSE·B/E 오류 응답에 적용하며 B/E에서 생성할 필요는 없음. 시각은 UTC epoch milliseconds임. GET은 세션을 연장하지 않으며 SSE 헤더는 스트림 시작 시 한 번 전달함. 상세 조건은 [공통 응답 헤더](agent-api-gw-session-summary.md#공통-응답-헤더)를 따름.

### 오류 응답

```json
{"error":{"code":403,"status":"THREAD_EXPIRED","message":""}}
```

error.code는 HTTP 상태, status는 FE 분기값, message는 항상 빈 문자열임. 각 API에는 공통 오류와 해당 API 오류가 적용됨. G/W는 B/E 상태·본문을 그대로 중계함.

| 상태코드 | status | 조건 |
|---|---|---|
| 404 | NOT_FOUND | 경로 없음 |
| 405 | METHOD_NOT_ALLOWED | 메서드 불일치 |
| 422 | VALIDATION_ERROR | 헤더·쿼리·경로·본문 형식 오류 |
| 500 | INTERNAL_ERROR | 그 밖의 오류 |

세션 인증 실패는 G/W가 B/E 호출 전에 401로 거절함.

## A. 대화 — POST /api/chat

### 요청

헤더는 공통 규격을 따름. 본문은 ag-ui RunAgentInput이며 최대 1MiB임.

| 본문 | 형식 | 필수 | 설명 |
|---|---|---|---|
| threadId | string | Y | FE가 선택하거나 생성한 대화 ID |
| runId | string | Y | 정수 문자열. B/E가 user 메시지 수로 덮어씀 |
| messages | array | Y | 전체 이력. B/E는 마지막 한 건 사용. 모든 메시지 id 필수 |
| tools | array | Y | 기본 [] |
| context | array | Y | 기본 [] |
| forwardedProps | any | Y | 기본 null |
| state | any | N | B/E가 정의하는 초기 입력 |
| parentRunId | string | N | B/E 미사용 |
| resume | array | N | interruptId·status 필수, payload·metadata 선택 |

```json
{
  "threadId":"thread-001",
  "runId":"1",
  "messages":[{"id":"user-001","role":"user","content":"카드 분실신고 해줘"}],
  "tools":[],
  "context":[],
  "forwardedProps":null
}
```

user 메시지 ID는 B/E가 수신 시각 14자리로 덮어씀. threadId는 대화 요청 값이며 로그인 세션에는 저장하지 않음. G/W는 본문을 중계하며 B/E의 RUN_STARTED.runId가 턴 순번의 정본임.

### 응답

200, Content-Type: text/event-stream. `data: <JSON>`과 빈 줄로 전송하며 FE는 JSON의 type으로 분기함.

```text
data: {"type":"RUN_STARTED","threadId":"thread-001","runId":"1"}

data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m-1","delta":"카드를 선택해 주세요."}

```

위는 이벤트 일부의 형식 예시임.

| 이벤트 | 처리 |
|---|---|
| RUN_STARTED | 턴 시작·runId 확인 |
| TEXT_MESSAGE_START/CONTENT/END | 텍스트 표시 |
| TOOL_CALL_START/ARGS/END/RESULT | 도구 표시·결과 처리. 도구 실패도 RESULT로 올 수 있음 |
| MESSAGES_SNAPSHOT | 전체 이력 교체. 한 턴에 1회 이상, 마지막 snapshot은 이력 GET과 동일 |
| RUN_FINISHED | 턴 종료. outcome.interrupts가 있으면 사용자 입력 대기 |
| RUN_ERROR | 스트림 시작 후 업무 오류. HTTP 상태는 이미 200 |

G/W는 SSE를 원문 중계함. B/E 읽기 간격은 기본 30초이며 EOF에서 종료함. SSE 수신은 로그인 세션을 연장하지 않음.

### interrupt 재개

B/E의 `outcome.interrupts[].id`를 다음 요청의 `resume[].interruptId`로 보냄.

```json
[{"interruptId":"int-3","status":"resolved","payload":{}}]
```

status는 resolved 또는 cancelled. payload는 해당 interrupt의 responseSchema를 따름. 위 배열은 RunAgentInput의 resume 값임.

통합인증 결과는 G/W가 검증함. B/E는 인증이 필요한 resolved interrupt에 X-Verified-Request-Id가 일치할 때만 실행하고 업무 상태로 중복 실행을 막음.

### 오류

| 상태코드 | status | 조건 |
|---|---|---|
| 400 | INTERRUPT_NOT_FOUND | 재개할 대기 interrupt 없음 |
| 403 | THREAD_EXPIRED | 마지막 대화 시도부터 5분 경과 |
| 403 | THREAD_FORBIDDEN | 대화 소유자 불일치 |

기록 없는 thread는 신규 대화임. 게스트→회원 승격의 소유권 확인은 B/E가 담당함.

## B. 목록 — GET /api/chat/threads

본문·쿼리 없음. 회원 전용.

### 응답

200. last_active_at 내림차순 배열.

```json
[{"thread_id":"thread-001","title":"카드 분실신고","last_active_at":"20261022143012"}]
```

게스트 요청은 403 GUEST_FORBIDDEN.

## C. 이력 — GET /api/chat/threads/{tid}/messages

### 응답

200. 도구 호출·결과·취소 상태를 포함한 메시지 이력.

```json
{"messages":[{"id":"20261022143012","role":"user","content":"카드 분실신고 해줘"}]}
```

B/E에서 만료된 대화도 조회 가능하며 G/W 세션은 유효해야 함.

| 상태코드 | status | 조건 |
|---|---|---|
| 403 | THREAD_FORBIDDEN | 소유자 불일치 |
| 404 | THREAD_NOT_FOUND | 대화 없음 |

## D. 위자드 — POST /api/chat/threads/{tid}/tools/{tool-name}/{tool-route}

### 요청·응답

도구명·조회명·본문·응답은 B/E의 라우트별 스키마를 따름. 인자가 없으면 `{}`. thread는 FE가 선택한 대화이며 B/E가 사용자·채널의 소유권과 대화 상태를 확인함.

```json
{"query":"래미안"}
```

위는 검색 인자 예시임. 성공 200.

### 오류

| 상태코드 | status | 조건 |
|---|---|---|
| 404 | TOOL_NOT_FOUND | 도구·조회 경로 없음 |
| 403 | THREAD_FORBIDDEN | 소유자 불일치 |
| 404 | THREAD_NOT_FOUND | 대화 없음 |
| 403 | THREAD_EXPIRED | 마지막 대화 시도부터 5분 경과 |

경로를 먼저 판정함. G/W 세션 만료와 B/E 대화 만료는 각각 적용됨.
