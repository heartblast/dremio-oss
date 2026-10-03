# Polaris / RESTCATALOG Compatibility Matrix

- 기준: Dremio OSS 26.0.5 + Phase 2/3 변경 (`feature/polaris-restcatalog`), Apache Polaris `1.1.0-incubating`, Iceberg `1.7.0-5f7c992-20250730084652-3bf8b99` (`pom.xml:82`)
- 갱신 시점: Phase 3 Integration (2026-10-03). Phase 1 근거는 [phase1-analysis.md](phase1-analysis.md), Phase 2 live gate는 [progress.md](progress.md#phase-2--restcatalog-oss-source-완성), Phase 3 결과는 [phase3-oauth-catalog.md](phase3-oauth-catalog.md)에 있다.

## 상태 값

Status 열에는 아래 값만 쓴다.

| 값 | 의미 |
|---|---|
| `PASS` | 검증 완료 |
| `FAIL` | 현재 동작하지 않음 (Gap) |
| `ENVIRONMENT_BLOCKED` | 환경 제약 때문에 검증하지 못함 |
| `NOT_SUPPORTED` | 설계상 지원하지 않음 |
| `TBD (Phase N)` | 아직 검증하지 않음. N은 검증할 Phase |

## 검증 수준

"검증" 열에서 쓰는 약어:

| 약어 | 의미 |
|---|---|
| **static** | 코드 정적 분석 |
| **client-probe** | Dremio의 Iceberg jar로 Polaris를 직접 호출. Dremio data path(DremioFileIO, S3)는 거치지 않는다 |
| **proto-E2E** | 빌드된 Dremio tarball에 `@SourceType` prototype(out-of-tree)을 넣고 확인 |
| **E2E** | 실제 Dremio SQL 경로로 확인 |
| **live-P2** | Phase 2 in-tree build tarball + Polaris 1.1.0 + 로컬 MinIO로 REST API/SQL 확인 (smoke) |
| **live-P3** | Phase 3 tarball + Polaris 1.1.0 + 로컬 MinIO, `scripts/polaris-e2e` harness (Agent A–D 각 instance와 integration 재검증 INSTANCE=5) |
| **unit** | icebergcatalog module unit test |

---

## (a) 공식 Dremio RESTCATALOG config 필드 ↔ OSS 코드

출처:
- 공식: API Catalog > Source > Source Configuration (RESTCATALOG), Iceberg REST Catalog 페이지
- 코드: `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPluginConfig.java:41-66`, `RestIcebergCatalogPluginConfig.java:26-52`

| 필드 | Type | 공식 Default | OSS Default | UI label (공식 / OSS `@DisplayMetadata`) | OSS Tag | 필드 정의 상태 | Source 생성 경로 상태 (Phase 2) | 비고 |
|---|---|---|---|---|---|---|---|---|
| `propertyList` | Array<{name,value}> | (optional) | null | Catalog Properties / Catalog Properties | 1 | PASS | PASS (live-P2) | Iceberg props에는 그대로 전달된다. Hadoop conf에는 REST client 전용 key(`credential`, `token`, `scope`, `oauth2-server-uri`, `header.*` 등)를 뺀 나머지만 복사된다 |
| `secretPropertyList` | Array<{name,value}> | (optional), masked `$DREMIO_EXISTING_VALUE$` | null | Catalog Credentials / Catalog Credentials (`@Secret`) | 2 | PASS | PASS (live-P2) | API에서 masking된다. at-rest는 평문이다 (G-12) |
| `enableAsync` | Boolean | true | true | (IRC 문서에 별도 toggle 없음) / "Enable asynchronous access for Parquet datasets" | 3 | PASS | PASS (live-P2) | label 차이는 경미하다 |
| `isCachingEnabled` | Boolean | true | true | Enable local caching when possible | 4 | PASS | PASS (live-P2) | `@NotMetadataImpacting` |
| `maxCacheSpacePct` | Integer 1..100 | 100 | 100 | Max percent of total available cache space to use when possible | 5 | PASS | PASS (live-P2) | `@NotMetadataImpacting` |
| `restEndpointUri` | String (required) | — | null | Endpoint URI | 10 | PASS (`@NotBlank`, G-17) | PASS (live-P2). 누락 시 400 validation error | layout에서도 required |
| `allowedNamespaces` | Array<String> | (optional) | null (= 전체, recursive) | Allowed Namespaces | 11 | PASS | PASS (live-P3: nested entry의 부모 folder 유지 수정 후) | separator는 option `plugins.restcatalog.allowed.ns.separator`(`\\.`, regex)로 정한다. discovery 범위만 정하며 접근 제어는 아니다 |
| `isRecursiveAllowedNamespaces` | Boolean | true | true | Allowed Namespaces include their whole subtrees | 12 | PASS | PASS (live-P3) | false면 직접 하위 namespace가 빈 folder로 보인다 (C B-5, 문서화) |
| `isUsingVendedCredentials` | Boolean (required) | UI: checked (true) / Polaris OSS recipe: false | false | Use vended credentials / Use vended credentials | 13 | PASS (unit: tag, 기본값, 25.2.0 bytes 호환) | PASS (live-P2: false/true 모두 200) | `@NotMetadataImpacting`. 기본값 false는 기존 source 동작 유지를 위한 것. true면 header만 보낸다 (G-04) |
| Source type `RESTCATALOG` | — | "Dremio source type: RESTCATALOG" | `@SourceType("RESTCATALOG")` | Iceberg REST Catalog / Iceberg REST Catalog (UI 상수도 동일) | — | PASS (unit) | PASS (live-P2: `/api/v3/source/type` 200, create 200) | Polaris 전용 type 없음. Polaris는 UI preset |
| uiConfig layout | — | General / Advanced Options / Reflection Refresh / Metadata / Privileges | `restcatalog-layout.json` (General / Advanced Options) | — | — | PASS (unit: propName ↔ `@Tag` 필드 일치) | PASS (live-P2: `/api/v3/source/type/RESTCATALOG`에 uiConfig) | 나머지 tab은 UI가 공통으로 추가한다. 브라우저 생성은 미실행 |

### (a-2) 공식 Polaris OSS recipe의 property key ↔ OSS 처리

| UI 위치 | Key | 예시 값 | OSS 처리 | 검증 | Status |
|---|---|---|---|---|---|
| General | Endpoint URI | `http://<polaris>:8181/api/catalog` | Iceberg `uri` | client-probe, proto-E2E | PASS |
| General | Use vended credentials | Unchecked | `isUsingVendedCredentials` (tag 13). true면 `header.X-Iceberg-Access-Delegation=vended-credentials` | unit, live-P2 | PASS (flag). credential 사용은 NOT_SUPPORTED (G-04) |
| Catalog Properties | `warehouse` | `<polaris_catalog>` (대소문자 정확히 일치) | `/v1/config?warehouse=`로 전달. 응답의 `prefix` 사용 | client-probe, proto-E2E | PASS |
| Catalog Properties | `scope` | `PRINCIPAL_ROLE:ALL` | Iceberg OAuth2. 기본값 `catalog`는 Polaris가 거부 | client-probe, proto-E2E, live-P3 | PASS (scope 지정 시). 미지정/잘못된 scope는 source 생성 400과 `invalid_scope` hint (Phase 3) |
| Catalog Properties | `fs.s3a.aws.credentials.provider` | `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` | Hadoop conf. 비어 있고 access key가 있으면 `FileSystemConfUtil`이 자동으로 채운다 | live-P2 (MinIO) | PASS (MinIO smoke). AWS S3는 TBD (Phase 4) |
| Catalog Properties | `oauth2-server-uri` (권장, 공식 Polaris recipe에는 없음) | `http://<polaris>:8181/api/catalog/v1/oauth/tokens` | 미지정 시 `<uri>/v1/oauth/tokens`와 deprecation WARN | client-probe | PASS |
| Catalog Properties | `token-refresh-enabled` | 기본 true (false 금지) | Iceberg | client-probe (PT20S), live-P3 (Polaris token TTL 60초) | PASS. false면 만료 직후 query 1건이 실패하고 state check가 client를 교체한다 |
| Catalog Properties | `rest.client.connection-timeout-ms` / `rest.client.socket-timeout-ms` / `rest.client.max-retries` | 권장 `10000` / `60000` / 기본 5 | Iceberg HTTPClient. Hadoop conf로 복사하지 않는다 (Phase 3) | unit, live-P3 | PASS. 미설정이면 timeout 3분 ([security.md §5](security.md#5-재시도와-timeout-iceberg-rest-client)) |
| Catalog Credentials | `credential` | `<client_id>:<client_secret>` | OAuth2 client_credentials | client-probe, proto-E2E | PASS |
| Catalog Credentials | `fs.s3a.access.key` / `fs.s3a.secret.key` | `<s3AccessKey>` / `<s3SecretKey>` | Hadoop conf로 S3FileSystem에 전달 (Phase 2부터 `start()` 시점 eager copy, G-07) | unit, live-P2 (MinIO) | PASS (MinIO smoke). AWS S3는 TBD (Phase 4) |

---

## (b) Iceberg REST operations

Dremio plugin 사용 여부와 Polaris 1.1.0 지원 여부를 함께 표시한다.

| Operation (REST) | Dremio plugin에서 사용 | Polaris 1.1.0 | 검증 | Status | 비고 |
|---|---|---|---|---|---|
| `GET /v1/config?warehouse=` | Yes (RESTCatalog initialize) | Yes. warehouse 필수 (없으면 400, 틀리면 404) | client-probe, proto-E2E, live-P3 | PASS | Phase 3: state check는 cached client를 재사용하고 `listNamespaces(root)` 1회만 호출한다. 새 client(config + token)는 cached client가 없거나 401 등으로 실패할 때만 만든다 (G-11 해결) |
| `POST /v1/oauth/tokens` (client_credentials) | Yes (Iceberg OAuth2Util) | Yes. `scope=PRINCIPAL_ROLE:<role>\|ALL` 필수 | client-probe, proto-E2E, live-P3 | PASS | 잘못된 secret: Polaris 401 `unauthorized_client` → Iceberg `BadRequestException`. Phase 3부터 source 생성 400 응답에 OAuth2 error code와 hint가 나온다 (G-09 해결) |
| Token refresh (token-exchange) | Yes (기본값 true) | Yes | client-probe, live-P3 (TTL 60초) | PASS | 만료 전 자동 갱신. 새 config 요청 없음 |
| Endpoint discovery (`endpoints`) | Yes | Yes. 비표준 항목 포함 | client-probe | PASS | |
| `GET /v1/{prefix}/namespaces` (list, nested) | Yes (folder listing, allowedNamespaces, state check) | Yes (`%1F` 지원) | client-probe, proto-E2E, live-P2, live-P3 | PASS | nested(3 level) 포함. 403이면 state `warn` (allowedNamespaces 미설정 시). 공백이 든 namespace 이름은 Iceberg client가 `+`로 encode해 Polaris가 404를 반환한다 (C O-1, upstream) |
| `POST /v1/{prefix}/namespaces` (create) | Yes (CREATE FOLDER) | Yes. `location` 자동 설정 | client-probe, live-P3 | PASS | 1–3 level. 부모 없음은 validation error, 403은 permission error (REST v3 403) |
| `GET /v1/{prefix}/namespaces/{ns}` (load) | Yes (namespace location → CTAS, folder listing) | Yes | client-probe, live-P3 (CTAS) | PASS | allowedNamespaces의 조상 folder는 load하지 않는다 (Phase 3) |
| `POST …/namespaces/{ns}/properties` (update) | 제한적 (storageUri 변경은 거부) | Yes | static | TBD (Phase 5) | Phase 3에서 실측하지 않음 |
| `DELETE /v1/{prefix}/namespaces/{ns}` (빈 namespace) | Yes (DROP FOLDER) | Yes | client-probe, live-P3 | PASS | 없는 namespace는 "Folder does not exist" |
| `DELETE …/namespaces/{ns}` (비어 있지 않음) | Yes | 400 `NamespaceNotEmptyException` | client-probe, unit, live-P3 | PASS | Phase 3: "Folder [..] cannot be deleted because it is not empty…" validation error (G-10 해결). 표준 409도 같은 메시지 |
| `GET …/namespaces/{ns}/tables` (list) | Yes | Yes | client-probe, live-P3 | PASS | INFORMATION_SCHEMA로 확인. `SHOW TABLES`는 harness SQL API 경로에서 0 rows (조사 필요, Phase 5) |
| `GET …/tables/{t}` (load) | Yes (metadata). REST FileIO는 버리고 DremioFileIO 사용 | Yes | client-probe, live-P2 (SELECT, MinIO) | PASS (smoke) | Phase 5에서 정식 검증 |
| `HEAD …/tables/{t}` (exists) | Yes (dataset lookup) | Yes | unit, live-P3 | PASS | 401/403은 permission error로 매핑 (Phase 3) |
| `POST …/tables` (create) | Yes (CREATE TABLE) | Yes. location은 namespace 아래여야 함 | client-probe, live-P2 (CREATE TABLE) | PASS (smoke) | out-of-tree LOCATION은 403 (G-22) |
| `POST …/tables` (stage-create) + commit | Yes (CTAS: `newCreateTableTransaction`) | Yes | client-probe, live-P3 | PASS | 3 level namespace 포함. 403은 permission error (`CREATE_TABLE_STAGED`) |
| `POST …/tables/{t}` (commit / update) | Yes (INSERT, DML, ALTER, OPTIMIZE) | Yes | live-P2, live-P3, unit | PASS (INSERT, UPDATE, DELETE, MERGE) | 403은 permission error (이전에는 `WRITER_COMMITTER` SYSTEM ERROR). 403 예외는 Iceberg `CleanableFailure`로 유지되어 거부된 commit의 manifest가 정리된다 (live-P3 INSTANCE=6: 남은 object 0). 409 `CommitFailedException`은 기존 concurrent modification 경로 (unit). ALTER/OPTIMIZE는 Phase 5 |
| `POST /v1/{prefix}/tables/rename` | No (Dremio에 rename API 없음) | Yes (cross-namespace 포함) | client-probe | NOT_SUPPORTED | |
| `DELETE …/tables/{t}?purgeRequested=false` | Yes (DROP TABLE) | Yes (204) | client-probe, live-P2 | PASS | data file이 남는다 |
| `DELETE …/tables/{t}?purgeRequested=true` | No | drop-with-purge 설정 필요 | — | NOT_SUPPORTED | |
| `GET …/namespaces/{ns}/views` (list) | Yes (`views_supported`) | Yes | client-probe, live-P3 | PASS | INFORMATION_SCHEMA에 view 포함 |
| `GET …/views/{v}` (load) | Yes (첫 SQL representation 사용) | Yes | client-probe | TBD (Phase 5) | dialect 우선순위 무시 (G-25) |
| `POST …/views` (create) | Yes (dialect `DremioSQL`) | Yes | client-probe | TBD (Phase 5) | |
| `POST …/views/{v}` (replace) | Yes (CREATE OR REPLACE / ALTER VIEW) | Yes | static | TBD (Phase 5) | |
| `DELETE …/views/{v}` (Polaris 기본 설정) | Yes | 403 `Unable to purge entity` | client-probe, live-P3 | PASS (오류 매핑) | Phase 3: permission error + `polaris.config.drop-with-purge.enabled` hint (G-06 해결). drop 자체는 catalog 설정이 필요하다 |
| `DELETE …/views/{v}` (`polaris.config.drop-with-purge.enabled=true`) | Yes | 204 | client-probe | PASS | |
| `POST /v1/{prefix}/views/rename` | No | Yes | client-probe | NOT_SUPPORTED | |
| `X-Iceberg-Access-Delegation: vended-credentials` | 부분: `isUsingVendedCredentials=true`이면 header 전송 (Phase 2). 응답 credential은 미사용 | Yes. Phase 2 live에서는 `CATALOG_MANAGE_CONTENT` grant만으로 loadTable 200과 `s3.*` 반환 (Phase 1 관찰: grant 없으면 root도 403) | unit, live-P2 | NOT_SUPPORTED (runtime) | G-04. 읽기/쓰기는 source의 static `fs.s3a.*`로 한다. Phase 4에서 재평가 |

---

## (c) SQL 기능

- "Gating option"이 false이면 해당 기능이 비활성화된다. 모든 기능은 공통으로 `plugins.restcatalog.enabled=true`가 필요하다.
- Phase 2부터 Source를 만들 수 있다 (G-01 해결). Phase 2 live smoke에서 확인한 항목은 PASS (smoke)로 표시했고, 정식 검증은 Phase 5에서 한다.
- "기대 상태"는 Phase 2 완료 후 코드상 기대되는 동작이다.

| SQL | 코드 경로 | Gating option | 기대 상태 (Phase 2 이후) | Status |
|---|---|---|---|---|
| `SHOW SCHEMAS` / folder 탐색 | `AbstractRestCatalogAccessor` namespace listing | `plugins.restcatalog.enabled` (+ allowedNamespaces) | 지원 | PASS (smoke, catalog API listing) |
| `SHOW TABLES` / dataset discovery | `listDatasetHandles` | 동일 | 지원 | PASS (discovery, live-P3 INFORMATION_SCHEMA). `SHOW TABLES` 문장은 TBD (Phase 5) |
| `SELECT` | `getDatasetMetadata` / `listPartitionChunks` → DremioFileIO | 동일 | 지원 | PASS (smoke, MinIO) |
| `SELECT … AT SNAPSHOT/TIMESTAMP` | `TimeTravelProcessors` | 동일 | 지원 | TBD (Phase 5) |
| `CREATE TABLE` | `createEmptyTable` → `buildTable().create()` | `plugins.restcatalog.mutable.enabled` | 지원 | PASS (smoke) |
| `CREATE TABLE … AS SELECT` (CTAS) | `createNewTable` → staged create. namespace `location` 필요 (Polaris는 자동 설정) | `mutable.enabled` | 지원. PARTITION BY는 upstream 26.0.8 fix가 없음 (G-26) | PASS (live-P3, unpartitioned). partitioned는 TBD (Phase 5) |
| CTAS with out-of-tree `LOCATION` | 위와 동일 | `mutable.enabled` | Polaris 403 → permission error (Phase 3 매핑, unit) | TBD (Phase 5 live) |
| `INSERT INTO` | `IcebergCatalogModel` commit | `mutable.enabled` | 지원 | PASS (smoke, MinIO) |
| `UPDATE` / `DELETE` / `MERGE` | `FileSystemTableModifyPrule:44` (`SupportsIcebergRestApi`) | `mutable.enabled` | 지원. positional delete 관련 upstream 26.1.6 fix가 없음 | PASS (live-P3 smoke, B: 4 snapshots). 정식 검증은 Phase 5 |
| `OPTIMIZE TABLE` / `VACUUM TABLE` | `FileSystemTableOptimizePrule:44`, `FileSystemVacuumTablePrule:43` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE ADD/DROP/CHANGE COLUMN` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE … PRIMARY KEY` / `LOCALSORT` / `SET/UNSET TBLPROPERTIES` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `TRUNCATE TABLE` / `ROLLBACK TABLE` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE … RENAME` | 구현 없음 | — | 미지원 | NOT_SUPPORTED |
| `DROP TABLE` | `dropTable(purge=false)` | `mutable.enabled` | 지원. data file은 남음 | PASS (smoke) |
| `CREATE [OR REPLACE] VIEW` / `ALTER VIEW` | `IcebergCatalogViewProvider`, dialect `DremioSQL` | `plugins.restcatalog.views_supported` + `mutable.enabled` | 지원 | PASS (live-P3, CREATE / CREATE OR REPLACE). ALTER VIEW는 TBD (Phase 5) |
| `SELECT` from view (Dremio에서 생성) | 첫 SQL representation | `views_supported` | 지원 | PASS (live-P3) |
| `SELECT` from view (Spark에서 생성) | 첫 SQL representation (spark dialect) | `views_supported` | SQL 호환성에 따라 다름 (G-25) | TBD (Phase 5) |
| `DROP VIEW` | `ViewCatalog.dropView` | `views_supported` + `mutable.enabled` | Polaris catalog에 `polaris.config.drop-with-purge.enabled=true`가 있으면 지원, 없으면 403 (G-06) | PASS (live-P3: true면 COMPLETED, false면 hint가 붙은 permission error) |
| `CREATE FOLDER` / `DROP FOLDER` (namespace) | `createFolder` / `deleteFolder` | `plugins.restcatalog.folders_supported` + `mutable.enabled` | 지원. 비어 있지 않은 namespace는 명확한 validation error (G-10 해결) | PASS (live-P3) |
| Folder storage URI 변경 | `updateFolder` → `validateStorageUri` 거부 | — | 미지원 | NOT_SUPPORTED |
| Read-only 모드 (`mutable.enabled=false`) | `getId()` throw | `mutable.enabled` | read 경로 영향은 미확인 (G-23) | TBD (Phase 5) |

---

## (d) Storage / Auth 모드

### Storage

| 모드 | 설정 | 검증 | Status | 비고 |
|---|---|---|---|---|
| AWS S3 static key (공식 Polaris OSS recipe) | `fs.s3a.aws.credentials.provider=SimpleAWSCredentialsProvider`, secret `fs.s3a.access.key`/`fs.s3a.secret.key` | static | TBD (Phase 4) | 필수 성공 기준. 실제 AWS 계정이 없으면 ENVIRONMENT_BLOCKED가 될 수 있다 |
| MinIO / S3-compatible (path-style, custom endpoint) | `fs.s3a.endpoint=<host>:<port>` (scheme 없음), `fs.s3a.connection.ssl.enabled=false`, `fs.s3a.path.style.access=true`, `dremio.s3.compat=true`, `dremio.bucket.discovery.enabled=false`, `dremio.s3.region=us-east-1`, `fs.s3a.requester.pays.enabled=false` | live-P2 (로컬 MinIO, 이 key 묶음 그대로) | PASS (smoke) | CREATE/INSERT/SELECT/재시작 확인. key별 필요 여부는 Phase 4에서 실측 |
| `fs.s3a.endpoint`에 scheme 포함 (`http://minio:9000`) | — | static (`S3FileSystem.java:788-791`) | FAIL | `http://http://…`가 된다. 문서에 명시 |
| Requester-pays 기본값 (true) | `S3ClientProperties.java:129` | static | TBD (Phase 4) | MinIO에서는 false를 권장 (26.1.8 동작과 맞춤) |
| S3 env credential / InstanceProfile fallback | provider 미지정, access key 없음 | static (`FileSystemConfUtil.java:228-247`) | TBD (Phase 4) | |
| S3 assumed role | `fs.s3a.assumed.role.arn` + `com.dremio.plugins.s3.store.STSCredentialProviderV1` | static | TBD (Phase 4) | |
| Azure shared key | `fs.azure.account.key…` | static | TBD (Phase 4) | 범위 밖. 확인만 한다 |
| Polaris FILE storage (local test 전용) | Polaris flag 2개와 readiness ignore. host와 경로 공유 | client-probe | TBD (Phase 4) | Dremio에서 `file://` 경로로 읽는 것은 미검증 |
| Polaris 서버 측 S3 (MinIO) metadata write | storageConfigInfo `endpoint` + `pathStyleAccess`, AWS_* env | client-probe | PASS | Polaris가 metadata.json을 직접 쓴다 |
| Vended credentials (`isUsingVendedCredentials=true`) | header + Polaris grant | client-probe, unit, live-P2 (flag true source: state good, SELECT는 static key로 성공) | NOT_SUPPORTED | header는 보내지만 Dremio가 응답 credential을 버린다 (G-04). Phase 4에서 별도 compatibility 결과로 기록 |
| Executor storage 설정 (multi-node) | `start()`/`getFsConfCopy()`에서 eager copy | unit | PASS (unit) | G-07 해결. multi-node 실측은 Phase 5 |

### Auth

| 모드 | 설정 | 검증 | Status | 비고 |
|---|---|---|---|---|
| OAuth2 client_credentials | `credential=<client_id>:<client_secret>`, `scope=PRINCIPAL_ROLE:ALL` | client-probe, proto-E2E, live-P2, live-P3 | PASS | Phase 3: 잘못된 credential / scope / warehouse, 닿지 않는 endpoint는 source 생성 400 응답의 `errorMessage`에 원인 hint가 나온다 (G-09 해결) |
| OAuth2 scope 미지정 | — | client-probe, live-P3 (A) | FAIL (Polaris 요구사항) | Polaris가 400 `invalid_scope`로 거부한다. Phase 3부터 "Set the 'scope' catalog property" hint가 API 응답에 나온다 (G-05 hint 해결) |
| OAuth2 dedicated principal (root 아님) | `scope=PRINCIPAL_ROLE:dremio_role` | client-probe, live-P3 | PASS | `CATALOG_MANAGE_CONTENT`. read-only principal은 SELECT 성공, 쓰기는 permission error. `NAMESPACE_LIST`가 없으면 state `warn` (allowedNamespaces 미설정 시) |
| 401 / 403 / token 만료 / catalog 재시작 | — | unit, live-P3 | PASS | 403은 동작별 permission error. Polaris 재시작(새 signing key) 후 cached client가 401이면 state check가 새 client로 교체한다 |
| Timeout / Polaris unavailable | `rest.client.*-timeout-ms` | unit, live-P3 | PASS (복구, hint) | timeout 미설정 시 hang은 일반 메시지("Source is not currently available"). 설정하면 "did not respond in time" hint |
| TLS 실패 (self-signed, scheme 불일치) | — | unit | PASS (hint) | Phase 3 integration: TLS hint와 trust store 안내. credential은 handshake 전에 실패하므로 전송되지 않는다 |
| Static bearer `token` | `token=<bearer>` | static | TBD (Phase 5) | Phase 3에서 실측하지 않음 |
| External IdP (`oauth2-server-uri`) | `oauth2-server-uri=<idp>` | static, live-P3 (Polaris 자체 token endpoint를 명시) | PASS (Polaris endpoint) / ENVIRONMENT_BLOCKED (외부 IdP 없음) | |
| SigV4 (`rest.sigv4-enabled`) | Glue/S3 Tables용 | static | NOT_SUPPORTED | Polaris에는 해당 없음 |
| Secret masking (API GET/PUT) | `secretPropertyList` | proto-E2E, unit, live-P2 (v3 GET, v2 GET, masked PUT 후 state good) | PASS | `$DREMIO_EXISTING_VALUE$` |
| Secret at-rest 암호화 | `List<Property>` | static | NOT_SUPPORTED | G-12. kernel 전체 범위의 open item |
| `propertyList`(plain)에 secret key | — | unit (backend WARN, key 이름만), UI spec (validator가 저장 차단) | PASS | G-13. UI와 backend가 같은 판정 규칙을 쓴다. Edit에서는 이미 저장된 key는 막지 않고 새/이름 바뀐 row만 막는다. API로 저장하면 backend WARN만 남는다 |
| Secret 로그 노출 (기본 log level) | — | proto-E2E, unit, live-P2, live-P3 (DEBUG 포함, 5개 instance 모두 0건) | PASS | HttpClient wire/headers logger는 `conf/logback.xml`에서 INFO로 고정된다. 상세는 [security.md](security.md) |
