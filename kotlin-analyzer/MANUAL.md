# kotlin-analyzer 매뉴얼

Spring(Kotlin/Java) 멀티서비스 코드베이스를 **Kotlin 컴파일러 K1 프론트엔드(PSI +
BindingContext)** 로 정적 분석해서 ① 콜그래프, ② 서비스 간(S2S) 통합 그래프, ③ OpenAPI
3.1 API 문서, ④ git 커밋 기반 변경 영향도를 만든다.

이 문서는 명령별 사용법 · 옵션 · 출력 스키마 · 웹 연동까지의 상세 레퍼런스다. 빠른 개요는
[`README.md`](./README.md) 참고.

---

## 목차

1. [핵심 개념](#1-핵심-개념)
2. [설치 & 빌드](#2-설치--빌드)
3. [명령 레퍼런스](#3-명령-레퍼런스)
   - [analyze](#31-analyze--콜그래프-추출)
   - [combine](#32-combine--서비스-간-통합)
   - [openapi](#33-openapi--openapi-31-생성)
   - [impact](#34-impact--변경-영향도)
   - [search / stats](#35-search--stats)
4. [출력 JSON 스키마](#4-출력-json-스키마)
5. [REST Docs 연동](#5-rest-docs-연동)
6. [웹(JS) 연동 가이드](#6-웹js-연동-가이드)
7. [한계 & 트러블슈팅](#7-한계--트러블슈팅)

---

## 1. 핵심 개념

### node id 규약 — 모든 산출물의 공통 키

```
<fqcn>#<method>           예) com.acme.order.OrderService#placeOrder
ext:<service>#<method>    외부(Feign/RestTemplate/WebClient) 호출 노드
gateway:<name>#<routeId>  게이트웨이 라우트 노드(layer GATEWAY)
kafka:<topic> / redis / db:table:<t> / db:jdbc   인프라 리소스 노드
```

- Kotlin 합성 `.Companion` 세그먼트는 제거(companion 메서드를 enclosing 클래스에 귀속).
- 중첩 클래스 표기(`Outer.Inner`)는 충돌 방지를 위해 **보존**.
- 이 규칙은 콜그래프 노드, OpenAPI `operationId`, impact의 변경 메서드에 **동일 적용**된다.
  덕분에 그래프 ↔ API 문서 ↔ 영향도가 별도 매핑 없이 조인된다.

### 레이어 분류

`@RestController/@Controller`=CONTROLLER, `@Service`=SERVICE, `@Repository`/`JpaRepository`
상속=REPOSITORY, Spring Batch=BATCH, 외부 클라이언트=EXTERNAL, 그 외=OTHER(기본 미추적,
`--include-other`로 포함). 인프라 리소스=RESOURCE.

### 파이프라인

```
소스 → AnalysisSession(PSI+BindingContext) → IR ┬→ GraphBuilder → 콜그래프 JSON ─┐
                                                 ├→ OpenApi → OpenAPI 3.1 JSON     │
                                                 └→ (REST Docs 보강)               │
                          서비스별 그래프들 → CrossRun.combine → 통합 그래프 ───────┤
   git repo → GitLog(커밋/diff) + PsiSourceParser(변경라인→메서드) → Impact ───────┘
                                                          (현재 그래프에 조인 + 역방향 BFS)
```

---

## 2. 설치 & 빌드

요구: JDK 17+, (impact 사용 시) `git` CLI가 PATH에 존재.

```bash
cd kotlin-analyzer
gradle wrapper --gradle-version 8.12     # 최초 1회
./gradlew build                          # 컴파일 + 테스트
./gradlew run --args="<command> ..."     # 실행
```

의존성은 `kotlin-compiler-embeddable:2.0.21`(K1 프론트엔드) 하나가 핵심이고 `snakeyaml`,
`jackson`, 그리고 분석 classpath에 올리는 Spring 애너테이션 jar들이 보조다. **impact의
git 접근은 외부 의존성 없이 `git` CLI를 호출**한다(버전 안정).

---

## 3. 명령 레퍼런스

공통 옵션: `--out <file>`(생략 시 stdout), `--repo <dir>`(기본 `../.repo`),
`--project <name>`(단일 프로젝트), `--profile <p>`(yml 프로파일), `--props <kv.txt>`.

### 3.1 `analyze` — 콜그래프 추출

소스를 의미 분석해 node-link 콜그래프를 만든다.

| 옵션 | 설명 |
|---|---|
| `--repo <dir>` | 분석 루트(기본 `../.repo`) |
| `--project <P>` | 특정 프로젝트만 |
| `--include-other` | OTHER 레이어 노드도 포함 |
| `--profile <p>` | `application-<p>.yml`로 `${...}` 치환 |
| `--props <file>` | `key=value` 한 줄씩, `${...}` 오프라인 보강 |
| `--restdocs <dir>` | REST Docs 스니펫에서 엔드포인트 `description` 부착 |
| `--out <file>` | 출력 경로 |

```bash
./gradlew run --args="analyze --repo ../.repo --project sample-shop --out /tmp/shop.json"
./gradlew run --args="analyze --repo ../.repo --project user-service \
  --restdocs ../.repo/user-service/build/generated-snippets --out ./json/user-service.json"
```

무엇을 잡나: 내부 호출 / Feign·RestTemplate·WebClient·`@HttpExchange` 외부호출(verb·path·
실제URL·`urlPlaceholder`·`clientPackage`) / `@Async`·코루틴 async 모드 / Spring Batch
job→step→reader/processor/writer / Kafka produce·consume / Redis·JDBC / JPA 테이블 /
Spring Data 상속 메서드(save/findById…).

### 3.1.1 `refresh` — pull + 전체 일괄 갱신

`.repo`의 각 프로젝트를 **현재 체크아웃된 브랜치로 pull**한 뒤, 전 프로젝트를 재분석해
`<p>.json` / `<p>.openapi.json` 와 통합 산출물(`_combined.json`, `_openapi.json`)을 한 번에
갱신한다. 소스 pull 후 "다시 분석" 워크플로를 한 명령으로 묶은 것.

| 옵션 | 설명 |
|---|---|
| `--repo <dir>` | 프로젝트들이 있는 루트(기본 `../.repo`) |
| `--out-dir <dir>` | 산출물 디렉터리(기본 `./json`) |
| `--no-pull` | git pull 생략(재분석만) |
| `--include-other` / `--profile` / `--props` | analyze와 동일 |
| `--title <T>` | repo 전체 `_openapi.json`의 `info.title`(기본 `flowmap-all`) |

동작:
1. 각 프로젝트 디렉터리가 git work tree면 `git pull --ff-only`(현재 브랜치). git이 아니면 pull 스킵.
   `--restdocs`는 `<project>/build/generated-snippets`가 있으면 자동 사용.
2. 프로젝트당 **1회 분석**으로 콜그래프와 OpenAPI를 함께 생성(소스가 없는 디렉터리는 건너뜀 → 고스트 없음).
3. `--out-dir`에서 현재 존재하지 않는 프로젝트의 `*.json`/`*.openapi.json`(고스트)을 자동 제거.
4. 메모리상 그래프로 `combine` → `_combined.json`, 누적 IR로 repo 전체 `_openapi.json` 생성.

```bash
./gradlew run --args="refresh --repo ../.repo --out-dir ./json"
./gradlew run --args="refresh --repo ../.repo --no-pull"     # 이미 최신이면 pull 생략
```

> 결정적·멱등: 같은 소스에서 재실행하면 동일 산출물. 소스 pull/프로젝트 추가 후에는 이 한
> 명령이면 [§7](#7-한계--트러블슈팅)의 stale/ghost 함정을 모두 피한다.

### 3.2 `combine` — 서비스 간 통합

서비스별 그래프를 합쳐 **S2S(service-to-service)** 엣지와 이벤트 흐름을 잇는다.

| 옵션 | 설명 |
|---|---|
| `--dir <dir>` | 디렉터리의 모든 `*.json`(단, `_*.json` 출력은 제외) |
| `--graphs a.json,b.json,...` | 명시적 그래프 목록 |
| `--out <file>` | 출력 경로 |

연결 규칙:
- Feign/`@HttpExchange` 외부호출(verb+path)이 다른 서비스 컨트롤러 엔드포인트와 매칭 →
  `external` 엣지를 `s2s`로 재연결(미매칭 서드파티는 `external` 유지).
- Kafka: produce→topic→consume 가 공유 토픽 리소스 노드로 자동 연결.
- Redis / DB 테이블도 공유 리소스 노드로 서비스 간 결합 표현.

```bash
./gradlew run --args="combine --dir ./json --out ./json/_combined.json"
```

### 3.2.1 게이트웨이 flow (`combine --gateway-routes`)

Spring Cloud Gateway 라우트 테이블을 주면, **게이트웨이를 별도 노드(layer `GATEWAY`)로 분리**하고
프론트-facing 경로 → 게이트웨이 → 백엔드 서버 엔드포인트를 `gateway` 엣지로 잇는다. 게이트웨이가
경로를 재작성(StripPrefix/PrefixPath/RewritePath)하므로 **공개 경로와 서버 경로가 다른 점**을 표현한다.

| 옵션 | 설명 |
|---|---|
| `--gateway-routes <file>` | 라우트 yml. 라우트가 Config Server로 외부화됐으면 resolved yml을 공급 |
| `--gateway-name <N>` | 게이트웨이 노드 식별자(기본: 파일명) |

라우트 yml 위치: `spring.cloud.gateway.routes` / 최상위 `routes:` / 문서 루트가 리스트. 각 라우트에서
`id`, `uri`(`lb://<service>`→타깃 서비스), `predicates`의 `Path`(+선택 `Method`), `filters`
(`StripPrefix=N`, `PrefixPath=/x`, `RewritePath=from,to`)를 읽는다.

매칭: 필터로 **백엔드 경로 prefix**를 계산 → 타깃 서비스의 컨트롤러 엔드포인트 중 그 prefix로 시작하는
것에 `gateway` 엣지 연결(verb는 combine과 동일하게 ANY 허용). 노드 필드: `endpoint`=공개 prefix,
`externalService`=타깃 서비스, `externalUrl`=`lb://...`, `description`=필터 요약.

```bash
./gradlew run --args="combine --dir ./json \
  --gateway-routes /path/gateway-routes.yml --gateway-name tera-cloud-gateway --out ./json/_combined.json"
```

예 (검증됨): `RewritePath=/api/fund/(?<seg>.*), /internal/investment/${'$'}{seg}` 라우트 →
`프론트 GET /api/fund/current-summary` 가 `서버 GET /internal/investment/current-summary`
(`InvestmentController#currentValidSummary`)로 연결된다. 공개 경로 = `publicPrefix` + (서버 경로에서
`backendPrefix` 제거분).

> 한계: 프론트(JS/TS) 소스는 이 도구가 분석하지 않으므로 **프론트→게이트웨이 엣지는 자동 생성되지
> 않는다**. 게이트웨이 라우트 노드를 "공개 진입점"으로 본다. 게이트웨이가 직접 서빙하는 컨트롤러
> (예: `ImageController`)는 그 repo를 `analyze`하면 일반 CONTROLLER 노드로 잡힌다.

### 3.3 `openapi` — OpenAPI 3.1 생성

엔드포인트의 **요청/응답 페이로드를 정적 타입에서 스키마로** 추출해 OpenAPI 3.1 문서를
만든다.

| 옵션 | 설명 |
|---|---|
| `--repo` / `--project` | 분석 대상(`--project` 생략 시 repo 전체를 서비스 tag로 묶은 단일 문서) |
| `--restdocs <dir>` | `description.adoc`→`summary`, `http-request/response.adoc` 바디→`example` |
| `--title <T>` | `info.title`(기본 프로젝트명/`API`) |
| `--api-version <V>` | `info.version`(기본 `1.0.0`) |
| `--out <file>` | 출력 경로 |

매핑 규칙:
- `@RestController`/`@Controller` 메서드 → `paths.<path>.<verb>`.
- DTO(data class) → `components.schemas`(생성자 val + 본문 프로퍼티, 제네릭 `List`/`Map`,
  중첩 DTO, enum까지 재귀, 순환 가드).
- 타입 매핑: `Long`→int64, `Int`→int32, `BigDecimal`→number, `UUID`→string(uuid),
  `LocalDate`→date, `LocalDateTime`/`Instant`→date-time, `List<T>`→array,
  `Map<K,V>`→object(additionalProperties), 미해석/`Any`→`{}`.
- 파라미터: `@PathVariable`→path, `@RequestParam`→query, `@RequestHeader`→header,
  `@RequestBody`/미주석 복합타입→requestBody, 미주석 단순타입→query.
  서블릿/시큐리티/페이징 등 프레임워크 주입 타입은 제외.
- `@RequestMapping`(verb 미지정, 내부적으로 "ANY")은 바디 유무로 POST/GET 추론.
- `operationId == 그래프 node id` → 콜그래프와 상호 링크. 오버로드 충돌 시 `~2` 접미.
- 경로의 `{var}`에 대응하는 path 파라미터가 없으면 string 타입으로 자동 보강(스펙 유효성).

```bash
# 단일 서비스
./gradlew run --args="openapi --repo ../.repo --project funding-service --out ./json/funding-service.openapi.json"
# REST Docs 예시 보강
./gradlew run --args="openapi --repo ../.repo --project twice-api \
  --restdocs ../.repo/twice-api/build/generated-snippets --out ./json/twice-api.openapi.json"
# repo 전체(서비스 tag) 단일 문서 — 웹 렌더러에 그대로 투입
./gradlew run --args="openapi --repo ../.repo --title flowmap-all --out ./json/_openapi.json"
```

출력은 표준 OpenAPI 3.1이라 [openapi-spec-validator]로 검증되고 Redoc/Scalar/Swagger UI/
Stoplight Elements가 그대로 렌더한다.

### 3.4 `impact` — 변경 영향도

git 커밋의 **변경 라인 → 변경 메서드 → 현재 그래프 역방향 BFS** 로 영향받는 엔드포인트·
서비스를 산출한다. (모드: "최근 범위 → 현재 그래프".)

| 옵션 | 설명 |
|---|---|
| `--git <repo>` | 마이닝할 git 작업 트리(없으면 `<--repo>/<--project>`, 그것도 없으면 `--repo`) |
| `--graph <g.json>` | 조인할 현재 그래프(없으면 `--repo`/`--project`로 즉석 분석) |
| `--branch <b>` | 기본 브랜치 강제(미지정 시 origin/HEAD→main→master→develop 자동탐지) |
| `--max <N>` | 최신 N개 커밋(기본 50) |
| `--range <A..B>` | 커밋 구간(예: `v1.2.0..HEAD`). `--max`보다 우선 |
| `--depth <N>` | 역방향 BFS 깊이(기본 3) |
| `--out <file>` | 출력 경로 |

동작:
1. `git log`로 커밋 목록(merge 제외), 커밋별 `git show -U0 -M`으로 변경 파일 + 신측 헌크 라인범위.
2. 변경된 `.kt`를 **그 시점 blob 내용**으로 PSI 재파싱 → `(fqcn#method, 라인범위)`. 헌크와
   교집합 → 변경 메서드. (node id 규칙 동일 → 현재 그래프에 그대로 조인.)
3. 변경 node를 현재 그래프에 조인하고 `Bfs(CALLERS, depth)`로 호출자 전파 → 영향 엔드포인트/서비스.
4. 현재 그래프에 없는 메서드(rename/삭제/미추적)는 `inGraph:false`로 표기.

**삭제 엔드포인트 + breaking 감지**:
5. 각 변경 파일의 **부모 리비전 blob**도 파싱해 `old − new`(node id 차집합) → 삭제 메서드.
   그중 컨트롤러 엔드포인트였던 것(verb+path를 PSI에서 추출)이 **삭제 엔드포인트**.
6. 삭제 엔드포인트의 `(verb, normPath)`가 **현재 그래프의 다른 컨트롤러에 여전히 존재**하면
   "이동/리팩터"로 보고 `pathStillServed:true`로 다운그레이드(진짜 삭제 아님).
7. 진짜 삭제(`pathStillServed:false`)인데 **현재 그래프의 외부호출 노드가 그 (verb,path)를 아직
   타깃**으로 하면 `breaking:true` + 호출 서비스 목록. 교차서비스 breaking을 보려면 `--graph`에
   `_combined.json`을 넘긴다.

> 주의: 삭제 판정은 node id(`fqcn#method`) 기준이라, 파일 내 **메서드 rename**은 삭제+추가로
> 보일 수 있다(파일 rename은 `-M`으로 처리). 그래서 6의 path-level 확인으로 이동을 걸러낸다.

```bash
# 최신 40커밋, 깊이 3
./gradlew run --args="impact --git ../.repo/tera-cloud-user \
  --graph ./json/tera-cloud-user.json --max 40 --depth 3 --out /tmp/impact.json"
# 태그 구간 + 즉석 분석
./gradlew run --args="impact --git ../.repo/mysvc --repo ../.repo --project mysvc \
  --range v1.2.0..HEAD --out /tmp/impact.json"
# 교차서비스 영향까지: 통합 그래프를 넘김
./gradlew run --args="impact --git ../.repo/tera-cloud-user --graph ./json/_combined.json --out /tmp/impact.json"
```

### 3.5 `search` / `stats`

```bash
# 특정 메서드의 callers/callees 서브그래프(BFS)
./gradlew run --args="search --method placeOrder --graph /tmp/shop.json --direction both --depth 3"
# 레이어/엣지 요약
./gradlew run --args="stats --graph /tmp/shop.json"
```
`search`: `--method`(id/메서드명/부분일치), `--direction both|callers|callees`, `--depth`,
`--graph` 또는 `--repo`. `stats`: `--graph` 또는 `--repo`/`--project`.

---

## 4. 출력 JSON 스키마

### 4.1 콜그래프 (analyze / combine / search)

node-link 형식. 키 순서·null 포함은 Python 도구 계약과 일치.

```jsonc
{
  "directed": true, "multigraph": false,
  "meta": { "command": "...", "nodes": 0, "edges": 0, "s2sEdges": 0 },
  "nodes": [{
    "id": "com.acme.order.OrderService#placeOrder",
    "fqcn": "com.acme.order.OrderService", "method": "placeOrder",
    "layer": "SERVICE", "visibility": "public", "async": false,
    "returnType": "OrderResponse",
    "httpMethod": null, "endpoint": null,            // 컨트롤러면 "POST","/orders"
    "externalService": null, "externalUrl": null,    // 외부 노드면 채워짐
    "urlPlaceholder": null, "clientPackage": null,   // ADDITIVE
    "resourceType": null,                            // "kafka-topic"|"redis"|"db-table"
    "description": null,                             // REST Docs 설명
    "file": "order-service/src/...", "line": 16, "project": "order-service", "module": "..."
  }],
  "edges": [{
    "source": "...#a", "target": "...#b",
    "mode": "sync",                                  // "sync"|"async"
    "kind": "internal",                              // internal|external|s2s|gateway|batch|resource
    "relation": "call",                              // call|batch:step|kafka:produce|db:io|...
    "callSiteFile": "...", "callSiteLine": 12
  }]
}
```

### 4.2 OpenAPI 3.1 (openapi)

표준 OpenAPI 3.1. 핵심: `paths.<path>.<verb>.operationId == 그래프 node id`,
`tags[].name == 서비스명`, DTO는 `components.schemas`에 `$ref`.

```jsonc
{
  "openapi": "3.1.0",
  "info": { "title": "funding-service", "version": "1.0.0" },
  "tags": [{ "name": "funding-service" }],
  "paths": { "/internal/investment/current-summary": { "get": {
    "operationId": "com.acme.funding.InvestmentController#currentValidSummary",
    "tags": ["funding-service"], "summary": "유효 투자 현재 요약 조회",
    "responses": { "200": { "content": { "application/json": {
      "schema": { "$ref": "#/components/schemas/SummaryView" } } } } } } } },
  "components": { "schemas": { "SummaryView": {
    "type": "object",
    "properties": { "totalAmount": { "type": "integer", "format": "int64" },
                    "count": { "type": "integer", "format": "int32" } },
    "required": ["totalAmount", "count"] } } }
}
```

### 4.3 영향도 (impact)

```jsonc
{
  "branch": "develop", "commitCount": 300, "depth": 3, "changedNodeCount": 330,
  "deletedEndpointCount": 71, "trulyDeletedEndpointCount": 9, "breakingDeletionCount": 0,
  "commits": [{
    "sha": "...", "shortSha": "8861efab", "author": "...", "date": "2026-...T..",
    "subject": "feature: ...",
    "changedFiles": ["tera-user-service/src/.../UsebClient.kt"],
    "changedNodes": [{ "id": "...UsebClient#statusIdCard", "inGraph": true }],
    "deletedNodes": ["...EncryptController#rsa"],
    "deletedEndpoints": [{ "id": "...EncryptController#rsa", "httpMethod": "GET", "endpoint": "/v3/encrypt/rsa" }],
    "impactedEndpoints": [{ "id": "...VerifyController#uploadOCR",
      "httpMethod": "POST", "endpoint": "/v1/verify/ocr/upload",
      "service": "tera-cloud-user", "description": null }],
    "impactedServices": ["tera-cloud-user"]
  }],
  "subgraph": { /* node-link: 변경노드 + 호출자 체인 (4.1 스키마) */ },
  "endpointImpact": [{ "id": "...#uploadOCR", "httpMethod": "POST",
    "endpoint": "/v1/verify/ocr/upload", "service": "tera-cloud-user",
    "description": null, "commits": ["101d3345", "8861efab"] }],
  "deletedEndpoints": [{ "id": "...EncryptController#rsa",
    "httpMethod": "GET", "endpoint": "/v3/encrypt/rsa",
    "removedInCommits": ["d0bfb772"],
    "pathStillServed": false,        // true면 다른 컨트롤러로 이동(진짜 삭제 아님)
    "breaking": false,               // true면 아래 서비스가 사라진 API를 아직 호출
    "stillCalledBy": [/* { "caller": "<nodeId>", "service": "..." } */] }]
}
```
- `commits[]` — 커밋별 상세(변경노드 + **삭제노드/삭제엔드포인트** + 영향 엔드포인트/서비스).
- `subgraph` — 변경 노드 + 호출자 서브그래프(웹 하이드레이트용 node-link).
- `endpointImpact[]` — 엔드포인트 → 영향 커밋 집계(릴리스 노트/리뷰용, 영향 커밋 수 내림차순).
- `deletedEndpoints[]` — 삭제 엔드포인트 집계. `pathStillServed=false`가 진짜 삭제, `breaking=true`는
  사라진 API를 아직 호출하는 서비스가 있는 호환성 위반(breaking 우선 → 진짜삭제 우선 정렬).
  웹에선 tombstone/⚠ 배지로 표현.

---

## 5. REST Docs 연동

Spring REST Docs는 op마다 `build/generated-snippets/<op>/`에 `.adoc`을 낸다. 분석기는:

- `http-request.adoc` → `(verb, path)`. 경로의 `{var}`·숫자·UUID는 `{}`로 정규화해 매칭.
- 같은 폴더 `description.adoc` 첫 줄 → 설명(없으면 폴더명 폴백).
- (openapi 한정) `http-request.adoc`/`http-response.adoc`의 바디 → 요청/응답 `example`.

`analyze --restdocs`는 컨트롤러 엔드포인트 노드에 `description`을 붙이고 이는 `combine`의
S2S 타깃 노드까지 전파된다. `openapi --restdocs`는 `summary` + `example`로 쓰인다.
실제 프로젝트에서는 REST Docs 테스트 실행으로 스니펫이 생기므로 그 디렉터리를 `--restdocs`로
지정하면 된다.

---

## 6. 웹(JS) 연동 가이드

산출물이 모두 표준/안정 JSON이라 **빌드타임 생성 → 런타임 정적 소비**가 정석이다. `.adoc`
파싱·타입해석은 분석기(JVM)에서 끝나므로 JS에서 Asciidoctor를 돌릴 필요가 없다.

권장 구성:

1. **API 문서** — `_openapi.json`을 **Redoc / Scalar / Swagger UI / Stoplight Elements**
   중 하나에 그대로 투입(렌더링 코드 0). 큰 서비스는 서비스별 `*.openapi.json`을 lazy-load.
2. **토폴로지 뷰** — `_combined.json`(콜그래프/S2S)을 그래프 라이브러리(예: Cytoscape)로 렌더.
3. **상호 링크** — `operationId == 그래프 node id` 이므로, API 문서에서 엔드포인트를 클릭하면
   토폴로지의 해당 노드로(또는 그 반대) 이동하도록 같은 키로 조인한다.
4. **영향도 오버레이** — `impact.json`의 `subgraph`/`endpointImpact`를 토폴로지·문서 위에
   오버레이(예: "이번 릴리스에서 바뀐 엔드포인트" 배지). `commits[].impactedEndpoints[].id`가
   동일 키.

파일 처리 원칙: 정적 자산으로 서빙하거나 번들에 포함, 키는 node id로 통일, 스키마 변경은
`info.version`/`meta`로 추적.

---

## 7. 한계 & 트러블슈팅

- **opaque 타입**: `Map<String, Any>` 같은 타입은 필드 스키마가 없어 `additionalProperties`로만
  표현된다(실제 DTO를 쓰면 완전 해석).
- **컴포넌트 이름 충돌**: OpenAPI 스키마는 `simpleName` 기준(충돌 시 `Name2`). 패키지 다른
  동명 DTO가 많으면 FQCN 키로 바꾸는 게 안전.
- **impact는 git 작업 트리 필요**: `--git`이 git repo여야 한다. 기본 브랜치가 main/master가
  아닐 수 있으니(예: `develop`) 자동탐지 실패 시 `--branch` 지정.
- **impact의 시점 정합성**: 현재 그래프 기준 조인이라, HEAD에서 사라진(rename/삭제) 메서드는
  `inGraph:false`로만 보고된다. 전이력 시점별 그래프가 필요하면 커밋마다 재분석하는 별도
  모드가 필요.
- **field 표 미파싱**: REST Docs `request-fields.adoc`/`response-fields.adoc`의 필드별 설명
  표는 아직 파싱하지 않는다(예시 바디는 부착). 필요 시 `RestDocs`에 표 파서 추가 가능.
- **빌드 에러(컴파일러 버전)**: K1 시그니처가 minor 버전 간 바뀔 수 있다. `README.md`의
  "버전 민감 심볼 체크리스트" 참고(전부 `AnalysisSession.kt`).
- **shading 주의**: `kotlin-compiler-embeddable`는 IntelliJ 클래스를
  `org.jetbrains.kotlin.com.intellij.*`로 relocate한다. `PsiElement`/`Disposer`는 거기서
  import한다.

[openapi-spec-validator]: https://github.com/python-openapi/openapi-spec-validator
