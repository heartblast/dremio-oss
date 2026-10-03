# Polaris RESTCATALOG 진행 현황

- Branch: `feature/polaris-restcatalog` (base: `799ccbda4 Release 26.0.5`)
- 작업 정의: [`docs/reference_docs/catalog-support/polaris-catalog-support.md`](../reference_docs/catalog-support/polaris-catalog-support.md)
- 상태 값: `PASS` / `FAIL` / `ENVIRONMENT_BLOCKED` / `NOT_SUPPORTED`. 진행 중인 항목은 `IN_PROGRESS`, 시작하지 않은 항목은 `NOT_STARTED`로 표시한다.
- 최종 갱신: 2026-10-03 (Phase 4 Integration + review 반영)

## Phase 현황

| Phase | 내용 | 상태 | 주요 산출물 | Commit SHA |
|---|---|---|---|---|
| 1 | 구조 분석 / Gap Analysis | PASS | [phase1-analysis.md](phase1-analysis.md), [compatibility-matrix.md](compatibility-matrix.md), progress.md | `67cf10e4c` |
| 2 | RESTCATALOG OSS Source 완성 | PASS | `@SourceType`, `isUsingVendedCredentials`(tag 13), `restcatalog-layout.json`, Polaris preset, registration/round-trip test, live gate (아래 §Phase 2) | `f2553db4a` (후속 CI/log fix `ffd1cc1b2`) |
| 3 | Polaris OAuth2 / Catalog 연동 | PASS | G-09/G-10/G-11/G-06 해결, 403/401 매핑, allowedNamespaces folder bug 수정, E2E harness `scripts/polaris-e2e`, [phase3-oauth-catalog.md](phase3-oauth-catalog.md), [security.md](security.md) | `3b85d3619` |
| 4 | Object Storage | PASS | 공식 static baseline MinIO PASS (AWS는 ENVIRONMENT_BLOCKED), MinIO matrix, provider 생략/endpoint scheme 수정, vended credential runtime (G-04), HttpClient 4·Netty logger 고정, review 13건 반영, [storage.md](storage.md), [security.md](security.md) §7 | TBD |
| 5 | 실제 E2E / Regression | NOT_STARTED | Dremio → Polaris → MinIO E2E, lifecycle, read/write, view, 장애 시나리오 | TBD |
| 6 | 최종 통합 / 문서 / Release Readiness | NOT_STARTED | `design.md`, `configuration.md`, `storage.md`, `security.md`, `test-results.md`, `known-limitations.md` | TBD |

## Phase 1 — 공통 완료 Gate

```text
[x] 병렬 Agent 작업 완료        (A Backend, B Source 등록, C 공식 설정, D UI, E Polaris; 각각 Verifier 재검증)
[x] Integration 완료            (Verifier 정정 반영, Gap G-01..G-28 통합)
[x] 신규 테스트 통과            (N/A: 분석 Phase, repo 코드 변경 없음)
[x] 관련 regression 통과        (N/A: repo 코드 변경 없음)
[x] secret/log 검토             (문서의 secret은 placeholder 또는 로컬 테스트 전용 값으로 명시)
[x] git diff/status 검토        (Lead)
[x] 문서 갱신                   (docs/polaris/ 3개 파일)
[x] commit 완료                 (67cf10e4c)
[x] origin push 완료            (origin/feature/polaris-restcatalog)
[x] commit SHA 기록             (67cf10e4c)
```

Phase 1 결과:
- 분석: **PASS**
- Live probe: Polaris 1.1.0 client-probe와 Dremio tarball prototype 모두 **PASS**
- Dremio 내부 data path(S3/DremioFileIO) E2E: **TBD (Phase 4/5)**

## Phase 2 — RESTCATALOG OSS Source 완성

### 공통 완료 Gate

```text
[x] 병렬 Agent 작업 완료        (A Backend 등록, B Config/API, C UI, D Test)
[x] Integration 완료            (contract 일치 확인, checkstyle/errorprone 지적 2건 수정)
[x] 신규 테스트 통과            (Phase 2 test 4개 class: 89 tests, 0 failures, 리뷰 수정 반영 후)
[x] 관련 regression 통과        (icebergcatalog module 전체: 182 tests, UI spec 103 passing, 0 failures)
[x] AbleOps Local CI             (`localci run --profile full`: test/lint/static/ui-source-specs 모두 SUCCESS)
[x] secret/log 검토             (live server.log/server.out/json log grep 0건, 문서는 placeholder만 사용)
[x] git diff/status 검토        (Integration Agent)
[x] 문서 갱신                   (progress.md, compatibility-matrix.md)
[x] commit 완료                 (Lead)
[x] origin push 완료            (Lead)
[x] commit SHA 기록             (Phase 현황 표 참고)
```

### AbleOps Local CI

파이프라인은 저장소 루트 [`.localci.yaml`](../../.localci.yaml)에 있다 (profile `fast`: test+lint, `full`: +static, ui-source-specs).

| Step | 내용 | 결과 |
|---|---|---|
| `icebergcatalog-test` | `plugins/icebergcatalog` compile + 전체 test (JDK 11) | SUCCESS (182 tests, 0 failures) |
| `icebergcatalog-lint` | spotless, license, checkstyle | SUCCESS |
| `icebergcatalog-static` | `verify -DskipTests`: errorprone, forbiddenapis, enforcer | SUCCESS |
| `ui-source-specs` | source form Mocha spec 6개 + eslint + prettier (Node 22) | SUCCESS (103 passing, 9 pending) |

- 원격 제출(`localci submit`)은 서버 큐에 들어가지만 Agent에 할당되지 않아 `QUEUED`에 머물렀다 (run `7213d373`, 취소). AbleOps CI 서버 쪽 이슈로 보고, 같은 파이프라인을 `localci run`(로컬 실행)으로 검증했다.
- `localci` 압축본은 32 MiB / 10000 entry 제한이 있고 ignore된 `target/` 디렉터리까지 포함하므로 `.localci.yaml`의 `archive_exclude`로 제외한다.
- CI 실행 중 eslint가 `AddSourceModal.jsx`의 기존 `promise/no-return-wrap` 오류를 잡아 `return "done"`으로 수정했다 (동작 동일).

### Gate 세부 (phase1-analysis.md §7.4)

검증 환경:
- `scripts/dev build distribution/server`로 만든 tarball을 scratchpad에 복사해 실행했다. JDK 17, 단일 노드(coordinator+executor), 사용자 `dremio`.
- Polaris `apache/polaris:1.1.0-incubating` (`p2-polaris`, `--network host`, 8181/8182).
  - S3 storage는 로컬 MinIO(`http://127.0.0.1:9000`, bucket `dremiodev`, prefix `s3://dremiodev/polaris-p2/`)를 사용했다.
  - catalog `p2cat`에 `polaris.config.drop-with-purge.enabled=true`를 설정했다.
  - catalog role `p2_admin`에 `CATALOG_MANAGE_CONTENT`를 부여하고 `service_admin`에 연결했다.
- Source JSON은 phase1-analysis.md §5.3 (5)의 형태다 (`warehouse=p2cat`, `scope=PRINCIPAL_ROLE:ALL`, MinIO `fs.s3a.*`/`dremio.*`, secret `credential`/`fs.s3a.access.key`/`fs.s3a.secret.key`, `isUsingVendedCredentials=false`).

| # | 항목 | 결과 (HTTP) | Status |
|---|---|---|---|
| 1 | `GET /api/v3/source/type`에 RESTCATALOG 존재 | 200, `{"sourceType":"RESTCATALOG","label":"Iceberg REST Catalog"}` | PASS |
| 2 | `GET /api/v3/source/type/RESTCATALOG`에 uiConfig 포함 | 200. `uiConfig.sourceType=RESTCATALOG`, `metadataRefresh={datasetDiscovery:true, authorization:false}`, tab `General`/`Advanced Options`, element 9개 (`isUsingVendedCredentials` 포함) | PASS |
| 2b | `GET /apiv2/source/type/RESTCATALOG` | 404. 26.x에서 source type API는 `/api/v3`에만 있다 (UI도 v3를 사용) | NOT_SUPPORTED (해당 endpoint 없음) |
| 3 | 공식 문서 JSON으로 `POST /api/v3/catalog` | 200, state `good` | PASS |
| 4 | GET 응답 masking (`GET /api/v3/catalog/{id}`, `GET /apiv2/source/polaris`) | 둘 다 200. secret 3개 모두 `$DREMIO_EXISTING_VALUE$`. 응답에서 secret 원문 0건 | PASS |
| 5 | masked 값 그대로 `PUT /api/v3/catalog/{id}` | 200, state `good`. 이후 namespace 조회도 정상 → credential 유지 | PASS |
| 6 | Polaris(MinIO) 대상 state `good` | `good` | PASS |
| 7 | Polaris에서 만든 namespace `p2ns`가 Dremio에 보임 | `GET /api/v3/catalog/by-path/polaris` → 200, children `[["polaris","p2ns"]]` | PASS |
| 8 | `restEndpointUri` 누락 | 400 `config.restEndpointUri` validation error (`@NotBlank`) | PASS |
| 9 | 잘못된 credential / warehouse 누락 | 둘 다 400 `Could not connect to <name>, check your connection information and credentials`. server.log에는 hint가 남는다: `(HTTP 400) … 'warehouse' … 'scope' … 'credential'. Details: Malformed request: unauthorized_client` / `… Please specify a warehouse`. 응답과 로그에 secret 없음 | PASS (hint가 API 응답에는 노출되지 않음 → Phase 3) |
| 10 | `isUsingVendedCredentials=true` source | 200, state `good`. INFO 로그 `requests vended credentials (header.X-Iceberg-Access-Delegation=vended-credentials)`. SELECT 성공 (static `fs.s3a.*`로 읽음) | PASS (flag). runtime 사용은 NOT_SUPPORTED (G-04, Phase 4) |
| 11 | Bonus: SQL API `CREATE TABLE polaris.p2ns.t1` / `INSERT` 2 rows / `SELECT` | 모두 `COMPLETED`. SELECT 결과 2 rows. MinIO에 parquet 1개, metadata.json 2개, avro 2개가 생성됨 | PASS (smoke, Phase 5에서 정식 검증) |
| 12 | Bonus: Dremio 재시작 후 state와 `SELECT count(*)` | `good`, `c=2`. 이후 `DROP TABLE` `COMPLETED` | PASS (smoke) |
| 13 | 로그 secret 노출 (server.log, server.out, json log, queries.json 등 log/ 전체) | MinIO secret, Polaris secret, 잘못된 secret, `Bearer`, `access_token`, vended `s3.secret-access-key`/`s3.session-token`: 모두 0건 | PASS |
| 14 | §7.1 D test (`TestRestCatalogSourceRegistration` 12, `TestRestCatalogLayout` 11, `TestRestIcebergCatalogPluginConfig` 28, `TestRestIcebergCatalogPlugin` 38) | 89 tests, 0 failures (리뷰 수정 후 재실행) | PASS |
| 15 | icebergcatalog module regression | 182 tests, 0 failures, 0 skipped (리뷰 수정 후 재실행) | PASS |
| 16 | dac/backend source-type test | dac/backend는 icebergcatalog에 의존하지 않아 영향받는 test가 없다 (dac/daemon만 의존). live #1, #2로 대체 | PASS (N/A) |
| 17 | UI spec (6개 spec: sourceUtils, SourceFormJsonPolicy, sourcesMapper, SelectSourceType, AddSourceModal, EditSourceView) | 103 passing, 9 pending (기존 skip block), 0 failing (리뷰 수정 후 재실행). prettier clean, eslint 신규 문제 없음 (`AddSourceModal.jsx` `promise/no-return-wrap` 1건은 HEAD부터 존재) | PASS |
| 18 | `scripts/dev fmt` / `scripts/dev lint` | clean (checkstyle `VisibilityModifier` 3건을 수정한 뒤). errorprone(`ReturnValueIgnored` 1건 수정)과 forbiddenapis도 module 단위로 clean | PASS |
| 19 | UI에서 브라우저로 source 생성 | 미실행. 새 UI bundle(preset 포함)이 서빙되는 것까지만 확인 | TBD (Phase 3) |

정리: Polaris container 삭제, Dremio 중지, `s3://dremiodev/polaris-p2/` 아래 생성 object 삭제.

### Phase 2에서 처리한 Gap

| Gap | 처리 |
|---|---|
| G-01, G-18 | `@SourceType(value="RESTCATALOG", label="Iceberg REST Catalog", uiConfig="restcatalog-layout.json")`. UI 상수 label도 변경 |
| G-02 | `restcatalog-layout.json` 추가 (General / Advanced Options, help text에 Polaris 필수 key와 MinIO key) |
| G-03 | `@Tag(13) isUsingVendedCredentials = false`. 25.2.0 bytes 호환 test 포함 |
| G-04 (flag) | true면 catalog props에만 `header.X-Iceberg-Access-Delegation=vended-credentials` (사용자 값 우선). runtime 사용은 Phase 4 |
| G-07 | `createCatalog`와 `getFsConfCopy()`에서 property를 fs conf로 eager copy. REST client 전용 key(`credential`, `token`, `scope`, `oauth2-server-uri`, `audience`, `resource`, `token-*`, `header.*`, `rest.auth.*`, token-exchange type key)는 복사하지 않는다 |
| G-09 (일부) | `getState()`가 오류 종류별 hint와 secret redaction을 붙인다. redaction은 긴 값부터 치환하고, 4자 미만 값은 건너뛰며, 8자 미만 값은 앞뒤가 영숫자가 아닐 때만 치환한다 (예: access key `dev`가 bucket `dremiodev`를 망가뜨리지 않음). API 응답 노출과 다른 경로의 매핑은 Phase 3 |
| G-13 | UI validator가 `propertyList`의 secret key를 막는다. 판정 규칙은 backend `isSensitivePropertyKey`와 같다 (정확한 key 목록 + `secret`/`password`/`account.key`/`private.key`/`private-key`/`sas-token` 포함, `.token`/`-token`/`_token` 접미사). Edit에서는 저장된 source에 이미 있던 key는 막지 않고 새로 추가하거나 이름을 바꾼 row만 막는다. backend는 key 이름만 WARN |
| G-14 (backend) | null 원소, 빈 이름, null 값을 건너뛴다. UI의 rename 시 값 비우기는 미완 |
| G-15 | UI preset "Apache Polaris OSS" (저장 type은 RESTCATALOG) |
| G-16 | registration, layout, round-trip, property 전달, fs conf, secret test |
| G-17 | `restEndpointUri`에 `@NotBlank` |
| (리뷰) | `isUsingVendedCredentials`에 `@NotMetadataImpacting`. 켜고 끄는 것만으로 dataset metadata가 삭제/재수집되지 않는다 (`equalsIgnoringNotMetadataImpacting` unit test) |
| G-12 (일부) | REST auth key가 Hadoop conf(`getFsConfCopy`, executor, `DatasetFileSystemCache`)에 더 이상 복사되지 않는다. at-rest 평문은 남음 |
| G-19 | `metadataRefresh?.`, safe errorHandler. `EditSourceView`의 undefined `errorMessage`도 수정 |

### Phase 3 이후로 넘기는 항목

- 잘못된 credential / warehouse 누락 시 API 응답은 일반 메시지뿐이다. hint는 server.log의 Suggested User Action에만 있다 (G-09).
- `createCatalog`의 catch는 여전히 dead code다. `checkState` 외 경로의 오류는 매핑되지 않는다 (G-09). `checkState`는 매번 새 catalog를 만든다 (G-11).
- UI: secret key 이름을 바꿀 때 값 비우기 (G-14), `location.state.selectedSourceType` 직접 진입 시 preset 미적용.
- `propertyList`에 secret key가 이미 있는 기존 source는 Edit에서 막지 않는다 (backend WARN만). 사용자가 Catalog Credentials로 옮기도록 안내하는 UI 경고는 아직 없다.
- 리뷰 수정(REST auth key 비복사, redaction 규칙, `@NotMetadataImpacting`, UI 규칙)은 unit/UI spec과 module build로 확인했다. tarball live gate는 수정 전 build로 실행했으며 재실행하지 않았다.
- source 시작 실패 시 `DatasetFileSystemCache.close`에서 NPE WARN (`this.cache` null). 기존 동작이며 기능 영향은 없다.
- Polaris 권한 관찰: `CATALOG_MANAGE_CONTENT`만 부여한 상태에서도 `loadTable` + vended header가 200과 `s3.*` credential을 반환했다. phase1-analysis.md §5.3 (4)의 "delegation 권한 없음" 설명은 Phase 4에서 다시 확인한다.
- `distribution/server/target`의 tarball에 들어간 `dremio-dac-ui` jar는 Phase 2 UI 변경 이전 build다 (`dac/ui` Maven build는 하지 않음). Live gate는 `corepack pnpm run build`로 만든 bundle을 scratchpad 복사본의 jar에만 넣어 확인했다. 배포용 tarball은 `dac/ui`를 다시 build해야 한다.

## Phase 3 — Polaris OAuth2 / Catalog 연동

상세 결과, 코드 변경, 재현 방법: [phase3-oauth-catalog.md](phase3-oauth-catalog.md). 보안: [security.md](security.md).

### 공통 완료 Gate

```text
[x] 병렬 Agent 작업 완료        (Harness, A OAuth2, B Namespace/Table, C Allowed Namespace, D 오류/보안)
[x] Integration 완료            (A/B 겹침 정리, C B-1/B-3/B-4와 D P-1/P-3 반영, live에서 발견한 folder listing 순서 bug 수정)
[x] 신규 테스트 통과            (신규/변경 test class 8개: 251 tests, 0 failures, 0 skipped; Review 반영 후)
[x] 관련 regression 통과        (icebergcatalog module 전체: 319 tests, 0 failures, 0 skipped; UI spec 103 passing)
[x] AbleOps Local CI             (`localci run --profile full --no-cache`: 5개 step 모두 SUCCESS, 아래 표)
[x] secret/log 검토             (unit echo test, 6개 instance live scan 0건 (Review 반영 INSTANCE=6 포함), `git diff` 전체에 MinIO secret 0건)
[x] git diff/status 검토        (Integration Agent)
[x] 문서 갱신                   (phase3-oauth-catalog.md, security.md 신규, progress.md, compatibility-matrix.md; phase3-security-notes.md는 security.md로 병합 후 삭제)
[x] commit 완료                 (Lead, 3b85d3619)
[x] origin push 완료            (Lead)
[x] commit SHA 기록             (3b85d3619)
```

### AbleOps Local CI

| Step | 내용 | 결과 |
|---|---|---|
| `icebergcatalog-test` | `plugins/icebergcatalog` compile + 전체 test (JDK 11) | SUCCESS (319 tests, 0 failures. Review 반영 후 재실행) |
| `icebergcatalog-lint` | spotless, license, checkstyle | SUCCESS |
| `icebergcatalog-static` | `verify -DskipTests`: errorprone, forbiddenapis, enforcer. Phase 3부터 source를 `touch`해 errorprone이 실제로 돈다 | SUCCESS |
| `ui-source-specs` | source form Mocha spec 6개 + eslint + prettier | SUCCESS (103 passing, 9 pending) |
| `e2e-harness-lint` (Phase 3 신규) | `scripts/polaris-e2e/*.sh` license header + shellcheck | SUCCESS |

- 원격 `localci submit`은 여전히 QUEUED 문제가 있어 local mode로 실행했다. Log는 `target/dev/localci-phase3.log`.
- Phase 2의 static step은 앞 step이 이미 compile해 둔 상태라 errorprone이 돌지 않았다. 그 사이 들어온 `TestRestIcebergCatalogPluginConfig`의 `Slf4jIllegalPassedClass` 1건을 고쳤다.

### Gate 세부 (Integration 재검증, INSTANCE=5)

| 항목 | 결과 | Status |
|---|---|---|
| Source 생성 | 200, `good` | PASS |
| 잘못된 credential / warehouse 누락 / 닿지 않는 endpoint / 잘못된 scope | 400 응답 `errorMessage`에 원인 hint와 redact된 detail (G-09) | PASS |
| 403 principal | namespace listing 403이면 `warn`. read-only는 SELECT 성공, CREATE/INSERT/CREATE FOLDER는 permission error, REST v3 folder 403 | PASS |
| Token 만료/갱신 (TTL 60초) | 6분 SELECT 12회 성공, 새 config 요청 0, 401 0 (G-11) | PASS |
| Polaris down/up | pause → unavailable → 15초 내 복구. stop → `bad` + "Unable to reach" hint. 재생성(새 key) → client 교체 후 45초 내 `good` | PASS |
| Namespace/table/view 동작 | CREATE FOLDER/TABLE, CTAS, INSERT/UPDATE/DELETE, view CREATE/SELECT/DROP | PASS |
| 비어 있지 않은 namespace DROP | "Folder [..] cannot be deleted because it is not empty…" (G-10) | PASS |
| DROP VIEW (`drop-with-purge` false) | permission error + `polaris.config.drop-with-purge.enabled` hint (G-06) | PASS |
| allowedNamespaces | 첫 실행 FAIL (nested subtree 삭제) → folder listing 순서 수정 후 7분간 안정 | PASS (수정 후) |
| Secret scan | 0 | PASS |
| 정리 | source 7개, Dremio, container, MinIO object 39개 삭제 | PASS |

### Review 반영 (INSTANCE=6)

Phase 3 diff review finding 11건을 모두 확인(실제 문제)하고 반영했다. 상세는 [phase3-oauth-catalog.md §9](phase3-oauth-catalog.md#9-review-반영-instance6).

- commit 403: `CommitForbiddenException`(`ForbiddenException` = Iceberg `CleanableFailure`)으로 던져 Iceberg가 거부된 commit의 manifest를 지우게 했다. 사용자에게는 같은 PERMISSION ERROR. live: 거부된 INSERT/DELETE 3건 뒤 남은 S3 object 0.
- state check: HTTP 401일 때만 client 교체(교체 시 table/view cache 비움). 5xx/429 등은 cached client 유지. root listing 403은 cache를 비우지 않는다. `ExpiringCatalogCache` close/replace race 수정.
- `dropFolder`: accessor는 typed `NamespaceNotEmptyException`, plugin이 validation error로 변환. live 메시지 동일.
- 401/403 오류 메시지, cause, listing WARN의 server message redaction. test는 secret을 echo하는 server로 검증.
- allowedNamespaces: 허용된 부모의 property를 못 읽어도 부모가 자식보다 먼저 listing된다.
- test 안정화(token refresh grace + polling), listing 403 test 결과 고정. harness `redact()` 대상 확대, `scan-secrets.sh` `.gz` 검사.
- 검증: module 319 tests 0 failures, fmt/lint clean, Local CI full SUCCESS, live INSTANCE=6 PASS(정리 완료), secret scan 0.

### Phase 3에서 처리한 Gap

| Gap | 처리 |
|---|---|
| G-05 (hint) | `warehouse`/`scope` 누락과 잘못된 값에 대한 hint가 API 응답에 나온다. Polaris 요구사항 자체는 그대로 |
| G-06 | DROP VIEW 403을 purge hint가 붙은 permission error로. 다른 403(load/create/drop/commit/CTAS staging/folder/dataset lookup)도 동작별 permission error |
| G-09 | 미시작 instance가 최근 실패 state를 반환하도록 해 source 생성/수정 API 오류에 hint가 나온다. OAuth2 error code, warehouse, 5xx/503, timeout, TLS, 분류되지 않는 HTTP 오류별 hint. `createCatalog`에 raw 예외 문자열 없음 |
| G-10 | 비어 있지 않은 namespace(400 "not empty" 또는 409)를 validation error로 (accessor는 typed `NamespaceNotEmptyException`, plugin이 변환). 없는 namespace drop은 "Folder does not exist" |
| G-11 | state check가 cached client 재사용, HTTP 401(stale session)일 때만 새 client로 교체하고 table/view cache를 비운다. `getDefaultBaseLocation` leak 제거. `ExpiringCatalogCache.replace`/`close` race 수정 |
| G-22 (매핑) | commit/CTAS staging 403을 permission error로 (unit, live INSERT/DELETE). commit 403은 Iceberg cleanup이 돌도록 `CleanableFailure`로 유지. out-of-tree LOCATION live는 Phase 5 |
| G-24 | separator와 parsing 규칙 문서화 (phase3-oauth-catalog.md §4.3) |
| G-27 (문서) | 최소 권한 principal 예시와 동작 (read-only, `CATALOG_READ_PROPERTIES`만) 문서화 |
| allowedNamespaces | nested entry의 조상 folder 포함, 부모 먼저 정렬, 중복 discovery 제거, 없는 namespace는 WARN 한 줄 |

### Phase 4 이후로 넘기는 항목

- Kernel: source update가 실패하면 `SourceMetadataManager`가 닫혀 background refresh가 멈춘다 (A). update 실패 문구 중복, REST v3 folder 오류의 409/class prefix, `DropFolderHandler`의 `CatalogFolderNotEmptyException` 미처리 (plugin이 validation error로 우회).
- 기본 `rest.client.*-timeout-ms` 없음 (권장값만 문서화). 정확한 HTTP status(404/409/429/502/504) 표기.
- allowedNamespaces 강제 여부(B-2), non-recursive의 빈 하위 folder(B-5). 공백이 든 namespace(O-1, upstream).
- `SHOW TABLES`가 harness SQL API 경로에서 0 rows (`sys` 포함, plugin 문제 아님).
- `distribution/server/target`의 압축 해제 디렉터리는 rebuild 후에도 `conf/`가 갱신되지 않을 수 있다. live 검증은 tarball을 새로 풀어서 한다. tarball의 `dremio-dac-ui` jar는 여전히 Phase 2 이전 UI (UI 브라우저 생성 미실행).
- G-04 vended credential runtime, G-08 storage matrix (Phase 4). out-of-tree LOCATION live, ALTER/OPTIMIZE, multi-node (Phase 5).

### Phase 3 — AbleOps Local CI 원격 검증

`localci submit --profile full --no-cache` (run `16c8597a`, 원격 Agent의 새 작업 공간, 대기 87 ms, 104 s):

| 구분 | Step | 결과 |
|---|---|---|
| 컴파일 + 테스트 | `icebergcatalog-test` | SUCCESS (319 tests, 0 failures, 0 skipped) |
| lint | `icebergcatalog-lint` | SUCCESS |
| 후속: 정적 분석 | `icebergcatalog-static` (errorprone, forbiddenapis, enforcer) | SUCCESS |
| 후속: UI | `ui-source-specs` | SUCCESS (103 passing, eslint 0 errors) |
| 후속: harness | `e2e-harness-lint` (license header, shellcheck) | SUCCESS |

## Phase 4 — Object Storage

상세 설정, 결과, troubleshooting: [storage.md](storage.md). 보안: [security.md](security.md) §7.

### 공통 완료 Gate

```text
[x] 병렬 Agent 작업 완료        (A Polaris + S3 기본 경로, B S3-compatible/MinIO, C Vended Credentials, D 보안)
[x] Integration 완료            (A P-A1 + B P2 → provider 기본 chain 교체, B P1 endpoint scheme, D logback patch 반영; B test의 forbiddenapis setAccessible 제거)
[x] Review 반영                 (finding 13건 모두 code로 확인 후 반영. 아래 §Review 반영)
[x] 신규 테스트 통과            (icebergcatalog 신규 test class 4개 62 tests + 변경된 `TestS3FileSystem` 25 tests(신규 2), 0 failures)
[x] 관련 regression 통과        (icebergcatalog module 381 tests, plugins/s3 module 112 tests, 0 failures)
[x] AbleOps Local CI             (`localci run --profile full --no-cache`: 6개 step 모두 SUCCESS (review로 `s3-test` 추가), 아래 표. 원격 submit은 Lead)
[x] secret/log 검토             (D live matrix 0건(KV 평문 G-12 제외), Integration INSTANCE=5와 review INSTANCE=6 scan 0건, `git diff`와 신규 파일에 MinIO secret 0건)
[x] git diff/status 검토        (Integration Agent, review 반영 Agent)
[x] 문서 갱신                   (storage.md 신규, security.md §1–§3·§6·§7, progress.md, compatibility-matrix.md)
[ ] commit 완료                 (Lead)
[ ] origin push 완료            (Lead)
[ ] commit SHA 기록             (Phase 현황 표, TBD)
```

### AbleOps Local CI

`localci run --profile full --no-cache` (local mode, review 반영 최종 code, log `target/dev/localci-phase4.log`, wall 2분 26초). 원격 `localci submit` 결과와 SHA는 Lead가 기록한다.

| 구분 | Step | 결과 |
|---|---|---|
| 컴파일 + 테스트 | `icebergcatalog-test` | SUCCESS (381 tests, 0 failures, 0 skipped) |
| 컴파일 + 테스트 | `s3-test` (review 신규) | SUCCESS (112 tests, 0 failures, 0 skipped) |
| lint | `icebergcatalog-lint` (spotless, license, checkstyle; review부터 `plugins/s3` 포함) | SUCCESS (0 violations) |
| 후속: 정적 분석 | `icebergcatalog-static` (errorprone, forbiddenapis, enforcer; review부터 `plugins/s3` 포함, `s3-test` 뒤) | SUCCESS (forbiddenapis s3/icebergcatalog main/test 0 errors) |
| 후속: UI | `ui-source-specs` | SUCCESS (103 passing, 9 pending) |
| 후속: harness | `e2e-harness-lint` (license header, shellcheck) | SUCCESS |
| 원격 | `localci submit --profile full --no-cache` | Lead 실행 예정 |

- Review 이전에는 `plugins/s3/src`가 archive에서 빠지고 어떤 step도 `plugins/s3`를 build하지 않았다 (Integration이 따로 확인). Review 반영으로 `.localci.yaml`이 `plugins/s3/src`를 포함하고 `s3-test` step(fast, full)과 lint/static의 `plugins/s3`를 추가했다. `plugins/icebergcatalog`의 test-scope `dremio-s3-plugin` 의존성은 여전히 `~/.m2`에서 resolve하지만, icebergcatalog test는 변경된 `S3FileSystem.getEndpoint()`를 쓰지 않는다.
- C 시점에 `icebergcatalog-static`을 막던 `TestRestCatalogS3CompatibleProps`의 `setAccessible`(forbiddenapis)은 reflection 없이 같은 규칙을 test 안에서 계산하도록 바꿨다.

### Gate 세부

| # | 항목 | 결과 | Status |
|---|---|---|---|
| 1 | 공식 baseline (`isUsingVendedCredentials=false`, `warehouse`, `scope`, `SimpleAWSCredentialsProvider`, secret `credential`/S3 key 2개) + MinIO endpoint 설정, 전체 read/write cycle | A INSTANCE=1, Integration INSTANCE=5 `base`: CREATE/INSERT/CTAS/INSERT…SELECT/UPDATE/DELETE/metadata table/`AT SNAPSHOT`/재시작 | **PASS** (MinIO) |
| 2 | 공식 baseline on AWS S3 | AWS 계정 없음 | ENVIRONMENT_BLOCKED |
| 3 | MinIO 최소 recipe, key별 필요 여부 | endpoint·compat·ssl(HTTP) 필수, path-style은 hostname이면 필수, 나머지 불필요 | PASS |
| 4 | provider 생략 | 수정 전 FAIL → 수정 후 PASS (unit, live `noprov`). Review: Hadoop 기본 chain을 빈 값 대신 `SimpleAWSCredentialsProvider`로 바꿔 key 없는 source는 fail closed (live `snp`) | PASS |
| 5 | endpoint scheme | 수정 전 FAIL (G-08) → 수정 후 PASS (unit, live `scheme`/`scheme2`, B `https://`) | PASS |
| 6 | SSL/TLS, region, path-style, requester-pays, bucket discovery | B matrix ([storage.md §14.2](storage.md#142-agent-b--s3-compatible--minio-instance2-user-minio--throwaway-tlsregion-minio)). custom region 이름은 NOT_SUPPORTED, AWS 전용 항목은 ENVIRONMENT_BLOCKED | PASS (MinIO) |
| 7 | Vended credentials (Polaris OSS, generic 구현) | C INSTANCE=3 + Integration `vn` + review INSTANCE=6 (`vn`/`vk`/`vbp`): static key 없이 read/write/DML, 만료 갱신. static key도 있는 source는 기본 location 밖 `LOCATION`도 PASS. vended만 있는 source의 기본 location 밖 `LOCATION` NOT_SUPPORTED | PASS (compatibility 결과) |
| 8 | Secret 노출 | D live matrix 0건 (KV 평문 G-12, `docker inspect` harness env 기록), HttpClient 4 header logger·Netty `LoggingHandler`(review) patch 적용, Integration/review scan 0 | PASS |
| 9 | 정리 | A–D, Integration, review 모두 container(`p4-*`), Dremio instance, MinIO prefix(Integration 86, review 94 object) 삭제. 남은 `p4-` container 0 | PASS |

### Review 반영 (INSTANCE=6)

Phase 4 diff review finding 13건을 하나씩 code로 확인한 뒤 반영했다. Live 재검증 결과는 [storage.md §14.5](storage.md#145-review-반영-재검증-instance6)에 있다.

| # | Finding | 확인 | 처리 |
|---|---|---|---|
| 1 | `vk`(vended + static key)의 새 table이 staged create credential(기본 location 전용)을 써서 기본 location 밖 `LOCATION`이 AccessDenied (Phase 4 이전에는 성공) | 실제 문제 | `loadTableStorageProperties(table, stageNewTable)`: 자기 S3 credential이 있는 source는 staged create를 하지 않고 static key로 쓴다. CREATE/CTAS/DROP TABLE 시 이 node의 table credential과 FS를 비운다. Live `vk`: `ns1/custom/*` CTAS·CREATE+INSERT, vended cache된 table DROP 후 다른 location으로 CTAS PASS |
| 2 | AWS에서 bucket discovery 기본값(true)이면 vended credential로 FS 생성 실패 (`ListAllMyBuckets` 없음) | code로 확인 (MinIO는 filtering이라 live로 재현 불가) | `applyTo`가 `dremio.bucket.discovery.enabled=false`를 설정. 처음 보는 bucket의 root listing 확인도 함께 빠진다. unit, live `vbp`(discovery 기본값) PASS |
| 3 | bucket별 key/provider가 vended credential을 덮음 | 실제 문제 (`FileSystemConfUtil.updateSourcePropsFromBucketProps`) | `applyTo`가 `fs.s3a.bucket.*.access.key`/`.secret.key`/`.session.token`/`.aws.credentials.provider`를 지운다. unit, live `vbp`(bucket별 provider Simple) PASS |
| 4 | source의 `s3.*` property가 vended credential로 오인됨 | 실제 문제 (FileIO property = catalog property + table config) | plugin이 source property와 같은 key/값을 뺀다 (`withoutSourceProperties`). unit |
| 5 | 만료 시각이 지난 FS entry 수명 0 → 매 조회 새 FS, lock 실패. wall clock step 후 만료된 credential 반환 | 실제 문제 | FS entry 최소 수명 1초. `VendedCredentialsCache.get`이 wall clock 기준으로 지난 결과를 다시 조회. unit (fake ticker/clock) |
| 6 | 200 ms 만료 test가 timing에 민감 | 실제 문제 | `DatasetFileSystemCache`에 ticker/clock 주입, test는 fake clock. 3회 연속 PASS |
| 7 | Local CI가 `plugins/s3`를 build/test하지 않음 | 실제 문제 | `plugins/s3/src` archive 포함, `s3-test` step, lint/static에 `plugins/s3` 추가 |
| 8 | Netty `LoggingHandler`(SDK v2 async 읽기)가 DEBUG에서 session token을 찍음 | 실제 문제 (bytecode, unit에서 고정을 빼면 session token 노출 확인) | `logback.xml`과 test `LogCapture`에 `io.netty.handler.logging.LoggingHandler`, `io.netty.handler.codec.http2.Http2FrameLogger` INFO 고정. test가 async 읽기와 `io.netty` DEBUG를 포함. security.md §3 문구 정정 |
| 9 | key 없는 source가 Dremio host identity(`AWS_*` env, instance profile)로 fallback | 실제 문제. 추가 발견: finding이 제안한 "chain 유지"도 fail closed가 아니다 (S3A가 chain의 env/instance provider를 시도, live 1차 `snp`) | Hadoop 기본 chain을 `SimpleAWSCredentialsProvider`로 교체 → key 없으면 두 client 모두 실패. host identity는 provider를 빈 값/명시할 때만 (opt-in). security.md §6·§7.4, storage.md §10·§12.4, compatibility-matrix 정정 |
| 10 | storage.md의 "vended source는 table마다 provider를 명시" 문구와 bucket별 설정 권장이 틀림 | 실제 문제 | #3 code 수정 + storage.md §4·§10·§12.4 정정 |
| 11 | security.md의 test 근거 과장 (`toString`/오류는 2개 test만, 102 event는 S3A+SDK v1 합계) | 실제 문제 | security.md §3, §7.1 정정, test class Javadoc도 정정 |
| 12 | storage.md가 `rest.*` 전체를 Hadoop conf에서 뺀다고 기술 (실제는 `rest.auth.*`/`rest.client.*`만) | 실제 문제 | 문서 정정 + `rest.access-key-id`/`rest.secret-access-key`/`rest.session-token`을 REST client 전용 key에 추가 (unit) |
| 13 | harness가 `AWS_ACCESS_KEY_ID`를 Dremio process에 남김 | 실제 문제 | `dremio-up.sh`가 `AWS_ACCESS_KEY_ID`/`AWS_ACCESS_KEY`/`AWS_SECRET_KEY`/`AWS_SESSION_TOKEN`/`AWS_PROFILE`/`S3_ACCESS_KEY`도 제거. live: 넘겨도 Dremio process env 0개 |

### Phase 4에서 처리한 Gap

| Gap | 처리 |
|---|---|
| G-04 | Vended credential runtime: table별 S3 FileSystem에 vended `s3.*` credential 적용, node별 cache와 만료 전 갱신 (C). Credential 외 설정은 source 값 |
| G-08 | MinIO 설정 실측. endpoint scheme은 `S3FileSystem.getEndpoint()` 수정으로 해결, requester-pays 기본값은 MinIO에서 무해 (변경 없음), 필수 key 확정 |
| G-28 | Dremio data path(DremioFileIO, S3, CTAS commit, DML)를 실제 Polaris + MinIO로 실행 (Phase 5에서 정식 E2E) |
| (신규) provider | 공식 문서/compatibility-matrix의 "provider가 비면 자동으로 채운다"는 틀렸다. Hadoop 기본 chain을 `SimpleAWSCredentialsProvider`로 바꿔 access key로 동작하게 했다. key가 없으면 host identity로 넘어가지 않고 실패한다 (review) |
| G-12 (일부) | 완화책 `dremio-admin encrypt` live 확인. 기본 동작은 그대로 |
| (Phase 2 known) | `DatasetFileSystemCache.close` NPE WARN 수정 (C) |

### Phase 5 이후로 넘기는 항목

- AWS S3 전부 (static baseline, requester-pays bucket, `ListAllMyBuckets` 없는 IAM user, instance profile, assumed role, AWS vending): ENVIRONMENT_BLOCKED.
- Multi-node executor (static, vended).
- Commit 시 403 외 `RESTException`(Polaris storage credential 오류 등)을 redact된 `UserException`으로 매핑 (A P-A3).
- AWS 외 region 이름 허용(B P3), non-AWS endpoint + compat off WARN(B P4), harness truststore mount(B P5 나머지).
- Known limitations: source state check가 storage를 검사하지 않음, Dremio 쪽 S3 오류로 실패한 CREATE TABLE이 Polaris에 남음, vended만 있는 source의 기본 location 밖 `LOCATION`, read-only vended INSERT의 SYSTEM 오류, 잘못된 region INSERT의 "Memory was leaked" 표시, logback hot reload 시 turboFilter 경고(D 관찰), vended cache 무효화는 DDL을 실행한 node만.
- Review 후속 후보: vended source(static key 없음)에서 explicit `LOCATION`을 staged create에 넘겨 그 location의 credential을 받기 (finding 1의 대안. FS cache key에 write path가 없어 범위가 크다). `AWS_*` env opt-in 경로 live 확인.

### Phase 4 — AbleOps Local CI 원격 검증

`localci submit --profile full --no-cache` (run `d2dfe26e`, 원격 Agent의 새 작업 공간, 147 s):

| 구분 | Step | 결과 |
|---|---|---|
| 컴파일 + 테스트 | `icebergcatalog-test` | SUCCESS (381 tests, 0 failures, 0 skipped) |
| 컴파일 + 테스트 | `s3-test` | SUCCESS (112 tests, 0 failures, 0 skipped) |
| lint | `icebergcatalog-lint` (icebergcatalog + s3) | SUCCESS |
| 후속: 정적 분석 | `icebergcatalog-static` (errorprone, forbiddenapis, enforcer) | SUCCESS |
| 후속: UI | `ui-source-specs` | SUCCESS (103 passing, eslint 0 errors) |
| 후속: harness | `e2e-harness-lint` | SUCCESS |

## 완료 기준 현황

`[ ]`는 아직 충족하지 않았다는 뜻이다. 현재 상태와 근거를 함께 적는다.

| # | 기준 | 현재 상태 | 근거 / 다음 단계 |
|---|---|---|---|
| 1 | `[x]` Dremio OSS에서 RESTCATALOG Source 노출 | PASS | Phase 2 live gate #1, #2 (`GET /api/v3/source/type` 200) |
| 2 | `[ ]` UI Source 생성 성공 | IN_PROGRESS | layout과 preset 구현, UI spec PASS, bundle 서빙 확인. 브라우저 수동 생성은 미실행 (Phase 3) |
| 3 | `[x]` REST API Source 생성 성공 | PASS | Phase 2 live gate #3 (`POST /api/v3/catalog` 200, state `good`) |
| 4 | `[x]` Polaris OAuth2 인증 성공 | PASS | Phase 2 live. Phase 3: 잘못된 credential/scope/warehouse/endpoint hint가 API 응답에 나오고, token 만료(60초)·갱신, 401/403, Polaris 재시작 복구 확인 ([phase3-oauth-catalog.md](phase3-oauth-catalog.md)) |
| 5 | `[x]` Namespace 탐색 성공 | PASS | Phase 2 live gate #7. Phase 3: nested(3 level) 양방향, CREATE/DROP FOLDER, 비어 있지 않은 namespace 메시지 |
| 6 | `[x]` Table 탐색 성공 | PASS | Phase 3: Polaris API로 만든 table과 Dremio에서 만든 table/view가 catalog tree와 `INFORMATION_SCHEMA."TABLES"`에 보인다. Spark에서 만든 table은 Phase 5 |
| 7 | `[ ]` SELECT 성공 | TBD (Phase 5) | Phase 2 smoke, Phase 3 live PASS (Polaris/Dremio table, view, read-only principal). 정식 검증은 Phase 5 |
| 8 | `[ ]` CREATE TABLE 또는 CTAS 성공 | TBD (Phase 5) | Phase 3 live: CREATE TABLE, CTAS(3 level namespace 포함) PASS. partitioned CTAS와 정식 검증은 Phase 5 |
| 9 | `[ ]` INSERT 성공 | TBD (Phase 5) | Phase 2 smoke, Phase 3 live PASS (INSERT/UPDATE/DELETE/MERGE) |
| 10 | `[x]` Polaris + S3/MinIO 접근 성공 | PASS (MinIO) / ENVIRONMENT_BLOCKED (AWS S3) | Phase 4: 공식 static baseline + MinIO endpoint로 전체 read/write cycle (A, Integration `base`), MinIO matrix (B), vended credential (C), secret 0건 (D). [storage.md](storage.md) |
| 11 | `[ ]` Source restart/reload 성공 | TBD (Phase 5) | Phase 2 smoke: Dremio 재시작 후 `good`. Phase 3: Polaris 중지/재시작 후 자동 복구. source update 실패 후 background refresh 정지(kernel) 발견 |
| 12 | `[x]` allowedNamespaces 동작 | PASS | Phase 3: recursive/non-recursive/nested/case/separator matrix (C). nested entry의 부모 folder 삭제 bug 수정 후 7분간 names refresh 반복에서 안정 (Integration). discovery 범위만 정하고 접근 제어는 아니다 |
| 13 | `[x]` secret masking 정상 | PASS | Phase 2 live gate #4, #5, #13. Phase 3: state/오류 메시지 redaction(unit echo test), DEBUG 포함 live scan 0건. Phase 4: vended credential 포함 REST/job/profile/system table/log 노출 matrix 0건, HttpClient 4 header logger·Netty `LoggingHandler` 고정, key 없는 source fail closed ([security.md](security.md) §7). at-rest 암호화는 NOT_SUPPORTED (G-12, `dremio-admin encrypt` 완화책) |
| 14 | `[ ]` Generic RESTCATALOG 회귀 없음 | IN_PROGRESS | icebergcatalog module 381 tests, plugins/s3 112 tests PASS (Phase 4 review 반영 후). Vended 경로는 flag가 false면 동작하지 않는다 (unit). icebergcatalog module 319 tests PASS (Phase 3, Review 반영 후). 오류 매핑은 Iceberg 예외 type 기반의 generic 구현. Polaris 외 REST catalog E2E는 Phase 5 |
| 15 | `[ ]` 관련 unit/integration/E2E 검증 | IN_PROGRESS | Phase 3 신규/변경 test class 8개(251 tests), Phase 4 신규 test class 4개(62 tests) + `TestS3FileSystem` 2, E2E harness `scripts/polaris-e2e`. opt-in IT는 Phase 5 |
| 16 | `[ ]` 문서 완료 | IN_PROGRESS | Phase 1–4 문서 (Phase 3: phase3-oauth-catalog.md, security.md. Phase 4: storage.md, security.md §7). Phase 6 문서 세트 남음 |
| 17 | `[ ]` 모든 Phase commit/push 완료 | IN_PROGRESS | Phase 1 `67cf10e4c`, Phase 2 `f2553db4a` (+ `ffd1cc1b2`), Phase 3 `3b85d3619`. Phase 4 commit 대기 |

## Gap 요약 (상세: [phase1-analysis.md §4](phase1-analysis.md#4-gap-목록))

| 심각도 | Gap |
|---|---|
| blocker | ~~G-01 `@SourceType` 누락~~, ~~G-02 layout 누락~~ (Phase 2 해결) |
| high | ~~G-03~~ (해결), ~~G-04~~ (Phase 4 vended runtime), G-05 Polaris `warehouse`/`scope` 필수 (UI/문서/hint 반영, 요구사항 자체는 Polaris), ~~G-06~~ (Phase 3 오류 매핑), ~~G-07~~ (해결), ~~G-08~~ (Phase 4: scheme 수정, MinIO 실측), G-28 data path (Phase 4 live PASS, Phase 5 정식 E2E) |
| medium | ~~G-09~~, ~~G-10~~, ~~G-11~~ (Phase 3 해결), G-12 secret at-rest (Hadoop conf 복사는 해결, `dremio-admin encrypt` 완화책), ~~G-13~~, ~~G-15~~, ~~G-16~~ (해결), G-22 LOCATION 403 (매핑은 Phase 3 unit, live는 Phase 5) |
| low | G-14 (backend 해결, UI rename 미완), ~~G-17~~, ~~G-18~~, ~~G-19~~ (해결), G-20, G-21, G-23~G-27 |

## 환경 메모

- **JDK와 build**
  - Build는 JDK 21, test는 JDK 11 toolchain을 쓴다.
  - Module 단위 build와 test는 `scripts/dev`로 한다.
  - `clean`과 `-Dmaven.test.skip`은 쓰지 않는다.
- **`dac/ui`**
  - Node 의존성은 설치되어 있다 (`dac/ui/target/frontend/node`의 node 22). 시스템 node 24는 `.ts` spec을 읽지 못한다.
  - UI spec: `PATH=$PWD/target/frontend/node:$PATH DREMIO_UI_TESTS="<abs spec paths>" corepack pnpm run test:only`
  - UI bundle만 필요하면 Maven 없이 `corepack pnpm run build --output-path=<dir>/rest/dremio_static`로 만든다 (약 90초).
  - `~/.m2`의 `dremio-dac-ui` jar는 Phase 2 UI 변경 이전 build다.
- **Docker** 사용 가능.
- **사용 가능한 이미지 (로컬)**

  | 이미지 | 용도 |
  |---|---|
  | `apache/polaris:1.1.0-incubating` | 기준 버전 |
  | `apache/polaris:0.10.0-beta` | 미검증 |
  | `bitnamilegacy/minio:latest` | MinIO 대체 |
  | `chrislusf/seaweedfs` | 대안 S3-compatible storage |

- **`minio/minio` 사용 불가**: Docker Hub의 `minio/minio`는 pull할 수 없다(차단). 대신 `bitnamilegacy/minio:latest`를 쓴다.
  - 환경 변수: `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_DEFAULT_BUCKETS=warehouse`
- **Phase 2 live 환경**
  - Polaris는 `--network host`로 띄워 host의 MinIO(`http://127.0.0.1:9000`)에 접근한다. AWS_* env에 MinIO `<access-key>`/`<secret-key>`를 넣는다.
  - Dremio tarball은 scratchpad에 복사해 실행했다 (JDK 17, `conf/dremio-env`에 `JAVA_HOME`). 9047/31010/45678과 8181/8182가 비어 있어야 한다.
  - 첫 사용자: `PUT /apiv2/bootstrap/firstuser` (header `Authorization: _dremionull`).
  - MinIO 정리는 임시 `MC_CONFIG_DIR`의 `mc` alias로 한다 (`~/.mc` 사용 금지).
- **E2E harness** (Phase 3): `scripts/polaris-e2e` ([README](../../scripts/polaris-e2e/README.md)). INSTANCE(0..9)별 port, container(`p3-polaris-e2e-<i>`), work dir, MinIO prefix를 분리한다. Secret은 환경 변수로만 받는다.
- **Container 규칙**
  - 이름에 `p1-` prefix를 붙인다 (Phase N이면 `pN-`).
  - 높은 port를 쓴다.
  - 사용 후 `docker rm -f`로 제거한다. Phase 1에서 쓴 container는 모두 제거했다.
- **Polaris FILE storage 실행 flag**
  - `polaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["FILE","S3"]`
  - `polaris.features."ALLOW_INSECURE_STORAGE_TYPES"=true`
  - `polaris.readiness.ignore-severe-issues=true`
  - 명령 전체는 [phase1-analysis.md §5.3](phase1-analysis.md#53-재사용-가능한-명령) 참고.
- **로컬 테스트 전용 credential**
  - Polaris root: `root` / `s3cr3t`
  - MinIO: `minioadmin` / `minioadmin`
  - 문서와 설정 예제에서는 `<client_id>:<client_secret>`, `<s3AccessKey>`, `<s3SecretKey>` placeholder를 쓴다.
- **Probe 관련 파일** (repo 밖 scratchpad, commit 대상 아님)
  - Iceberg client probe (`Probe.java`, `Probe2.java`, `Refresh.java`)
  - Agent B prototype (`p1b/proto/P1ProtoRestCatalogConf.java`, 25.2.0 layout)
  - Prototype jar는 Phase 2의 in-tree annotation과 **동시에 classpath에 두면 안 된다**. 두 class가 같은 `RESTCATALOG` 값을 가지면 duplicate key 때문에 startup이 실패한다.
