# 게이트웨이 분석 (Spring Cloud Gateway)

API 게이트웨이(Spring Cloud Gateway)를 분석해 **라우트 표면**을 그래프에 노출하고,
**프론트 → 게이트웨이 → 백엔드** 흐름을 연결하는 방식을 정리한다.

대상 코드: `Gateway.kt`(라우트 파싱) · `CrossRun.kt`(게이트웨이 노드/엣지) ·
`Cli.kt`(라우트 테이블 산출물) · `Manifest.kt`/`Sync.kt`(전파) · `docs/web/app.js`(프론트 조인).

---

## 1. 무엇을 게이트웨이로 보는가

`spring.cloud.gateway.routes` 라우트 리스트가 있으면 게이트웨이로 취급한다. 두 경로로 수집:

- **자동 발견** — `Gateway.discover(dir)`: 프로젝트 리소스의 모든 `*.yml`/`*.yaml` 을 walk 하며
  **`spring.cloud.gateway.routes` 중첩 경로의 리스트만**(STRICT) 인정한다. 일반 yml 의 top-level
  `routes:` 는 오인 방지를 위해 무시. 여러 파일에 흩어진 라우트는 `id` 로 dedup 해 병합.
- **명시 지정** — `--gateway-routes <file> [--gateway-name N]`: `Gateway.load` 로 외부 라우트 파일을
  직접 투입(top-level `routes:`/루트 리스트도 허용).

> 실제 게이트웨이는 라우트를 **Config Server 로 외부화**하는 경우가 많다. 그때는 해소된 라우트 YAML 을
> 프로젝트 리소스에 export 하거나 `--gateway-routes` 로 넣어야 발견된다(저장소에 없으면 분석기가 알 수 없음).

## 2. 라우트 1건 파싱 — `Gateway.parseRoute`

| 출처 | 의미 |
|---|---|
| `uri: lb://<svc>` / `http(s)://host` | `targetService` (lb 서비스명 / 호스트) |
| predicate `Path=/api/user/**` | 프론트 prefix |
| predicate `Method=GET,POST` | 허용 메서드(없으면 전체) |
| filter `StripPrefix=N` · `PrefixPath=/x` · `RewritePath=/from,/to` | 경로 변환 |

→ 두 값으로 정규화:
- **`publicPrefix`** — 프론트가 호출하는 prefix (예: `/user`)
- **`backendPrefix`** — 필터 적용 후 백엔드가 실제로 받는 prefix (예: `/user`→RewritePath→`""`(루트), `/oauth`(미변환)→`/oauth`)

predicate/filter 는 `"Name=args"` 문자열 **또는** `{name, args}` 맵 양쪽 형태를 지원하고,
경로는 정규식/글롭/placeholder 직전까지의 **리터럴 prefix만** 취한다.

## 3. 게이트웨이 → 백엔드 (`CrossRun.wireGateways`, `_combined.json`)

`combine` 단계에서:
- 라우트마다 **`GATEWAY` 노드** 생성 (`gateway:<gwName>#<routeId>`).
- `route.targetService` 를 분석된 프로젝트로 매칭(`matchService`, 정규화 후 부분일치)하고,
  그 서비스의 컨트롤러 엔드포인트 중 **`backendPrefix` 로 시작**하고 **메서드가 맞는** 것에
  **`gateway` 엣지**를 연결한다.

> 주의: 이 GATEWAY 노드/엣지는 현재 `_combined.json` 에만 있고 웹 데이터로 sync 되지 않는다.
> (웹앱은 프로젝트별 그래프만 로드)

## 4. 라우트 테이블 산출물 — `projects/<gw>/<gw>.gateway.json`

프론트 조인이 게이트웨이의 **실제 변환**을 쓸 수 있도록, 게이트웨이 프로젝트마다 라우트 테이블을
별도 sibling 으로 내보낸다(`Cli.writeGatewayRoutes`).

```jsonc
{
  "command": "gateway", "name": "tera-cloud-gateway", "routeCount": 14,
  "routes": [
    { "routeId": "auth-service", "publicPrefix": "/oauth", "backendPrefix": "/oauth",
      "targetService": "${tera.service-url.user}", "methods": [], "uri": "..." },
    { "routeId": "user-service", "publicPrefix": "/user",  "backendPrefix": "",
      "targetService": "${tera.service-url.user}", "methods": [], "uri": "..." }
  ]
}
```

- `Manifest` 가 프로젝트 엔트리에 **`gateway`** 필드(상대경로)를 추가.
- `Sync` 가 `<base>.gateway.json` sibling 으로 정규화·취합·prune.

**언제 생성되나 (두 경로 모두 지원)**
- **one-shot** `refresh` (`./gradlew run`, `COMMAND=refresh`) — 발견된 게이트웨이마다 자동 emit.
- **granular** `combine --repo <dir>` (merge 단계, `scripts/03-merge.sh`) — `--repo` 가 주어지면 소스
  트리에서 게이트웨이를 **자동발견**해 `_combined.json` 의 gateway 엣지 + `<name>.gateway.json` 을
  함께 만든다. (`combine` 은 prebuilt 그래프만 받으므로 소스 접근은 `--repo` opt-in 으로 둔다.)

## 5. 프론트 → (게이트웨이) → 백엔드 조인 — `docs/web/app.js`

`loadAndApplyJoins()` 가 `<project>.join.json` 의 프론트 호출을 백엔드 노드에 붙일 때, 순서대로:

1. **matched** — join 이 이미 매칭한 직접 경로
2. **direct** — 프론트 경로 == 백엔드 컨트롤러 엔드포인트 정확 일치
3. **alias** — 토큰 세그먼트가 백엔드 선언 alias 와 일치 (예: nexcore `.jmd` Tid)
4. **gateway** — `gatewayMatch(path, method, ctrlByPath, routes)`:
   - manifest `gateway` 라우트 테이블을 로드(publicPrefix 긴 순 정렬).
   - 프론트 경로의 publicPrefix 를 소유한 라우트를 찾아 그 **`backendPrefix` 로 치환**해 백엔드 경로를
     만들고 컨트롤러와 매칭(후보 유일 시 연결). → `/oauth`(미변환) vs `/user`(프리픽스 제거)처럼
     라우트마다 다른 변환을 정확히 반영.
   - **라우트가 prefix 를 소유하면 그 변환을 신뢰**(백엔드 부재 시 null; 휴리스틱 폴백 금지).
   - 라우트 테이블이 없거나 소유 라우트가 없을 때만 "첫 세그먼트 제거" 휴리스틱으로 폴백.

생성 엣지: `kind:'join'`, `confidence:'gateway'`.

```
FE 외부호출(/user/v3/rsa) ──join(confidence:gateway, 라우트 변환)──▶ backend CONTROLLER(/v3/rsa)
GATEWAY 노드(route /user/**) ──gateway 엣지(targetService+backendPrefix)──▶ 같은 backend  (※ _combined 전용)
```

## 6. 한계 / 주의

- **placeholder URI** (`uri: ${tera.service-url.user}`) 는 분석기가 해소하지 않는다 → `targetService` 가
  `${...}` 리터럴이 되어 ③의 `matchService` 가 백엔드 프로젝트로 매칭되기 어렵다(③ gateway 엣지 누락).
  반면 ⑤의 프론트 조인은 `publicPrefix/backendPrefix`(placeholder 무관, predicate/filter 에서 도출)만
  쓰므로 영향이 없다.
- 라우트가 가리키는 대상이 **인프라 호스트**(예: `ecs-cluster-1...:9081`)면 분석된 서비스명과 매칭되지 않는다.
- GATEWAY 노드는 `_combined.json` 전용이라 웹앱에는 노출되지 않는다(라우트 테이블 `*.gateway.json` 으로만 전파).

## 예시 — `tera-cloud-gateway`

- `build.gradle`: `spring-cloud-starter-gateway` (게이트웨이 O)
- `application-live.yml`: `spring.cloud.gateway.routes` 14건 (uri 는 `${tera.service-url.*}` placeholder)
- 분석 결과: 자동 발견 **14 routes → `tera-cloud-gateway.gateway.json`**, `_combined` 에 GATEWAY 노드 14개.
  프론트 조인은 이 라우트 테이블로 `publicPrefix→backendPrefix` 변환을 정확히 수행한다.
