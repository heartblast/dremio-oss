# Polaris / RESTCATALOG Compatibility Matrix

- 기준: Dremio OSS 26.0.5 HEAD (코드 변경 전), Apache Polaris `1.1.0-incubating`, Iceberg `1.7.0-5f7c992-20250730084652-3bf8b99` (`pom.xml:82`)
- 갱신 시점: Phase 1 (2026-10-03). 근거는 [phase1-analysis.md](phase1-analysis.md)에 있다.

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

---

## (a) 공식 Dremio RESTCATALOG config 필드 ↔ OSS 코드

출처:
- 공식: API Catalog > Source > Source Configuration (RESTCATALOG), Iceberg REST Catalog 페이지
- 코드: `plugins/icebergcatalog/src/main/java/com/dremio/plugins/icebergcatalog/store/IcebergCatalogPluginConfig.java:41-66`, `RestIcebergCatalogPluginConfig.java:26-52`

| 필드 | Type | 공식 Default | OSS Default | UI label (공식 / OSS `@DisplayMetadata`) | OSS Tag | 필드 정의 상태 | Source 생성 경로 상태 (HEAD) | 비고 |
|---|---|---|---|---|---|---|---|---|
| `propertyList` | Array<{name,value}> | (optional) | null | Catalog Properties / Catalog Properties | 1 | PASS | FAIL (G-01) | Iceberg props와 Hadoop conf에 그대로 전달된다 |
| `secretPropertyList` | Array<{name,value}> | (optional), masked `$DREMIO_EXISTING_VALUE$` | null | Catalog Credentials / Catalog Credentials (`@Secret`) | 2 | PASS | FAIL (G-01) | API에서 masking된다. at-rest는 평문이다 (G-12) |
| `enableAsync` | Boolean | true | true | (IRC 문서에 별도 toggle 없음) / "Enable asynchronous access for Parquet datasets" | 3 | PASS | FAIL (G-01) | label 차이는 경미하다 |
| `isCachingEnabled` | Boolean | true | true | Enable local caching when possible | 4 | PASS | FAIL (G-01) | `@NotMetadataImpacting` |
| `maxCacheSpacePct` | Integer 1..100 | 100 | 100 | Max percent of total available cache space to use when possible | 5 | PASS | FAIL (G-01) | `@NotMetadataImpacting` |
| `restEndpointUri` | String (required) | — | null | Endpoint URI | 10 | FAIL (validation 없음, G-17) | FAIL (G-01) | 필드는 있으나 `@NotBlank`가 없다 |
| `allowedNamespaces` | Array<String> | (optional) | null (= 전체, recursive) | Allowed Namespaces | 11 | PASS | FAIL (G-01) | separator는 option `plugins.restcatalog.allowed.ns.separator`(`\\.`)로 정한다 |
| `isRecursiveAllowedNamespaces` | Boolean | true | true | Allowed Namespaces include their whole subtrees | 12 | PASS | FAIL (G-01) | |
| `isUsingVendedCredentials` | Boolean (required) | UI: checked (true) / Polaris OSS recipe: false | **없음** | Use vended credentials / — | (계획: 13) | FAIL (G-03) | FAIL (G-01, G-03) | 공식 JSON이 strict Jackson에서 400이 될 가능성이 있다 (static). Phase 2에서 기본값 false로 추가한다 |
| Source type `RESTCATALOG` | — | "Dremio source type: RESTCATALOG" | `@SourceType` 없음 | Iceberg REST Catalog / (UI 상수 "REST Iceberg Catalog") | — | FAIL (G-01) | FAIL: `/source/type`에 없음, create 400 (proto-E2E 확인). annotation을 추가하면 PASS (proto-E2E) | |
| uiConfig layout | — | General / Advanced Options / Reflection Refresh / Metadata / Privileges | 없음 | — | — | FAIL (G-02) | FAIL | Phase 2에서 `restcatalog-layout.json` 작성 |

### (a-2) 공식 Polaris OSS recipe의 property key ↔ OSS 처리

| UI 위치 | Key | 예시 값 | OSS 처리 | 검증 | Status |
|---|---|---|---|---|---|
| General | Endpoint URI | `http://<polaris>:8181/api/catalog` | Iceberg `uri` | client-probe, proto-E2E | PASS |
| General | Use vended credentials | Unchecked | 필드 없음 | static | FAIL (G-03) |
| Catalog Properties | `warehouse` | `<polaris_catalog>` (대소문자 정확히 일치) | `/v1/config?warehouse=`로 전달. 응답의 `prefix` 사용 | client-probe, proto-E2E | PASS |
| Catalog Properties | `scope` | `PRINCIPAL_ROLE:ALL` | Iceberg OAuth2. 기본값 `catalog`는 Polaris가 거부 | client-probe, proto-E2E | PASS (scope 지정 시) / FAIL (미지정 시) |
| Catalog Properties | `fs.s3a.aws.credentials.provider` | `org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider` | Hadoop conf. 비어 있고 access key가 있으면 `FileSystemConfUtil`이 자동으로 채운다 | static | TBD (Phase 4) |
| Catalog Properties | `oauth2-server-uri` (권장, 공식 Polaris recipe에는 없음) | `http://<polaris>:8181/api/catalog/v1/oauth/tokens` | 미지정 시 `<uri>/v1/oauth/tokens`와 deprecation WARN | client-probe | PASS |
| Catalog Properties | `token-refresh-enabled` | 기본 true (false 금지) | Iceberg | client-probe (PT20S) | PASS |
| Catalog Credentials | `credential` | `<client_id>:<client_secret>` | OAuth2 client_credentials | client-probe, proto-E2E | PASS |
| Catalog Credentials | `fs.s3a.access.key` / `fs.s3a.secret.key` | `<s3AccessKey>` / `<s3SecretKey>` | Hadoop conf로 S3FileSystem에 전달 (lazy, G-07) | static | TBD (Phase 4) |

---

## (b) Iceberg REST operations

Dremio plugin 사용 여부와 Polaris 1.1.0 지원 여부를 함께 표시한다.

| Operation (REST) | Dremio plugin에서 사용 | Polaris 1.1.0 | 검증 | Status | 비고 |
|---|---|---|---|---|---|
| `GET /v1/config?warehouse=` | Yes (RESTCatalog initialize, 모든 `getState`) | Yes. warehouse 필수 (없으면 400, 틀리면 404) | client-probe, proto-E2E | PASS | `getState`마다 새 catalog를 만들어 호출한다 (G-11) |
| `POST /v1/oauth/tokens` (client_credentials) | Yes (Iceberg OAuth2Util) | Yes. `scope=PRINCIPAL_ROLE:<role>\|ALL` 필수 | client-probe, proto-E2E | PASS | 잘못된 secret: 401 → `BadRequestException` (G-09) |
| Token refresh (token-exchange) | Yes (기본값 true) | Yes | client-probe | PASS | `catalog.expire_seconds` 1800 < TTL 3600 |
| Endpoint discovery (`endpoints`) | Yes | Yes. 비표준 항목 포함 | client-probe | PASS | |
| `GET /v1/{prefix}/namespaces` (list, nested) | Yes (folder listing, allowedNamespaces) | Yes (`%1F` 지원) | client-probe, proto-E2E (ns1 노출) | PASS | |
| `POST /v1/{prefix}/namespaces` (create) | Yes (CREATE FOLDER) | Yes. `location` 자동 설정 | client-probe | TBD (Phase 3) | Dremio SQL 경로는 미검증 |
| `GET /v1/{prefix}/namespaces/{ns}` (load) | Yes (namespace location → CTAS) | Yes | client-probe | TBD (Phase 3) | |
| `POST …/namespaces/{ns}/properties` (update) | 제한적 (storageUri 변경은 거부) | Yes | static | TBD (Phase 3) | |
| `DELETE /v1/{prefix}/namespaces/{ns}` (빈 namespace) | Yes (DROP FOLDER) | Yes | client-probe | TBD (Phase 3) | |
| `DELETE …/namespaces/{ns}` (비어 있지 않음) | Yes | 400 `NamespaceNotEmptyException` | client-probe | FAIL (G-10) | Iceberg가 `BadRequestException`으로 변환해 raw error로 노출된다 |
| `GET …/namespaces/{ns}/tables` (list) | Yes | Yes | client-probe | TBD (Phase 3) | |
| `GET …/tables/{t}` (load) | Yes (metadata). REST FileIO는 버리고 DremioFileIO 사용 | Yes | client-probe (InMemoryFileIO) | TBD (Phase 3) | Dremio data path 미검증 (G-28) |
| `HEAD …/tables/{t}` (exists) | Yes | Yes | static | TBD (Phase 3) | |
| `POST …/tables` (create) | Yes (CREATE TABLE) | Yes. location은 namespace 아래여야 함 | client-probe | TBD (Phase 3) | out-of-tree LOCATION은 403 (G-22) |
| `POST …/tables` (stage-create) + commit | Yes (CTAS: `newCreateTableTransaction`) | Yes | client-probe | TBD (Phase 3) | |
| `POST …/tables/{t}` (commit / update) | Yes (INSERT, DML, ALTER, OPTIMIZE) | Yes | static | TBD (Phase 3) | |
| `POST /v1/{prefix}/tables/rename` | No (Dremio에 rename API 없음) | Yes (cross-namespace 포함) | client-probe | NOT_SUPPORTED | |
| `DELETE …/tables/{t}?purgeRequested=false` | Yes (DROP TABLE) | Yes (204) | client-probe | PASS | data file이 남는다 |
| `DELETE …/tables/{t}?purgeRequested=true` | No | drop-with-purge 설정 필요 | — | NOT_SUPPORTED | |
| `GET …/namespaces/{ns}/views` (list) | Yes (`views_supported`) | Yes | client-probe | TBD (Phase 3) | |
| `GET …/views/{v}` (load) | Yes (첫 SQL representation 사용) | Yes | client-probe | TBD (Phase 5) | dialect 우선순위 무시 (G-25) |
| `POST …/views` (create) | Yes (dialect `DremioSQL`) | Yes | client-probe | TBD (Phase 5) | |
| `POST …/views/{v}` (replace) | Yes (CREATE OR REPLACE / ALTER VIEW) | Yes | static | TBD (Phase 5) | |
| `DELETE …/views/{v}` (Polaris 기본 설정) | Yes | 403 `Unable to purge entity` | client-probe | FAIL (G-06) | |
| `DELETE …/views/{v}` (`polaris.config.drop-with-purge.enabled=true`) | Yes | 204 | client-probe | PASS | |
| `POST /v1/{prefix}/views/rename` | No | Yes | client-probe | NOT_SUPPORTED | |
| `X-Iceberg-Access-Delegation: vended-credentials` | No (header 미전송, 응답 credential 미사용) | Yes. `TABLE_READ_DATA`/`TABLE_WRITE_DATA` grant 필요 (없으면 root도 403) | client-probe | NOT_SUPPORTED | G-04. Phase 4에서 재평가 |

---

## (c) SQL 기능

- "Gating option"이 false이면 해당 기능이 비활성화된다. 모든 기능은 공통으로 `plugins.restcatalog.enabled=true`가 필요하다.
- 현재 HEAD에서는 G-01 때문에 Source를 만들 수 없다. 따라서 **아래 모든 SQL 기능의 현재 상태는 FAIL**이다.
- "기대 상태"는 Phase 2 완료 후 코드상 기대되는 동작이다.

| SQL | 코드 경로 | Gating option | 기대 상태 (Phase 2 이후) | Status |
|---|---|---|---|---|
| `SHOW SCHEMAS` / folder 탐색 | `AbstractRestCatalogAccessor` namespace listing | `plugins.restcatalog.enabled` (+ allowedNamespaces) | 지원 | TBD (Phase 5) |
| `SHOW TABLES` / dataset discovery | `listDatasetHandles` | 동일 | 지원 | TBD (Phase 5) |
| `SELECT` | `getDatasetMetadata` / `listPartitionChunks` → DremioFileIO | 동일 | 지원 | TBD (Phase 5) |
| `SELECT … AT SNAPSHOT/TIMESTAMP` | `TimeTravelProcessors` | 동일 | 지원 | TBD (Phase 5) |
| `CREATE TABLE` | `createEmptyTable` → `buildTable().create()` | `plugins.restcatalog.mutable.enabled` | 지원 | TBD (Phase 5) |
| `CREATE TABLE … AS SELECT` (CTAS) | `createNewTable` → staged create. namespace `location` 필요 (Polaris는 자동 설정) | `mutable.enabled` | 지원. PARTITION BY는 upstream 26.0.8 fix가 없음 (G-26) | TBD (Phase 5) |
| CTAS with out-of-tree `LOCATION` | 위와 동일 | `mutable.enabled` | Polaris 403 (G-22) | TBD (Phase 5) |
| `INSERT INTO` | `IcebergCatalogModel` commit | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `UPDATE` / `DELETE` / `MERGE` | `FileSystemTableModifyPrule:44` (`SupportsIcebergRestApi`) | `mutable.enabled` | 지원. positional delete 관련 upstream 26.1.6 fix가 없음 | TBD (Phase 5) |
| `OPTIMIZE TABLE` / `VACUUM TABLE` | `FileSystemTableOptimizePrule:44`, `FileSystemVacuumTablePrule:43` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE ADD/DROP/CHANGE COLUMN` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE … PRIMARY KEY` / `LOCALSORT` / `SET/UNSET TBLPROPERTIES` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `TRUNCATE TABLE` / `ROLLBACK TABLE` | `IcebergCatalogModel` | `mutable.enabled` | 지원 | TBD (Phase 5) |
| `ALTER TABLE … RENAME` | 구현 없음 | — | 미지원 | NOT_SUPPORTED |
| `DROP TABLE` | `dropTable(purge=false)` | `mutable.enabled` | 지원. data file은 남음 | TBD (Phase 5) |
| `CREATE [OR REPLACE] VIEW` / `ALTER VIEW` | `IcebergCatalogViewProvider`, dialect `DremioSQL` | `plugins.restcatalog.views_supported` + `mutable.enabled` | 지원 | TBD (Phase 5) |
| `SELECT` from view (Dremio에서 생성) | 첫 SQL representation | `views_supported` | 지원 | TBD (Phase 5) |
| `SELECT` from view (Spark에서 생성) | 첫 SQL representation (spark dialect) | `views_supported` | SQL 호환성에 따라 다름 (G-25) | TBD (Phase 5) |
| `DROP VIEW` | `ViewCatalog.dropView` | `views_supported` + `mutable.enabled` | Polaris catalog에 `polaris.config.drop-with-purge.enabled=true`가 있으면 지원, 없으면 403 (G-06) | TBD (Phase 5) |
| `CREATE FOLDER` / `DROP FOLDER` (namespace) | `createFolder` / `deleteFolder` | `plugins.restcatalog.folders_supported` + `mutable.enabled` | 지원. 비어 있지 않은 namespace는 raw error (G-10) | TBD (Phase 3) |
| Folder storage URI 변경 | `updateFolder` → `validateStorageUri` 거부 | — | 미지원 | NOT_SUPPORTED |
| Read-only 모드 (`mutable.enabled=false`) | `getId()` throw | `mutable.enabled` | read 경로 영향은 미확인 (G-23) | TBD (Phase 5) |

---

## (d) Storage / Auth 모드

### Storage

| 모드 | 설정 | 검증 | Status | 비고 |
|---|---|---|---|---|
| AWS S3 static key (공식 Polaris OSS recipe) | `fs.s3a.aws.credentials.provider=SimpleAWSCredentialsProvider`, secret `fs.s3a.access.key`/`fs.s3a.secret.key` | static | TBD (Phase 4) | 필수 성공 기준. 실제 AWS 계정이 없으면 ENVIRONMENT_BLOCKED가 될 수 있다 |
| MinIO / S3-compatible (path-style, custom endpoint) | `fs.s3a.endpoint=<host>:<port>` (scheme 없음), `fs.s3a.connection.ssl.enabled=false`, `fs.s3a.path.style.access=true`, `dremio.s3.compat=true`, `dremio.bucket.discovery.enabled=false`, `dremio.s3.region=us-east-1`, `fs.s3a.requester.pays.enabled=false` | static | TBD (Phase 4) | bitnamilegacy/minio 사용 |
| `fs.s3a.endpoint`에 scheme 포함 (`http://minio:9000`) | — | static (`S3FileSystem.java:788-791`) | FAIL | `http://http://…`가 된다. 문서에 명시 |
| Requester-pays 기본값 (true) | `S3ClientProperties.java:129` | static | TBD (Phase 4) | MinIO에서는 false를 권장 (26.1.8 동작과 맞춤) |
| S3 env credential / InstanceProfile fallback | provider 미지정, access key 없음 | static (`FileSystemConfUtil.java:228-247`) | TBD (Phase 4) | |
| S3 assumed role | `fs.s3a.assumed.role.arn` + `com.dremio.plugins.s3.store.STSCredentialProviderV1` | static | TBD (Phase 4) | |
| Azure shared key | `fs.azure.account.key…` | static | TBD (Phase 4) | 범위 밖. 확인만 한다 |
| Polaris FILE storage (local test 전용) | Polaris flag 2개와 readiness ignore. host와 경로 공유 | client-probe | TBD (Phase 4) | Dremio에서 `file://` 경로로 읽는 것은 미검증 |
| Polaris 서버 측 S3 (MinIO) metadata write | storageConfigInfo `endpoint` + `pathStyleAccess`, AWS_* env | client-probe | PASS | Polaris가 metadata.json을 직접 쓴다 |
| Vended credentials (`isUsingVendedCredentials=true`) | header + Polaris grant | client-probe (Polaris가 `s3.*`를 반환함) | NOT_SUPPORTED | Dremio가 응답 credential을 버린다 (G-04). Phase 4에서 별도 compatibility 결과로 기록 |
| Executor storage 설정 (multi-node) | lazy fsConf 주입 | static | FAIL (G-07) | Phase 2에서 eager copy로 수정 |

### Auth

| 모드 | 설정 | 검증 | Status | 비고 |
|---|---|---|---|---|
| OAuth2 client_credentials | `credential=<client_id>:<client_secret>`, `scope=PRINCIPAL_ROLE:ALL` | client-probe, proto-E2E | PASS | |
| OAuth2 scope 미지정 | — | client-probe | FAIL | 400 `invalid_scope` (G-05) |
| OAuth2 dedicated principal (root 아님) | `scope=PRINCIPAL_ROLE:dremio_role` | client-probe | PASS | `CATALOG_MANAGE_CONTENT` |
| Static bearer `token` | `token=<bearer>` | static | TBD (Phase 3) | |
| External IdP (`oauth2-server-uri`) | `oauth2-server-uri=<idp>` | static | TBD (Phase 3) | |
| SigV4 (`rest.sigv4-enabled`) | Glue/S3 Tables용 | static | NOT_SUPPORTED | Polaris에는 해당 없음 |
| Secret masking (API GET/PUT) | `secretPropertyList` | proto-E2E | PASS | `$DREMIO_EXISTING_VALUE$` |
| Secret at-rest 암호화 | `List<Property>` | static | NOT_SUPPORTED | G-12. kernel 전체 범위의 open item |
| Secret 로그 노출 (기본 log level) | — | proto-E2E (server.log, server.out grep) | PASS | `org.apache.hc` DEBUG 로그에서는 노출된다 (운영 환경 금지) |
