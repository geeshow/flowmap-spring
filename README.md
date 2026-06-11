# flowmap-spring-kotlin

Spring **Kotlin/Java** 프로젝트를 정적 분석해서, 메서드 사이의 **호출/피호출 흐름**을
**node-link 그래프(`nodes[] + edges[]`) JSON**으로 뽑아내는 도구입니다.
특정 API/메서드를 지정하면 **BFS**로 그 대상의 호출(downstream)·피호출(upstream)
서브그래프를 추출합니다.

분석 결과에는:

- **레이어**: Controller / Service / Repository / Component / Config / Batch / External
- **동기 · 비동기 구분**: `suspend`, `@Async`/`@Scheduled`, reactive 반환(`Mono`/`Flux`/…),
  코루틴 빌더(`launch`/`async`/`withContext`/…) 안에서의 호출
- **컨트롤러 엔드포인트**: HTTP 메서드 + 전체 URL 경로(클래스 `@RequestMapping` + 메서드 매핑)
- **스프링 배치 관계**: Job → Step → Reader / Processor / Writer / Tasklet
- **외부 API 호출**: `@FeignClient` / `@HttpExchange` / `RestTemplate` / `WebClient`,
  그리고 대상 **URL · HTTP 메서드 · 외부 서비스(패키지 포함)**
- **코드 위치**: 노드의 선언 위치(`file:line`), 엣지의 호출 지점(`callSiteFile:callSiteLine`)

이 정보로 컨트롤러→서비스→리포지토리/외부 같은 **호출 경로**를 그래프 탐색으로 복원할 수 있습니다.

---

## 예시 다이어그램 (`.repo/sample-shop`)

아래는 번들된 데모 프로젝트를 실제로 분석한 결과입니다(`analyze --project sample-shop`).
색 = 레이어, **점선 = 비동기(async)** 호출, `external` 라벨 = 외부 API 호출.

```mermaid
flowchart LR
  OCp["OrderController.placeOrder<br/>POST /orders"]:::ctrl
  OCn["OrderController.notifyOrder<br/>POST /orders/{id}/notify"]:::ctrl
  OSp["OrderService.placeOrder"]:::svc
  OSa["OrderService.sendConfirmationAsync<br/>@Async"]:::svc
  OSr["OrderService.reconcile<br/>suspend"]:::svc
  NS["NotificationService.notify"]:::svc
  ORs["OrderRepository.save"]:::repo
  ORf["OrderRepository.findById"]:::repo
  PAY["PaymentClient.charge<br/>POST https://pay.internal/charge"]:::ext
  WC["WebClient.post"]:::ext

  OCp -->|sync| OSp
  OCn -. async .-> OSa
  OSp -->|sync| ORs
  OSp -->|sync · external| PAY
  OSa -->|sync| ORf
  OSa -->|sync| NS
  OSr -->|sync| ORf
  OSr -. async .-> NS
  NS -->|sync · external| WC

  classDef ctrl fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef svc  fill:#dcfce7,stroke:#166534,color:#14532d;
  classDef repo fill:#fef9c3,stroke:#854d0e,color:#713f12;
  classDef ext  fill:#fee2e2,stroke:#991b1b,color:#7f1d1d;
```

읽는 포인트: `notifyOrder→sendConfirmationAsync`는 `@Async`라 **비동기(점선)**, `reconcile→notify`는
`launch{}` 안이라 비동기이지만 같은 `notify`를 일반 호출하는 `sendConfirmationAsync→notify`는 동기.
`PaymentClient`(`@FeignClient`)는 외부 URL까지 해석되고, `WebClient.post`는 런타임 URL이라 미해석.

스프링 배치 와이어링(`@Configuration @EnableBatchProcessing`)도 별도 엣지로 표현됩니다:

```mermaid
flowchart LR
  JOB["settlementJob<br/>(Job)"]:::batch
  STEP["settlementStep<br/>(Step)"]:::batch
  RDR["settlementReader<br/>(ItemReader)"]:::batch
  PRC["settlementProcessor<br/>(ItemProcessor)"]:::batch
  WRT["settlementWriter<br/>(ItemWriter)"]:::batch

  JOB -->|batch:step| STEP
  STEP -->|batch:reader| RDR
  STEP -->|batch:processor| PRC
  STEP -->|batch:writer| WRT

  classDef batch fill:#ede9fe,stroke:#5b21b6,color:#4c1d95;
```

> 특정 API만 보고 싶으면 `search`로 해당 메서드의 BFS 서브그래프를 뽑아 같은 식으로 그릴 수 있습니다.

---

## 두 가지 구현

같은 node-link JSON 계약을 만족하는 두 구현이 있습니다. 목적에 따라 고르세요.

| | [`callgraph/`](callgraph/) — **Python** | [`kotlin-analyzer/`](kotlin-analyzer/) — **Kotlin** |
|---|---|---|
| 방식 | 정규식 + 중괄호 추적 휴리스틱 | 컴파일러 프론트엔드(PSI + BindingContext) 의미 분석 |
| 의존성 | 없음(순수 stdlib), 오프라인 즉시 실행 | `kotlin-compiler-embeddable`(Maven Central) |
| 강점 | 빠르고 설치 불필요, 부분 소스에서도 동작 | **상수/`object`/`@Value`/`${...}` 참조를 따라가 외부 API 실제 URL까지 정확히 해석** |
| 호출 해석 | 타입 휴리스틱(DI 필드/지역변수) | 심볼 해석(오버로드·확장함수·암시적 receiver까지 정확) |

> 빠른 개요/그래프 형태만 보려면 Python, **외부 API URL을 정확히** 뽑으려면 Kotlin을 쓰세요.

---

## 빠른 시작

### Python (`callgraph/`)

```bash
python3 -m callgraph analyze --repo .repo --out graph.json
python3 -m callgraph search --graph graph.json --method placeOrder --direction both --depth 3
python3 -m callgraph stats  --graph graph.json
```

### Kotlin (`kotlin-analyzer/`)

```bash
cd kotlin-analyzer
./gradlew build
./gradlew run --args="analyze --repo ../.repo --project sample-shop --out /tmp/shop.json"
./gradlew run --args="search --method placeOrder --graph /tmp/shop.json --direction both"
```

자세한 옵션/스키마/설계는 각 디렉토리의 README를 보세요:
[`callgraph/README.md`](callgraph/README.md) · [`kotlin-analyzer/README.md`](kotlin-analyzer/README.md).

---

## 분석 대상 레이아웃

분석할 프로젝트는 `.repo/` 아래에 둡니다:

```
.repo/<프로젝트명>/<모듈명>/.../*.kt | *.java
```

각 노드에는 어느 `project`/`module`에서 왔는지 함께 기록됩니다.

이 저장소에는 데모용 샘플 프로젝트 **`.repo/sample-shop/`** 만 포함되어 있습니다
(controller→service→repository, 동기/비동기, `@FeignClient`/`WebClient`, 스프링 배치를
모두 포함). 분석하려는 실제 프로젝트는 `.repo/<your-project>/`에 직접 넣으면 됩니다.
`.gitignore`가 `.repo/sample-shop` 외의 `.repo/*`와 `graph.json`(분석 산출물)을
**커밋에서 제외**합니다 — 사내/외부 소스가 실수로 공개되지 않도록.

---

## 출력 스키마 (node-link)

```jsonc
{
  "directed": true, "multigraph": true, "meta": { ... },
  "nodes": [
    { "id": "com.shop.order.OrderService#placeOrder",
      "fqcn": "com.shop.order.OrderService", "method": "placeOrder",
      "layer": "SERVICE", "visibility": "public", "async": false,
      "returnType": "Order",
      "httpMethod": null, "endpoint": null,         // 컨트롤러/외부 노드에서 채워짐
      "externalService": null, "externalUrl": null, // 외부 노드에서 채워짐
      "file": "sample-shop/order-api/.../OrderService.kt", "line": 14,
      "project": "sample-shop", "module": "order-api" }
  ],
  "edges": [
    { "source": "...#placeOrder", "target": "...#save",
      "mode": "sync",        // sync | async
      "kind": "internal",    // internal | external | batch
      "relation": "call",    // call | batch:step | batch:reader | batch:processor | ...
      "callSiteFile": "...", "callSiteLine": 16 }
  ]
}
```

Kotlin 구현은 외부 노드에 `externalService`/`externalUrl`/`httpMethod`/`endpoint`와
추가 키 `urlPlaceholder`(원본 `${...}`)·`clientPackage`(외부 클라이언트 패키지)를 채웁니다.

---

## 디렉토리 구조

```
flowmap-spring-kotlin/
├── callgraph/              # Python 구현 (순수 stdlib)
├── kotlin-analyzer/        # Kotlin 구현 (kotlin-compiler-embeddable, K1 프론트엔드)
├── .repo/sample-shop/      # 데모용 샘플 Spring Kotlin 프로젝트
├── *.kt, schema.sql        # 초기 ASM 바이트코드 기반 버전(레거시)
└── README-legacy-kotlin-asm.md
```

## 라이선스

내부 학습/도구용. (필요 시 라이선스 추가)
