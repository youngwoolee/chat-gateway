# Chatbot Gateway

Java 21 / Spring Boot / MVC·Tomcat 기반 인증·HTTP/SSE 중계 서비스입니다.

**설계·동작·외부 연동 계약은 [agent-api-gw-session-summary.md](docs/agent-api-gw-session-summary.md) 한 곳에서 관리합니다.**

## 실행 및 검증

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew check bootJar
```

local은 메모리 세션·PFC mock·테스트용 staging 허용 목록을 사용합니다. 실제 PFC·세션 서비스·Document DB 연동은 별도 확인이 필요합니다. HTTP 호출 예제는 `http/`에서 실행하며 baseUrl을 실행 포트에 맞춥니다.

## API 문서

local 실행 후 `http://localhost:8080/docs/index.html`에서 Swagger와 API PDF를 확인합니다.

- `docs/openapi/*.yaml`: Swagger와 API PDF의 요청·응답 명세 원본
- `output/pdf/gateway-api-guide.pdf`: UI에서 제공하는 공유용 파일
- `docs/tools/build-api-pdf.py`: API PDF 생성 도구 (Python reportlab, pyyaml 필요)

```sh
python docs/tools/build-api-pdf.py --font /path/to/korean.ttf
./gradlew bootJar
```

전달용 [인수인계 PDF](output/handover/chatbot-gateway-handover.pdf)와 [수정 가능한 원본](output/handover/chatbot-gateway-handover.md)은 별도로 보관합니다. 중복 설명서·과거 설계·리뷰 기록은 관리하지 않습니다.
