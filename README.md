# Chatbot Gateway

Java 21 / Spring Boot / MVC·Tomcat 기반 인증·HTTP/SSE 중계 서비스입니다.
`ChatController → ChatService → AgentBackendClient`로 호출하며 SSE는 `SseRelayService`의 `SseEmitter`로 전달합니다. Spring Cloud Gateway 중계는 사용하지 않습니다.

**설계·동작·외부 연동 계약은 [agent-api-gw-session-summary.md](docs/agent-api-gw-session-summary.md) 한 곳에서 관리합니다.**

## 실행 및 검증

```sh
./gradlew bootRunOffline
./gradlew check bootJar
```

offline은 외부망 전용 메모리 세션·PFC mock·테스트용 staging 허용 목록을 사용합니다. 실제 PFC·세션 서비스·Document DB 연동은 별도 확인이 필요합니다. HTTP 호출 예제는 `src/offline/http/`에서 실행하며 baseUrl을 실행 포트에 맞춥니다.

## API 문서

offline 또는 내부망 local 실행 후 `http://localhost:8080/docs/index.html`에서 Swagger와 API PDF를 확인합니다.

- `docs/openapi/*.yaml`: Swagger와 API PDF의 요청·응답 명세 원본
- `output/pdf/gateway-api-guide.pdf`: UI에서 제공하는 공유용 파일
- `docs/backend-integration-spec.md` / `output/pdf/backend-integration-spec.pdf`: B/E 양방향 연동·신원 헤더·SSE·추가 인증 계약
- `docs/tools/build-api-pdf.py`: API PDF 생성 도구 (Python reportlab, pyyaml 필요)

```sh
python docs/tools/build-api-pdf.py --font /path/to/korean.ttf
./gradlew bootJar
```

전달용 [인수인계 PDF](output/handover/chatbot-gateway-handover.pdf)와 [수정 가능한 원본](output/handover/chatbot-gateway-handover.md)은 별도로 보관합니다. 중복 설명서·과거 설계·리뷰 기록은 관리하지 않습니다.

## 실행 환경 분리

| 환경 | 실행 | 연동 |
|---|---|---|
| 외부망 offline | `./gradlew bootRunOffline` | 메모리 세션·PFC mock·설정 고객 목록 |
| 내부망 local | `./gradlew bootRun --args='--spring.profiles.active=local'` | 내부 개발 서비스 |
| 내부망 dev/prod | 기본 bootJar에 해당 프로필 지정 | 배포 환경의 실제 서비스 |

local/dev/prod는 EUREKA_URL·BACKEND_BASE_URL·서비스 토큰·채널·Document DB 설정을 외부에서 주입합니다. 실제 PFC 구현은 별도 필요하며 미구현이면 503입니다. 환경별 주소를 코드에 가정하지 않습니다. offline과 local/dev/prod의 동시 활성화는 금지합니다.

`src/offline/java`, `resources`, `test`, `http`는 외부망 전용입니다. 기본 JAR에 포함하지 않으며 `check`는 일반 `test`와 `offlineTest`를 모두 실행합니다. 더 이상 필요 없으면 `src/offline`을 삭제하고 build.gradle.kts의 외부망 전용 블록을 제거합니다. 디렉터리가 없으면 블록은 자동으로 건너뛰므로 공통 코드와 일반 테스트는 그대로 빌드됩니다. 개인 HTTP Client 설정은 자동 삭제하지 않습니다.
