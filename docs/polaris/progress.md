# Polaris RESTCATALOG 진행 현황

- Branch: `feature/polaris-restcatalog` (base: `799ccbda4 Release 26.0.5`)
- 작업 정의: [`docs/reference_docs/catalog-support/polaris-catalog-support.md`](../reference_docs/catalog-support/polaris-catalog-support.md)
- 상태 값: `PASS` / `FAIL` / `ENVIRONMENT_BLOCKED` / `NOT_SUPPORTED`. 진행 중인 항목은 `IN_PROGRESS`, 시작하지 않은 항목은 `NOT_STARTED`로 표시한다.
- 최종 갱신: 2026-10-03 (Phase 2 Integration)

## Phase 현황

| Phase | 내용 | 상태 | 주요 산출물 | Commit SHA |
|---|---|---|---|---|
| 1 | 구조 분석 / Gap Analysis | PASS | [phase1-analysis.md](phase1-analysis.md), [compatibility-matrix.md](compatibility-matrix.md), progress.md | `67cf10e4c` |
| 2 | RESTCATALOG OSS Source 완성 | PASS | `@SourceType`, `isUsingVendedCredentials`(tag 13), `restcatalog-layout.json`, Polaris preset, registration/round-trip test, live gate (아래 §Phase 2) | TBD |
| 3 | Polaris OAuth2 / Catalog 연동 | NOT_STARTED | 오류 매핑 (scope/credential/warehouse/403/not-empty), namespace/table 실측, allowedNamespaces, secret/log 검증 | TBD |
| 4 | Object Storage | NOT_STARTED | static S3/MinIO 실측, vended credential compatibility 결과, `storage.md`, `security.md` | TBD |
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

## 완료 기준 현황

`[ ]`는 아직 충족하지 않았다는 뜻이다. 현재 상태와 근거를 함께 적는다.

| # | 기준 | 현재 상태 | 근거 / 다음 단계 |
|---|---|---|---|
| 1 | `[x]` Dremio OSS에서 RESTCATALOG Source 노출 | PASS | Phase 2 live gate #1, #2 (`GET /api/v3/source/type` 200) |
| 2 | `[ ]` UI Source 생성 성공 | IN_PROGRESS | layout과 preset 구현, UI spec PASS, bundle 서빙 확인. 브라우저 수동 생성은 미실행 (Phase 3) |
| 3 | `[x]` REST API Source 생성 성공 | PASS | Phase 2 live gate #3 (`POST /api/v3/catalog` 200, state `good`) |
| 4 | `[x]` Polaris OAuth2 인증 성공 | PASS | Phase 2 live (in-tree build, `scope=PRINCIPAL_ROLE:ALL`). 오류 hint 개선은 Phase 3 |
| 5 | `[x]` Namespace 탐색 성공 | PASS | Phase 2 live gate #7 (`p2ns` 노출). nested와 allowedNamespaces는 Phase 3 |
| 6 | `[ ]` Table 탐색 성공 | TBD (Phase 3) | Phase 2 smoke에서 `polaris.p2ns.t1` 조회 PASS. Polaris/Spark에서 만든 table 탐색은 Phase 3 |
| 7 | `[ ]` SELECT 성공 | TBD (Phase 5) | Phase 2 smoke PASS (MinIO, static `fs.s3a.*`). 정식 검증은 Phase 5 |
| 8 | `[ ]` CREATE TABLE 또는 CTAS 성공 | TBD (Phase 5) | Phase 2 smoke에서 CREATE TABLE PASS. CTAS는 미검증 |
| 9 | `[ ]` INSERT 성공 | TBD (Phase 5) | Phase 2 smoke PASS (2 rows) |
| 10 | `[ ]` Polaris + S3/MinIO 접근 성공 | TBD (Phase 4) | Phase 2 smoke에서 Dremio가 MinIO에 parquet를 쓰고 읽음 (`fs.s3a.endpoint=<host>:<port>`). 설정 matrix는 Phase 4 |
| 11 | `[ ]` Source restart/reload 성공 | TBD (Phase 5) | Phase 2 smoke: Dremio 재시작 후 state `good`, SELECT PASS |
| 12 | `[ ]` allowedNamespaces 동작 | TBD (Phase 3) | 코드는 존재 (`AbstractRestCatalogAccessor.java:145-156`) |
| 13 | `[x]` secret masking 정상 | PASS | Phase 2 live gate #4, #5, #13 (API masking, masked PUT 유지, 로그 0건). at-rest 암호화는 NOT_SUPPORTED (G-12) |
| 14 | `[ ]` Generic RESTCATALOG 회귀 없음 | IN_PROGRESS | icebergcatalog module 182 tests PASS. Polaris 외 REST catalog E2E는 Phase 5 |
| 15 | `[ ]` 관련 unit/integration/E2E 검증 | IN_PROGRESS | Phase 2 unit test 89개 (G-16). opt-in IT는 Phase 4/5 |
| 16 | `[ ]` 문서 완료 | IN_PROGRESS | Phase 1, 2 문서 갱신. Phase 6 문서 세트 남음 |
| 17 | `[ ]` 모든 Phase commit/push 완료 | IN_PROGRESS | Phase 1 commit `67cf10e4c`. Phase 2 commit 대기 |

## Gap 요약 (상세: [phase1-analysis.md §4](phase1-analysis.md#4-gap-목록))

| 심각도 | Gap |
|---|---|
| blocker | ~~G-01 `@SourceType` 누락~~, ~~G-02 layout 누락~~ (Phase 2 해결) |
| high | ~~G-03~~ (해결), G-04 vended runtime 미지원 (flag만 해결), G-05 Polaris `warehouse`/`scope` 필수 (UI/문서 반영, hint는 일부), G-06 DROP VIEW 403, ~~G-07~~ (해결), G-08 MinIO endpoint scheme / requester-pays (Phase 2 smoke PASS, Phase 4 실측), G-28 data path (Phase 2 smoke PASS, Phase 4/5 정식) |
| medium | G-09 오류 매핑 (getState hint만 해결), G-10 비어 있지 않은 namespace, G-11 catalog lifecycle, G-12 secret at-rest (Hadoop conf 복사는 해결), ~~G-13~~, ~~G-15~~, ~~G-16~~ (해결), G-22 LOCATION 403 |
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
