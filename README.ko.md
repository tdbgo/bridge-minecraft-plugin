# Bridge by PLAYCITY BLOCK

Bridge는 신뢰할 수 있는 웹 백엔드와 다른 플러그인이 RCON이나 임의의 원격 콘솔 없이도 작고 명확한 타입의 서버 작업을 호출하고 범위가 지정된 이벤트를 수신할 수 있게 하는 Paper 플러그인입니다.

*[English README](README.md)*

호출자마다 별도의 인증 정보, 작업·이벤트 허용 목록, 요청 빈도 제한, 동시 실행 제한을 부여합니다. 중앙 발급자를 운영하는 환경에서는 수명이 짧은 서명된 JWT 액세스 토큰을 추가로 검증할 수 있습니다. 다른 Paper 플러그인은 별도로 배포되는 `bridge-api` 모듈을 통해 자체 작업과 이벤트를 등록합니다.

Bridge는 공개 인터넷 API도, 브라우저 인증 시스템도, 범용 명령 실행기도 아닙니다. 제품 수준의 권한 검사는 호출하는 백엔드에 남아 있어야 합니다.

> 이 문서는 한국어 요약 안내서입니다. `docs/` 아래의 상세 문서는 모두 영어로 작성되어 있습니다.

## Bridge를 쓰는 이유

- **콘솔 접근이 아닌 작업 단위 호출.** 호출자는 등록된 작업 이름과 구조화된 payload만 사용할 수 있으며, 임의 명령을 실행하는 작업은 존재하지 않습니다.
- **하나의 정책 경계.** 제공자 플러그인은 타입이 정해진 작업과 이벤트를 추가하고, 인증·호출자 범위·제한·멱등성·오류 처리는 Bridge가 담당합니다.
- **최소 권한.** 한 백엔드에는 읽기 전용 플레이어 상태만, 다른 백엔드에는 특정 변경 작업만 부여할 수 있습니다. JWT 범위는 서버의 로컬 허용 목록과 교집합으로 처리되며 결코 이를 넓힐 수 없습니다.
- **재시도 안전성.** 호출자별로 분리된 요청 ID가 동시 재시도를 하나로 합치고, 내용이 다른 재사용은 거부합니다.
- **유연한 네트워크 구성.** 리버스 프록시나 VPN 뒤에서 loopback HTTP·WebSocket을 사용하거나, 인바운드 연결을 받을 수 없는 호스트에서는 아웃바운드 relay를 사용합니다.
- **관측 가능성.** 선택적인 상태·Prometheus endpoint는 집계 값만 노출하며, 모든 작업 클라이언트와 분리된 인증 정보를 사용합니다.
- **안전한 기본값.** 새로 설치하면 loopback HTTP만 바인딩하고 WebSocket, JWT, relay, metrics, 변경 작업은 모두 비활성 상태입니다.
- **백엔드·서버 중립성.** 인증된 JSON을 HTTP로 보낼 수 있는 언어면 무엇이든 사용할 수 있고, 기본 설정에는 필수 외부 서비스가 없습니다.

여러 서비스와 서버 플러그인이 하나의 RCON 인증 정보를 공유하지 않고 협력해야 할 때 이러한 특성이 가장 유용합니다.

## 요구 사항

- Paper 26.2
- Java 25
- 백엔드가 마인크래프트 호스트 밖에 있을 경우 리버스 프록시 또는 사설 네트워크

Folia는 지원하지 않으며, Bridge는 의도적으로 `folia-supported: true`를 선언하지 않습니다. Spigot, Bukkit 전용 서버, Purpur 계열도 호환을 주장하지 않습니다. 자세한 내용은 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)에 있습니다.

처음 설치할 때는 [최소 구성 quick start](docs/QUICKSTART.md)를 따르십시오. WebSocket, JWT, relay를 도입하기 전에 loopback에서 읽기 전용 플레이어 상태 작업만 설정합니다.

## 기본 제공 기능

| 작업 | 위험도 | 활성화 조건 |
| --- | --- | --- |
| `minecraft.player_status` | `READ_ONLY` | 클라이언트 허용 목록만 필요 |
| `minecraft.teleport_player` | `MUTATING` | `actions.teleportPlayer.enabled`, 기본값 `false` |
| `minecraft.notice_sync` | `MUTATING` | `actions.notice.syncEnabled`, 기본값 `false`, 선택 플러그인 Notice 필요 |
| `minecraft.notice_send` | `MUTATING` | `actions.notice.sendEnabled`, 기본값 `false`, 선택 플러그인 Notice 필요 |

| 이벤트 | 데이터 |
| --- | --- |
| `minecraft.player_joined` | 플레이어 UUID만 |
| `minecraft.player_left` | 플레이어 UUID만 |

서버 전체에서 작업이나 이벤트를 켜는 것만으로는 충분하지 않습니다. 각 클라이언트의 `allowedActions` 또는 `allowedEvents`에도 반드시 명시해야 합니다.

## 빌드

```powershell
.\gradlew.bat clean build --console=plain
```

산출물:

- 플러그인: `build/libs/Bridge-<version>.jar`
- 개발자 API: `bridge-api/build/libs/bridge-api-<version>.jar`, Javadoc·소스 JAR 포함

개발용 기본 버전은 `0.5.0-SNAPSHOT`입니다. 소스를 수정하지 않고 릴리스 후보를 빌드할 수 있습니다.

```powershell
.\gradlew.bat releaseBundle writeReleaseChecksums -PbridgeVersion=0.5.0-rc.2 --console=plain
```

체크섬이 기록된 산출물은 `build/release` 아래에 준비됩니다. 더 엄격한 `assembleRelease` 작업은 snapshot 버전, 날짜가 없는 변경 기록 항목, 정리되지 않은 라이선스 메타데이터를 거부합니다.

제공자 플러그인을 개발하는 동안에는 `publishToMavenLocal`을 사용하십시오.

```powershell
.\gradlew.bat :bridge-api:publishToMavenLocal --console=plain
```

## 설치와 설정

1. 플러그인 JAR을 Paper 서버의 `plugins` 디렉터리에 넣습니다.
2. 서버를 한 번 시작해 `plugins/Bridge/config.yml`을 생성합니다.
3. 백엔드마다 임의의 토큰 파일을 하나씩 만듭니다. 각 토큰은 최소 32 UTF-8 바이트여야 하며 서버 계정만 읽을 수 있어야 합니다.
4. 범위가 지정된 클라이언트를 추가하고 노출할 작업만 활성화합니다.
5. 일반적인 서버 재시작으로 변경 사항을 적용합니다. 플러그인 hot-reload 도구는 사용하지 마십시오.

```yaml
http:
  enabled: true
  host: "127.0.0.1"
  port: 25585
  allowNonLoopback: false
  token: "CHANGE_ME" # 과거 호환용 단일 클라이언트 인증 정보. clients 목록 사용을 권장합니다.
  clients:
    - id: "example-web"
      tokenFile: "secrets/example-web.token"
      allowedActions:
        - "minecraft.player_status"
        - "minecraft.teleport_player"
      allowedEvents:
        - "minecraft.player_joined"
        - "minecraft.player_left"
      requestsPerMinute: 120
      maxConcurrent: 4
      maxEventConnections: 2

actions:
  teleportPlayer:
    enabled: true
    allowedWorlds: ["world"]
```

결과는 콘솔에서 `bridge status`, 게임 안에서 `/bridge status`로 확인할 수 있습니다.

가능한 한 Bridge를 loopback에 두고, 원격 백엔드와의 사이에 HTTPS 리버스 프록시, VPN 또는 인증된 서비스 메시를 두십시오. loopback이 아닌 주소에 직접 바인딩하려면 `http.allowNonLoopback: true` 안전장치를 명시적으로 켜야 하며, 이 설정 자체가 TLS를 제공하지는 않습니다.

아웃바운드 전용 설치에서는 `relay.allowedActions`와 `relay.allowedEvents`를 각각 부여합니다. relay 이벤트는 메모리 한도 안에서 관리되며 수신 확인 전까지 최소 한 번 이상 전달됩니다. 수신 측은 agent, Bridge 인스턴스, sequence 조합으로 중복을 제거하고, Bridge가 overflow gap을 보고하면 상태를 다시 구성해야 합니다.

## 명령어와 권한

| 명령어 | 용도 | 권한 |
| --- | --- | --- |
| `/bridge help` | 사용법 표시 | `bridge.use` |
| `/bridge status` | HTTP 리스너, WebSocket, relay, 인증 설정, 등록된 작업 상태 확인 | `bridge.use` |
| `/bridge reload` | 설정을 다시 읽고 Bridge가 소유한 전송 계층을 재시작 | `bridge.admin` |

두 권한의 기본값은 모두 `op`이며, `bridge.admin`은 `bridge.use`를 포함합니다. 서버 콘솔에서는 `/` 없이 같은 하위 명령을 사용합니다.

## 설정·저장소·네트워크

Bridge는 설정을 `plugins/Bridge/config.yml`에 보관합니다. 그 밖에는 운영자가 선택한 클라이언트·metrics 토큰 파일, 선택적인 로컬 JWKS 파일, relay를 사용할 때의 `relay.identityFile`(기본값 `secrets/relay-identity.json`)과 그 옆의 재실행 방지 기록 `relay-executions.json`을 사용합니다. 상대 경로는 `plugins/Bridge`를 기준으로 해석합니다.

- 데이터베이스가 필요하지 않습니다.
- 클라이언트 모드가 필요하지 않습니다. Bridge는 서버 전용입니다.
- 실행 중에 의존성을 내려받지 않습니다. 필요한 구성 요소는 모두 플러그인 JAR 안에 있습니다.
- PLAYCITY를 비롯한 어떤 외부 서비스 endpoint도 필요하지 않습니다. 기본 설정에는 외부 서비스가 명시되어 있지 않습니다.
- 아웃바운드 연결은 명시적으로 설정한 경우에만 발생합니다. 키 조회를 위한 원격 `jwt.jwksUrl`, 또는 relay를 사용할 때의 `relay.baseUrl`이며 둘 다 기본값은 비어 있습니다.

## HTTP 사용법

한 클라이언트가 호출할 수 있는 작업 조회:

```http
GET /v1/actions
Authorization: Bearer <client-token>
```

작업 실행:

```http
POST /v1/actions
Content-Type: application/json
Authorization: Bearer <client-token>

{
  "requestId": "example.status.20260814.001",
  "action": "minecraft.player_status",
  "targetPlayer": {
    "uuid": "00000000-0000-0000-0000-000000000000"
  }
}
```

요청 ID는 완전히 동일한 작업과 payload를 재시도할 때만 재사용하십시오. 타임아웃이 발생했다고 해서 작업이 취소되었다는 뜻은 아닙니다.

전체 통신 규약은 [docs/HTTP-API.md](docs/HTTP-API.md)와 [docs/openapi.yaml](docs/openapi.yaml)에 있습니다.

## 다른 플러그인에서 작업 추가하기

```kotlin
dependencies {
    compileOnly("com.playcity:bridge-api:<version>")
}
```

`depend: [Bridge]` 또는 이에 준하는 Paper 의존성을 선언한 뒤 `onEnable`에서 등록합니다.

```java
BridgeApi bridge = getServer().getServicesManager().load(BridgeApi.class);
BridgeRegistration registration = bridge.registerAction(
    this,
    new BridgeActionDescriptor(
        "example:greet",
        "1",
        "Returns a greeting without changing server state.",
        BridgeActionRisk.READ_ONLY
    ),
    (request, context) -> CompletableFuture.completedFuture(
        BridgeActionResult.success(Map.of("message", "Hello"))
    )
);
```

소유한 플러그인이 비활성화되면 Bridge가 해당 작업을 자동으로 해제합니다. payload와 결과는 변경 불가능한 JSON 호환 Java Map·List를 사용하며, 공개 API는 Bridge의 JSON 구현을 노출하지 않습니다.

제공자는 같은 방식으로 `BridgeEventDescriptor`를 등록하고 반환된 `BridgeEventPublisher`를 보관할 수 있습니다. 발행은 전송 계층과 무관하며, WebSocket 인증·재전송·연결 수 제한은 Bridge가 담당합니다.

이름 규칙, 스레드 규칙, 수명 주기, 실패 코드 규칙은 [docs/DEVELOPER-API.md](docs/DEVELOPER-API.md)를 참고하십시오.

## 업그레이드

1. `plugins/Bridge/config.yml`과 토큰·JWKS·relay identity 파일을 백업합니다.
2. 새로 제공되는 기본 설정과 기존 설정을 비교하고, 추가하려는 작업·이벤트 범위를 명시적으로 부여합니다.
3. JAR을 교체하고 일반적인 서버 재시작으로 적용합니다.

기본 제공 설정은 버전 7입니다. 새로 추가된 WebSocket, JWT, relay 이벤트 설정에 명시적인 기본 거부 값이 있으므로 기존 버전 6 파일도 그대로 읽을 수 있습니다. Bridge는 시작할 때 기존 설정을 덮어쓰지 않으므로, 새 키는 비교 대상인 기본 설정에서만 확인할 수 있습니다.

## 한계와 RCON 전환

Bridge는 원격 호출자가 할 수 있는 일의 범위를 좁히지만, 설치만으로 RCON이 사라지지는 않습니다. 모든 업무 흐름에 대응하는 typed action이 준비되고, 모든 호출자를 전환하고, 전환 결과를 검증한 뒤 RCON 리스너와 인증 정보를 비활성화해야 비로소 RCON을 완전히 폐기할 수 있습니다.

Bridge가 의도적으로 제공하지 않는 것은 다음과 같습니다.

- 범용 콘솔 또는 명령 실행 작업
- TLS 종단 처리 — 원격 접근에는 리버스 프록시, VPN, 서비스 메시를 앞에 두어야 합니다
- 브라우저 인증, 로그인, 계정 연동
- Folia 지원
- 지속적인 이벤트 전달. WebSocket 재전송은 메모리 기반이며 Bridge 재시작 후에는 유지되지 않습니다. 수신 측은 권위 있는 조회로 상태를 다시 구성해야 합니다.

## 보안 모델

- 임의 명령을 실행하는 작업은 존재하지 않습니다.
- 모든 HTTP 클라이언트는 서로 다른 인증 정보와 로컬 작업 범위를 가집니다.
- 이벤트 범위는 작업 범위와 분리되어 있으며, 기존 클라이언트나 과거 호환 클라이언트에 이벤트 권한이 자동으로 부여되지 않습니다.
- JWT 범위는 서버의 로컬 허용 목록과 교집합으로 처리되며 이를 넓힐 수 없습니다.
- 요청 본문, 요청 ID, 캐시, 작업 스레드, 요청 빈도, 동시 실행 수에 모두 상한이 있습니다.
- 작업용 인증 정보와 metrics용 인증 정보는 분리되어 있습니다.
- 기본 리스너는 loopback 전용입니다.
- Bukkit 월드·엔티티 작업은 Paper 주 스레드에서 실행되도록 예약합니다.
- 제공자에서 발생한 예외는 스택 트레이스를 호출자에게 노출하지 않고 안정적인 실패 코드로 변환됩니다.

작업 제공자는 여전히 신뢰된 서버 코드입니다. 악의적인 플러그인을 설치하는 것은 이미 마인크래프트 서버 프로세스 안에서 코드 실행 권한을 주는 것과 같습니다. 취약점은 [SECURITY.md](SECURITY.md)에 안내된 비공개 경로로 제보하십시오.

## 문제 해결과 지원

먼저 `/bridge status`와 서버 로그를 확인하십시오. 처음 설치할 때 자주 발생하는 문제는 다음과 같습니다.

| 증상 | 주요 원인 |
| --- | --- |
| `bridge_token_not_configured` | 유효한 static 클라이언트 또는 JWT 설정이 로드되지 않음 |
| `401 unauthorized` | 헤더의 토큰이 토큰 파일과 정확히 일치하지 않거나 클라이언트 항목이 거부됨 |
| 작업 목록이 비어 있음 | 인증은 성공했지만 해당 클라이언트의 `allowedActions`에 일치하는 항목이 없음 |
| HTTP가 열리지 않음 | 포트를 다른 프로세스가 사용 중이거나, `http.allowNonLoopback: true` 없이 loopback이 아닌 host를 지정했거나, 시작 과정에서 실패 |
| 원격 클라이언트가 `127.0.0.1`에 연결하지 못함 | 정상 동작입니다. 리스너를 직접 노출하지 말고 HTTPS 리버스 프록시, VPN, 사설 터널을 사용하십시오 |
| 클라이언트가 이벤트를 받지 못함 | 이벤트가 비활성 상태이거나, 클라이언트에 `allowedEvents`와 `maxEventConnections`가 없음 |

버그와 기능 요청은 <https://github.com/tdbgo/bridge-minecraft-plugin/issues>에 등록하십시오. 취약점은 공개 이슈가 아니라 [SECURITY.md](SECURITY.md)에 안내된 비공개 경로로 제보해야 합니다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [Quick start](docs/QUICKSTART.md) | 읽기 전용 작업 하나로 시작하는 첫 loopback 설치 |
| [HTTP API](docs/HTTP-API.md) | endpoint, 인증, 실패 코드 |
| [OpenAPI document](docs/openapi.yaml) | 기계 판독용 HTTP 계약 |
| [Player status contract](docs/PLAYER-STATUS.md) | `minecraft.player_status` 요청·결과 형식 |
| [WebSocket events](docs/WEBSOCKET-EVENTS.md) | 이벤트 구독, 재전송, 배압 처리 |
| [JWT authentication](docs/JWT-AUTH.md) | 서명 토큰 계약과 범위 교집합 |
| [Outbound relay protocol](docs/RELAY-PROTOCOL.md) | 아웃바운드 전용 호스트의 수신 서비스 책임 |
| [Observability](docs/OBSERVABILITY.md) | 상태 snapshot, Prometheus 메트릭, 개인정보 경계 |
| [Developer API](docs/DEVELOPER-API.md) | 작업·이벤트 제공자 플러그인 작성 |
| [Compatibility policy](docs/COMPATIBILITY.md) | 런타임, 제공자 API, 전송, 설정 호환성 |
| [Real-time and identity roadmap](docs/FUTURE-AUTH-REALTIME.md) | WebSocket·JWT 도입 배경과 남은 과제 |
| [LILO integration study](docs/LILO-INTEGRATION-STUDY.md) | LILO를 선택적 연동으로 유지하는 이유 |
| [Release checklist](docs/RELEASE-CHECKLIST.md) | 자동·Modrinth·수동 Paper 릴리스 점검 |
| [Contributing](CONTRIBUTING.md) | 빌드, 설계 경계, 호환성 검토 |
| [Security policy](SECURITY.md) | 지원 버전과 취약점 제보 |
| [Changelog](CHANGELOG.md) | 버전별 변경 내역 |

## 라이선스

Bridge의 소스 코드, 바이너리, 제공자 API는 [Apache License 2.0](LICENSE.md)으로 배포됩니다. 필수 저작자 표시는 [NOTICE](NOTICE)에 기록되어 있으며, 함께 포함되는 제3자 구성 요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 나열된 각자의 라이선스를 따릅니다.
