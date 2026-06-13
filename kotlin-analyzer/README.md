# kotlin-analyzer — Kotlin 컴파일러 프론트엔드 기반 콜그래프 도구

Python 휴리스틱 도구(`../callgraph/`)를 **Kotlin 컴파일러 K1 프론트엔드(PSI +
BindingContext)** 로 재구현한 버전. 호출을 의미 분석으로 해석하고, 외부 API 호출의
**정확한 path + 실제 URL + 외부 서비스 식별(패키지 포함)** 을 뽑는다. 출력은 동일한
node-link JSON.

> ℹ️ 원래 K2 Analysis API(standalone)로 설계했으나, `-for-ide` 아티팩트와 그 전이
> 의존성이 공개 저장소에 2.0.21 버전으로 publish되어 있지 않아 빌드 불가였다. 그래서
> **Maven Central에만 있는 `kotlin-compiler-embeddable`(K1 프론트엔드)** 로 전환했다.
> 기능/출력/아키텍처는 동일하고, 의미 분석 정확도도 동등(상수 인라인은 오히려 K1
> `COMPILE_TIME_VALUE`가 직접 제공). `Resolver` 인터페이스 뒤 3파일만 K1 구현이다.

## 기능 한눈에

하나의 PSI/BindingContext 분석 위에서 5개 명령이 동작한다. 모든 산출물의 **공통 키는
node id(`<fqcn>#<method>`)** 라서 그래프·API 문서·영향도가 서로 조인된다.

| 명령 | 입력 | 산출 | 용도 |
|---|---|---|---|
| `analyze` | repo 소스(+`--restdocs`) | node-link 콜그래프 JSON | 단일 서비스 호출/외부호출/리소스 그래프 |
| `refresh` | repo(git) | 위 산출물 일괄 갱신 | pull + 전 프로젝트 재분석/openapi/combine 한 방에 |
| `combine` | 서비스별 그래프들 | 통합 그래프(S2S/이벤트 연결) | 서비스 간 호출·Kafka·DB 결합 |
| `openapi` | repo 소스(+`--restdocs`) | **OpenAPI 3.1 JSON** | 요청/응답 페이로드 스키마 → Redoc/Scalar 웹문서 |
| `impact` | git repo + 현재 그래프 | 커밋별 **변경 영향도 JSON** | 커밋 변경 → 메서드 → 영향 엔드포인트/서비스 |
| `search`/`stats` | 그래프 | 서브그래프 / 요약 | 특정 메서드 BFS, 통계 |

> 상세 매뉴얼(명령별 옵션·출력 스키마·웹 연동): [`MANUAL.md`](./MANUAL.md)

## 무엇이 좋아지나 (Python 대비)

| 케이스 | Python(정규식) | 이 도구(의미분석) |
|---|---|---|
| `restTemplate.exchange(URI(FACEBOOK_PROFILE_URL))` | URL 모름 | `const` 추적 → `https://graph.facebook.com/v8.0/me` |
| Feign `url="${app.service-url.payment}"` | placeholder만 | yml 있으면 실제값, 없으면 placeholder 보존 |
| WebClient `@Value("${..baseUrl}")` + `.uri{path}` | 못 봄 | baseUrl(@Value)+path 결합 |
| 오버로드/확장함수/암시적 receiver 호출 | 부정확 | 심볼 해석으로 정확 |
| 외부 노드에 패키지 | 없음 | `clientPackage` 추가 |

추가 JSON 키(기존 계약에 **additive**): `urlPlaceholder`(원본 `${...}`), `clientPackage`, `resourceType`(RESOURCE 노드 종류: `kafka-topic`|`redis`|`db-table`), `description`(컨트롤러 엔드포인트 REST Docs 설명).

**노드 id 규약**: `<fqcn>#<method>`. Kotlin 합성 `.Companion` 세그먼트는 제거해 companion 메서드를 enclosing 클래스에 귀속(Java static·Python 도구와 동일). 중첩 클래스 표기(`Outer.Inner`)는 충돌 방지를 위해 **보존**(Python은 평탄화). 선언 노드와 호출 타깃 양쪽에 동일 적용돼 엣지 연결이 유지된다.

**인프라 리소스 탐지**(Python 도구와 동등): `@Entity`/`@Table` + `JpaRepository<E,Id>` → `db:table:*` 노드, `KafkaTemplate.send("topic")`/`@KafkaListener` → `kafka:*` 토픽 노드(produce/consume 엣지), `RedisTemplate`/`JdbcTemplate` → `redis`/`db:jdbc` 노드. 토픽 노드 id가 서비스 간 공유되므로 `combine`에서 이벤트 흐름이 자동으로 이어진다.

## 빌드 & 실행

```bash
cd kotlin-analyzer
gradle wrapper --gradle-version 8.12      # 최초 1회 (래퍼 커밋)
./gradlew build

# 분석 → node-link JSON
./gradlew run --args="analyze --repo ../.repo --project sample-shop --out /tmp/shop.json"
./gradlew run --args="analyze --repo ../.repo --project <your-project> --profile local --out /tmp/out.json"

# REST Docs 설명 부착: 엔드포인트에 한글/API 설명을 붙임 (s2s 엣지에도 전파됨)
#   build/generated-snippets/<op>/http-request.adoc 에서 (verb, path)를 읽고
#   같은 폴더의 description.adoc 한 줄을 설명으로 사용(없으면 폴더명으로 폴백).
./gradlew run --args="analyze --repo ../.repo --project user-service --restdocs ../user-service/build/generated-snippets --out /tmp/us.json"

# refresh: .repo의 각 프로젝트를 현재 브랜치로 pull → 전체 재분석(graph+openapi+combine)
#   - 프로젝트별 현재 체크아웃 브랜치를 git pull --ff-only (git repo만; --no-pull로 생략)
#   - 프로젝트당 1회 분석으로 <p>.json / <p>.openapi.json 생성(재사용)
#   - 사라진 프로젝트의 고스트 JSON 자동 제거, _combined.json + _openapi.json 재생성
./gradlew run --args="refresh --repo ../.repo --out-dir ./json"
./gradlew run --args="refresh --repo ../.repo --no-pull"     # pull 없이 재분석만

# OpenAPI 3.1 생성: 엔드포인트의 요청/응답 페이로드를 정적 타입에서 스키마로 추출
#   - @RestController/@Controller 엔드포인트 → paths, DTO(data class) → components/schemas
#   - K1 BindingContext로 파라미터/반환 타입을 풀 해석(제네릭 List/Map/중첩 DTO/enum 재귀)
#   - operationId == 그래프 node id(<fqcn>#<method>) 라 콜그래프/문서 상호 링크 유지
#   - --restdocs 주면 description.adoc → summary, http-request/response.adoc 바디 → example 부착
#   - --project 생략 시 repo 전체를 한 문서로(서비스별 tag). Redoc/Scalar/Swagger UI에 그대로 투입
./gradlew run --args="openapi --repo ../.repo --project funding-service --out ./json/funding-service.openapi.json"
./gradlew run --args="openapi --repo ../.repo --restdocs ../.repo/twice-api/build/generated-snippets --out ./json/twice-api.openapi.json"
./gradlew run --args="openapi --repo ../.repo --title flowmap-all --out ./json/_openapi.json"

# 변경 영향도(impact): git 커밋의 변경 라인 → 변경 메서드 → 현재 그래프 역방향 BFS
#   - git CLI로 기본 브랜치(main→master→develop 자동탐지) 커밋 목록 + 커밋별 -U0 헌크
#   - 변경 .kt를 그 시점 blob 내용으로 PSI 재파싱해 (fqcn#method, 라인범위) 산출 → 헌크와 교집합
#   - 변경 node id를 현재 분석 그래프에 조인, Bfs(CALLERS)로 영향받는 엔드포인트/서비스 전파
#   - 삭제 엔드포인트: 부모 blob과 old−new 비교로 탐지, 경로가 현재도 제공되면 '이동'으로 다운그레이드,
#     진짜 삭제인데 아직 호출하는 서비스가 있으면 breaking 경보(_combined.json 넘기면 교차서비스까지)
#   - 산출: commits[], subgraph(변경+호출자), endpointImpact[](엔드포인트→커밋), deletedEndpoints[](+breaking)
./gradlew run --args="impact --git ../.repo/tera-cloud-user --graph ./json/tera-cloud-user.json --max 40 --depth 3 --out /tmp/impact.json"
./gradlew run --args="impact --git ../.repo/<proj> --repo ../.repo --project <proj> --range v1.2.0..HEAD --out /tmp/impact.json"

# cross-run combine: 프로젝트별 그래프를 합쳐 서비스 간 호출(S2S)/이벤트 연결
#   - Feign/HttpExchange 외부 호출(verb+path)이 다른 서비스 컨트롤러 엔드포인트와
#     매칭되면 external → s2s 엣지로 재연결 (미매칭 서드파티는 external 유지)
#   - Kafka는 produce→topic→consume 가 공유 토픽 리소스 노드를 통해 자동 연결
#     (한 서비스가 produce, 다른 서비스가 @KafkaListener consume)
#   - Redis / DB 테이블도 공유 리소스 노드로 서비스 간 결합을 표현
./gradlew run --args="combine --dir ./json --out ./json/_combined.json"
./gradlew run --args="combine --graphs ./json/tera-cloud-user.json,./json/bank-broker.json --out /tmp/c.json"

# 검색(BFS) / 통계
./gradlew run --args="search --method placeOrder --graph /tmp/shop.json --direction both --depth 3"
./gradlew run --args="stats --graph /tmp/shop.json"
```

옵션: `analyze --repo --project --out --include-other --profile --props kv.txt --restdocs <snippets-dir>`,
`refresh --repo --out-dir <json dir> --no-pull --include-other --profile --props --title`  (전 프로젝트 pull+재분석+combine+openapi 일괄 갱신),
`openapi --repo --project --out --restdocs <snippets-dir> --title --api-version --profile --props`  (OpenAPI 3.1 JSON 출력; `--project` 생략 시 repo 전체를 서비스 tag로 묶은 단일 문서),
`impact --git <repo> (--graph g.json | --repo --project) --branch --max N | --range A..B --depth --out`  (커밋 변경 → 메서드 → 영향도; 기본 브랜치 자동탐지),
`combine --graphs a.json,b.json,... | --dir <dir of *.json> --out`  (`--dir`는 `_*.json` 출력 제외),
`search --method --graph|--repo --direction both|callers|callees --depth --out`,
`stats --graph|--repo --project --profile`.
`--props`는 `key=value` 한 줄씩 파일로 `${...}`를 오프라인 보강(예: configserver 값 대체).

## 아키텍처 (관심사 분리)

```
순수(Analysis API 무관, 단독 테스트 가능)         Analysis API 의존(K2)
  Model.kt        출력 모델 + node-link JSON         AnalysisSession.kt  세션/PSI 워크 → IR
  Classify.kt     분류 테이블(layer/async/batch)      ConstantEvaluator.kt 참조/상수/@Value 평가
  Ir.kt           중간표현 + Resolver 인터페이스       ExternalResolver.kt  Feign/RestTemplate/WebClient/HttpExchange
  GraphBuilder.kt IR → CallGraph(엣지/배치/엔드포인트)
  OpenApi.kt      IR → OpenAPI 3.1(엔드포인트→paths, DTO→schemas)
  Bfs.kt          callers/callees BFS
  GitLog.kt       git CLI: 커밋/diff 헌크/blob (영향도)
  PsiSourceParser.kt  파일 텍스트 → (fqcn#method, 라인범위) [PSI, 해석 불필요]
  Impact.kt       변경라인→메서드→그래프 역방향 BFS 영향도
  YamlPropertyResolver.kt  application*.yml → ${...} 치환
  JsonOutput.kt / Cli.kt
```

핵심: **모든 Analysis API 사용은 `Resolver` 인터페이스 뒤로 격리**(`AnalysisSession`,
`ConstantEvaluator`, `ExternalResolver` 3파일). `GraphBuilder`/`Bfs`/`Model`/`Classify`/
`Yaml`/`Json`/`Cli`는 순수 Kotlin이라 그대로 두고, K2↔K1 전환 시 이 3파일만 바꾸면 된다.

## 의존성

`org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21` 하나면 된다(Maven Central).
`-for-ide` 아티팩트도, JetBrains 전용 저장소도 필요 없다. `snakeyaml`/`jackson`은 보조.

> **주의(shading)**: `kotlin-compiler-embeddable`는 IntelliJ 클래스를
> `org.jetbrains.kotlin.com.intellij.*` 로 relocate한다. 그래서 `PsiElement`/`Disposer`는
> `org.jetbrains.kotlin.com.intellij.*` 에서 import한다(평범한 `com.intellij.*` 아님).
> Kotlin 자체 클래스(`org.jetbrains.kotlin.psi.*`, `...resolve.*`, `...descriptors.*`)는
> relocate되지 않으므로 그대로 import.

## 버전 민감 심볼 체크리스트 (빌드 에러 시 여기부터)

K1 프론트엔드 API는 K2 Analysis API보다 안정적이지만, minor 버전 간 일부 시그니처가
바뀐다. 컴파일 에러 시 **이 3곳만** 확인하면 된다(전부 `AnalysisSession.kt`):

- `NoScopeRecordCliBindingTrace(project)` — 버전에 따라 **무인자** `NoScopeRecordCliBindingTrace()`
  일 수 있음(가장 흔한 수정 지점).
- `TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(project, files, trace,
  config, ::createPackagePartProvider)` — 오버로드 인자 수가 버전마다 다를 수 있음.
- `getResolvedCall(bc)` 패키지: `org.jetbrains.kotlin.resolve.calls.util`(신) ↔
  `...resolve.calls.callUtil`(구).
- `configureJdkClasspathRoots()` / `addKotlinSourceRoots(List<String>)` 익스텐션 패키지.

해석 핵심 심볼(안정적): `BindingContext.get(BindingContext.CLASS|FUNCTION|REFERENCE_TARGET|
COMPILE_TIME_VALUE, …)`, `KtExpression.getResolvedCall(bc).resultingDescriptor`,
`ClassDescriptor.fqNameSafe`/`.annotations`/`.typeConstructor.supertypes`,
`FunctionDescriptor.isSuspend`/`.returnType`, `DescriptorToSourceUtils.descriptorToDeclaration`,
`AnnotationDescriptor.allValueArguments` → `StringValue.value`.

## 기대 결과 (검증용)

- **sample-shop**: `ext:PaymentClient#charge`(externalService=`payment`, POST,
  endpoint=`/charge`, externalUrl=`https://pay.internal/charge`, clientPackage=`com.shop.order`);
  `OrderController#notifyOrder→sendConfirmationAsync` async(@Async); `reconcile→notify`
  async(launch); repo 상속 `save/findById`; 배치 job→step→reader/processor/writer;
  `NotificationService#notify→ext:WebClient#post`.
  → Python 출력과 diff 시 신규 키(urlPlaceholder/clientPackage) 제외하면 동일해야 함.
- **실제 프로젝트**: RestTemplate가 companion `const val PROFILE_URL = "https://..."`를 통해
  호출하면, 그 const를 추적해 외부노드 `externalUrl`에 실제 URL이 채워진다(기존 정규식
  도구는 URL이 비어 있었음). Feign은 per-method verb/endpoint + `urlPlaceholder=${...}`로
  남고, `application*.yml`에 키가 있으면 `externalUrl`이 실제값으로 치환된다(없으면
  placeholder 보존 — Spring Cloud Config 등 외부 설정이면 정상 동작).
```
