# S2S 엔드포인트 매핑 (External → 컨트롤러 retarget)

서비스 간(Server-to-Server, S2S) HTTP 호출을 **다른 서비스의 실제 컨트롤러 엔드포인트**로
연결하는 방식을 정리한다. 핵심 목표는 **서드파티가 아닌데도 `external` 로 남는 호출**을 줄이고,
host 설정이나 base-path 차이 때문에 매핑이 깨지지 않게 하는 것이다.

대상 코드: `Cli.kt`(`stampS2S`) · `HostRegistry.kt`(host→프로젝트 해석) ·
`CrossRun.kt`(`matchProvider`, endpoint retarget) · `Model.kt`(`MethodNode.s2sService`).

관련 테스트: `CrossRunS2STest.kt`.

---

## 1. 전체 흐름 — 2단계

각 프로젝트를 `analyze` 하면 외부로 나가는 HTTP 호출(`@FeignClient`/`@HttpExchange`/imperative
client)이 **`EXTERNAL` 노드**로 만들어지고, 대상의 **verb + path** 를 담는다. 이 외부 호출을
다른 분석 대상 서비스로 연결하는 일은 두 단계로 나뉜다.

| 단계 | 위치 | 무엇을 하나 | 산출 |
|---|---|---|---|
| **① host 해석** | `Cli.stampS2S` → `HostRegistry` | 외부 호출이 **어느 서비스로** 가는지 yml host 로 추정 | `MethodNode.s2sService` stamp |
| **② endpoint 매칭** | `CrossRun.combine` → `matchProvider` | 그 호출을 **어느 엔드포인트로** retarget 할지 결정 | 엣지를 `S2S` 로 바꾸고 external stub 제거 |

> 두 단계는 독립적이다. ①이 실패(`s2sService=null`)해도 ②가 endpoint 만으로 매칭할 수 있고,
> ①이 성공하면 ②의 신호로 쓰인다.

```
analyze (프로젝트별)
  └─ EXTERNAL 노드 [verb, path, externalUrl, externalService, urlPlaceholder]
       │
       ▼  ① stampS2S (Cli.kt) — 모든 프로젝트 application*.yml host 합집합
  EXTERNAL 노드 + s2sService=<프로젝트>      ← HostRegistry.resolve
       │
       ▼  ② combine (CrossRun.kt)
  S2S 엣지 (provider 컨트롤러 노드로 retarget) ← matchProvider
```

---

## 2. ① host 해석 — `HostRegistry`

외부 호출의 host 가 `application*.yml` 에 설정돼 있으면(예: `service-url.bank-broker:
https://bank-api-stage.terafunding.com`) 그 host 를 소유한 프로젝트로 본다. 환경(dev/stage/prod…)
마다 host 가 다르므로 **모든 프로파일 yml 의 host 합집합**을 모은다.

- **config 키 매칭**(우선): `service-url.bank-broker` 의 leaf `bank-broker` 를 프로젝트명과 별칭 매칭
- **host 라벨 매칭**: host 첫 라벨 `twice-api-stage` → `twice-api` 별칭 매칭
- `alias()` 는 정규화(alnum lowercase) 후 `exact > startsWith > contains` 점수로 매칭하되,
  너무 일반적인 토큰(`api/service/client/gateway/stage/…` STOP set)과 길이 3 미만은 제외해 오탐을 막는다.
- localhost 류(`127.*`, `0.0.0.0`, `::1`, `*.local`)는 제외.

해석 결과는 `MethodNode.s2sService` 에 stamp 된다(②의 가장 강한 신호).

> **한계**: host 텍스트가 프로젝트명과 안 닮으면 해석이 실패한다. 예) host `tera-funding-stage`
> 가 실제로는 프로젝트 `tera-cloud-user` 가 서빙 → 별칭 매칭 불가 → `s2sService=null`.
> 이런 경우는 ②의 endpoint 매칭(특히 2b)이 보완한다.

---

## 3. ② endpoint 매칭 — `CrossRun.matchProvider`

provider 후보 = **다른 배포단위**의 `CONTROLLER` 엔드포인트.

> **다른 배포단위** = 다른 프로젝트, 또는 멀티모듈(services-as-modules) 한 프로젝트 안의 다른 모듈.
> 같은 모듈이 자기 자신을 HTTP 로 호출하는 self-call 은 S2S 가 아니므로 제외된다.

경로는 `normPath` 로 정규화: query 제거, `{id}`·`{userNo}` 등 path variable 을 `{}` 로 통일,
trailing slash 제거. verb 는 `verbOk` 로 비교(`null`/`ANY` 는 와일드카드).

### 매칭 단계 (위에서부터 시도)

| Tier | 조건 | 채택 |
|---|---|---|
| **1. exact** | `normPath` 완전일치 | 항상 채택 |
| **2a. suffix + 신호** | 짧은 경로가 긴 경로의 **trailing-segment suffix**(≥2 segment) **AND** 서비스 신호가 뒷받침 | 채택 |
| **2b. suffix + 유일** | suffix 매칭이 **전역에서 유일**(cross-unit provider 1개) **AND** 호출·provider verb 가 concrete | 채택 |
| (그 외) | 신호도 유일성도 없는 느슨한 겹침 | **매칭 안 함**(external 유지) |

- **suffix 매칭**(`suffixDrop`)은 base-path / `server.servlet.context-path` / Feign `path=` base /
  gateway prefix 차이를 흡수한다. 짧은 경로가 긴 경로의 끝 segment 들과 정확히 일치하고 **2개 이상**
  공유할 때만 인정하며, 더 적게 버려진(가까운) 후보를 우선한다.
- **서비스 신호** = provider 프로젝트가 `s2sService` 와 같거나(①의 결과), provider 의 프로젝트/모듈이
  호출 힌트(Feign 이름·`${...}` placeholder leaf)와 별칭 매칭됨.
- **tie-break**(`pickByHint`): 같은 점수 후보 중 `s2sService` 프로젝트 > 힌트 별칭 > cross-project > 첫 후보.

### 안전장치 — 가짜 엣지 방지

- 2b 의 **유일성**(전역에 단 하나의 cross-unit suffix 매칭)이 핵심 가드다. 둘 이상이면 모호하다고
  보고 매칭하지 않는다(잘못된 서비스로 연결 방지).
- 신호 없는 suffix 매칭은 verb 가 concrete(`null`/`ANY` 아님)일 때만 허용한다.
- 위 어느 tier 도 통과 못 하면 그대로 `EXTERNAL` 로 남는다 → **느슨한 경로 겹침만으로는 절대
  cross-service 엣지를 만들지 않는다.**

---

## 4. 예시

### (a) context-path 차이 → suffix 매칭

```
호출:    GET /internal/funding/id          (Feign → http://tera-funding-stage.../internal/funding/id)
provider: GET /funding/internal/funding/id  (tera-cloud-user, context-path/class-base "/funding")
```

`normPath` 후 caller `[internal, funding, id]` 는 provider `[funding, internal, funding, id]` 의
끝 3 segment 와 일치(버려진 prefix `/funding` 1개) → **suffix 매칭**. host 가 프로젝트명을 못 짚어
`s2sService=null` 이어도, 그 경로를 서빙하는 provider 가 유일하면 **2b** 로 연결된다.

> 단, caller 와 provider 가 **같은 배포단위**면(같은 모듈의 self HTTP call) 후보에서 제외 → S2S 아님(정상).

### (b) path-variable vs literal → 매칭 안 함(정상)

```
호출:    GET /system/v1/codes/{}     (한 엔드포인트가 모든 코드 조회)
provider: GET /system/v1/codes/bank, /system/v1/codes/by-cd/{}, …  (서버는 코드별 리터럴 분기)
```

1:1 대응하는 provider 가 없으므로 매칭하지 않고 external 로 둔다.

### (c) 진짜 서드파티 → external 유지

`slack.com`, `api.email-vendor.com`, `pay.internal` 등 분석 대상에 없는 host 는 provider 후보가
없어 그대로 `external`.

---

## 5. 테스트 (`CrossRunS2STest`)

| 케이스 | 기대 |
|---|---|
| exact 경로 일치 | S2S retarget, external stub 제거 |
| context-path suffix + `s2sService` 뒷받침 | S2S (2a) |
| 신호 없는 **유일** suffix | S2S (2b) — host 가 owner 를 못 짚어도 연결 |
| 신호 없는 **모호한** suffix(2개 프로젝트) | external 유지 |
| Feign 이름 힌트가 suffix 뒷받침 | S2S (2a) |
| 1 segment 만 공유 | 너무 약해 매칭 안 함 |
| verb mismatch (POST vs GET) | 매칭 안 함 |
| 같은 배포단위(self-call) | S2S 아님 |
| 동일 경로 provider 여럿 + `s2sService` | yml 해석 대상이 tie 우선 |

---

## 6. 파이프라인에서 재생성

분석기 코드만 고쳤다면 데이터에 반영하려면 재실행이 필요하다(데이터는 `.gitignore` 산출물).

```sh
# 백엔드만
./scripts/02-analyze.sh    # 프로젝트별 그래프 + s2sService stamp (installDist 자동 재빌드)
./scripts/03-merge.sh      # CrossRun.combine → json/_combined.json

# 웹 데이터까지 (flowmap5 루트에서)
../sh/run-all.sh 12 14     # sync(docs/web/data 취합) + verify(연결성 검증)
```

`03-merge` 로그의 `… N s2s …` 와 `14-verify` 의 S2S/조인 통계로 효과를 확인한다.
