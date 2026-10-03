# Polaris RESTCATALOG Known Limitations

- 기준: `feature/polaris-restcatalog` Phase 1–5 (`67cf10e4c` … `691f2b5a9` + Phase 5 작업 tree), Apache Polaris `1.1.0-incubating`, Iceberg `1.7.0-5f7c992-20250730084652-3bf8b99` (Dremio fork).
- 출처: [progress.md](progress.md), [phase3-oauth-catalog.md](phase3-oauth-catalog.md), [storage.md](storage.md), [security.md](security.md), [compatibility-matrix.md](compatibility-matrix.md), [phase1-analysis.md](phase1-analysis.md) Gap 목록. Phase 5 E2E 결과([test-results.md §5](test-results.md#5-phase-5--실제-e2e--regression))를 반영했다. 괄호 안 `A-…`/`B-…`/`C-…`/`D-…`/`E-…`/`F-…`/`U-…`/`I-…`는 그 표의 시나리오 번호다.
- Status: `NOT_SUPPORTED`(설계상 또는 현재 미지원), `ENVIRONMENT_BLOCKED`(환경 제약으로 미검증), `FAIL`(알려진 결함), `PASS (제약 있음)`(동작하지만 제약), `미검증`(아직 실행하지 않음, 다음 Phase 후보).
- 구분: **plugin**(이 branch의 `plugins/icebergcatalog`에서 고칠 수 있음), **kernel**(`sabot/kernel`, `dac` 등 공통 framework), **upstream**(Iceberg library / Polaris 동작·정책), **환경**.
- 모든 secret은 placeholder다.

## 1. Catalog / DDL

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| C-01 | Table/view rename 없음 (`ALTER TABLE … RENAME`은 parse error) | Dremio에서 이름을 바꿀 수 없다 | Polaris API(`/v1/{prefix}/tables/rename`)나 다른 engine으로 rename. Dremio는 다음 refresh에서 반영 | kernel (Dremio에 rename 경로 없음) | NOT_SUPPORTED |
| C-02 | `DROP TABLE`은 항상 `purgeRequested=false` | Polaris에서 table만 지우고 S3의 data/metadata object가 남는다 | storage lifecycle 정책 또는 수동 삭제 | plugin (기존 동작 유지, 설계 결정 D-12) | NOT_SUPPORTED (purge) |
| C-03 | Polaris 기본 설정에서 `DROP VIEW`가 403 | view를 지울 수 없다 (permission error + hint) | Polaris catalog property `polaris.config.drop-with-purge.enabled=true` | upstream (Polaris 정책) | PASS (제약 있음) |
| C-04 | Dremio 쪽 S3 오류로 실패한 `CREATE TABLE`이 Polaris에 남는다 (orphan table) | 같은 이름 재시도 시 "already exists" | Polaris에서 table drop 후 재시도. Storage 설정을 먼저 확인 | plugin/kernel (CREATE는 Polaris가 첫 metadata를 쓴 뒤 Dremio가 S3에 접근) | FAIL (known) |
| C-05 | Namespace location 밖 `LOCATION` 거부 ("Invalid locations … not in the list of allowed locations") | 임의 경로에 table을 만들 수 없다 | namespace location 아래 경로 사용 | upstream (Polaris unstructured table location 기본 off) | NOT_SUPPORTED (Polaris 정책). Dremio는 permission error로 매핑 |
| C-06 | Commit 시 403 외 `RESTException`(예: Polaris storage credential 오류)은 매핑하지 않는다 | CTAS가 `SYSTEM ERROR: RESTException`. Dremio가 쓴 data file은 지운다 | Polaris storage credential 확인 | plugin (A P-A3, 미구현) | FAIL (known) |
| C-07 | 정확한 HTTP status(404/409/429/502/504)를 메시지에 표기하지 못한다 | hint가 후보 status를 나열한다 | server.log와 Polaris log로 확인 | upstream (Iceberg 예외에 status 없음) | NOT_SUPPORTED |
| C-08 | Table lookup/load 경로(`AbstractRestCatalogAccessor.getDatasetHandle` 등)는 401/403만 매핑한다. 429, 5xx, timeout, connection reset은 raw Iceberg 메시지 (Phase 5 F-12–F-14: "Unable to process: injected fault 429", "Server error: …", timeout은 "Error occurred while processing GET request"이고 `Read timed out`은 server.log에만) | hint 없음. server message가 redact되지 않은 채 오류/DEBUG stack trace에 남을 수 있다 (secret을 echo하는 catalog일 때만 위험). Source state 메시지(`getState`)는 이 경우에도 hint가 있다 | server.log, source state 확인 | plugin (`RestCatalogExceptionMapper`로 매핑, Phase 6 후보) | FAIL (known, 위험 낮음) |
| C-09 | Folder storage URI 변경(`updateFolder`) 거부 | namespace location을 Dremio에서 바꿀 수 없다 | Polaris API로 변경 | plugin (기존 `validateStorageUri`) | NOT_SUPPORTED |
| C-10 | Spark 등에서 만든 view는 첫 SQL representation을 쓴다 (dialect 우선순위 무시, G-25) | SQL dialect가 달라 실패할 수 있다. Phase 5 D-14: spark 다음 DremioSQL이 있는 view에서 spark SQL을 씀. spark 전용 view(D-13)는 SQL이 호환되어 읽힘 | engine별 view 사용 | plugin (기존) | PASS (제약 있음, live 확인) |
| C-11 | Upstream Dremio 26.0.5 이후 IRC fix 미포함 (G-26): 26.0.8 CTAS unpartitioned, 26.1.6 positional delete DML, 26.1.8 requester-pays, 26.1.10 CTAS, 26.1.11 stuck jobs / HTTP 405 partitioned | Phase 5: partitioned CTAS가 Polaris에서 unpartitioned default spec이 되는 문제를 확인(C-6)하고 plugin에서 수정 (`stagedCreateOperations`). UPDATE/DELETE/MERGE는 copy-on-write로 PASS(delete file 0), stuck job/405는 관찰되지 않음. upstream fix 자체는 들어오지 않았다 | 상위 release 반영 시 plugin 수정과 겹치는지 확인 | upstream (Dremio release) / plugin (CTAS 수정) | PASS (CTAS 수정) / 나머지 미관찰 |
| C-12 | `catalogName()`이 null (G-21) | Iceberg table 이름이 `null.<ns>.<t>`로 표시된다 (log, metadata 이름) | — (기능 영향 없음으로 판단) | plugin | NOT_SUPPORTED (low) |
| C-13 | `mutable.enabled=false`(read-only 모드)에서 `getId()`가 throw (G-23) | read 경로 영향 미확인 (Phase 5에서 실행하지 않음) | 기본값(true) 사용 | plugin (기존) | 미검증 |
| C-14 | `plugins.restcatalog.enabled=false`가 목록에서만 숨기고 `GET /api/v3/source/type/RESTCATALOG`와 create는 막지 않는다 (G-20, 정적 분석) | option으로 완전히 끌 수 없다 | — | kernel/dac | NOT_SUPPORTED (low) |
| C-15 | Read-only principal + vended credential의 INSERT가 PERMISSION ERROR 대신 `SYSTEM ERROR: AmazonS3Exception: Access Denied` | 오류 type이 다르다 (거부는 정상) | — | plugin/storage | PASS (제약 있음) |
| C-16 | 잘못된 region으로 INSERT하면 원래 오류 대신 "Memory was leaked by query"가 보인다 | 원인 파악이 어렵다 | region 설정 확인 (`fs.s3a.endpoint.region`) | kernel (오류 표시) | FAIL (known) |
| C-17 | `DROP TABLE IF EXISTS`로 없는 table을 지우면 "Table [...] dropped" (C-22). Accessor가 `catalog.dropTable`의 `false`를 무시해 handler의 "not found" 분기가 돌지 않는다 | 메시지만 틀림 (지운 것 없음) | — | plugin (`false`일 때 not-found validation error. 단 외부에서 지워져 목록에만 남은 table의 DROP 정리 동작과 함께 확인 필요, Phase 6 후보) | FAIL (minor) |
| C-18 | 일부 오류 메시지에 class 이름 prefix: `views_supported=false`의 `UnsupportedOperationException: This operation is unsupported…`, ADD PRIMARY KEY의 `IllegalArgumentException: …` | 표기만 어색 | — | kernel/upstream | PASS (제약 있음) |
| C-19 | `ALTER TABLE ADD PRIMARY KEY`는 nullable column에 실패 ("Cannot add field id as an identifier field: not a required field", C-13) | Dremio에서 만든 column은 nullable이라 사실상 불가 | — | upstream (Iceberg identifier field 규칙) | NOT_SUPPORTED |
| C-20 | `VACUUM TABLE … REMOVE ORPHAN FILES` "command is not supported" (C-19). 실패한 동시 UPDATE가 쓴 data file, DROP TABLE/VIEW 뒤 object가 S3에 남는다 | Dremio로 orphan 정리 불가 | storage lifecycle 또는 다른 engine의 orphan 정리 | kernel/upstream | NOT_SUPPORTED |
| C-21 | `DROP FOLDER IF EXISTS`, `ALTER VIEW … AS`는 parser error (D-5, D-9) | 해당 문법 없음 | `DROP FOLDER`(없으면 "Folder does not exist."), `CREATE OR REPLACE VIEW` | kernel (grammar) | NOT_SUPPORTED |
| C-22 | 표기: DROP FOLDER 메시지 "deleted at the default branch"(versioned source용 문구), UPDATE/MERGE snapshot operation이 `delete`, 0 file을 rewrite한 기본 OPTIMIZE도 빈 `replace` snapshot | 기능 영향 없음 | — | upstream (Dremio) | PASS (제약 있음) |
| C-23 | `plugins.restcatalog.views_supported=false`로 바꿔도 이미 등록된 view는 names refresh 뒤에도 목록에 남는다. SELECT는 "Views are not supported in this catalog."(전환 직후 첫 SELECT는 성공) (D-16) | 목록과 동작 불일치 | option은 source 생성 전에 정하거나, view metadata를 지운다 | plugin/kernel | PASS (제약 있음) |
| C-24 | 잘못된 S3 key에서 INSERT는 `SYSTEM ERROR: AmazonS3Exception … SignatureDoesNotMatch (403)`, SELECT는 PERMISSION ERROR (A-22, F-8) | 오류 type이 다르다 | Catalog Credentials의 S3 key 확인 | plugins/s3 / kernel (writer 오류 매핑) | PASS (제약 있음) |
| C-25 | `ROLLBACK TABLE` (snapshot / timestamp) | Phase 5에서 실행하지 않았다 | — | plugin/kernel | 미검증 ([progress.md Phase 6 handoff](progress.md#phase-6으로-넘기는-항목)) |
| C-26 | 외부 engine(Spark 등)이 data file을 쓴 table의 읽기/쓰기 | Phase 5 B는 Polaris API로 만든 table에 Dremio가 쓴 data로 대신했다. 외부 writer의 file layout, delete file(merge-on-read), 다른 Parquet writer 설정은 확인하지 않았다 | — | plugin/kernel | 미검증 ([progress.md Phase 6 handoff](progress.md#phase-6으로-넘기는-항목)) |

## 2. Namespace / allowedNamespaces / Metadata

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| N-01 | allowedNamespaces는 discovery 범위만 정한다 (접근 제어 아님, B-2) | 목록 밖 table도 직접 경로로 SELECT/CREATE/INSERT 가능 | Polaris grant로 권한 제한 | 설계 결정 (D-8) | NOT_SUPPORTED (강제) |
| N-02 | Non-recursive이면 직접 하위 namespace가 빈 folder로 보인다 (B-5) | tree에 빈 folder | 필요한 namespace를 entry로 추가 | plugin | PASS (제약 있음) |
| N-03 | 기본 separator(`.`)로는 이름에 `.`이 든 namespace를 지정할 수 없다 (G-24). separator는 regex, entry는 trim하지 않고 대소문자 구분 | 일부 namespace 지정 불가 | `plugins.restcatalog.allowed.ns.separator` 변경 (source 시작 시 읽음) | plugin (기존 option) | PASS (제약 있음) |
| N-04 | 공백이 든 namespace 이름 (`with space`) | Iceberg client가 `+`로 encode해 Polaris 404. allowedNamespaces와 무관하게 보이지 않는다 (O-1) | 공백 없는 이름 사용 | upstream (Iceberg/Polaris interop) | FAIL (upstream) |
| N-05 | Source 생성 뒤 Polaris에서 만든 namespace는 다음 names refresh 때 보인다 | 최소 60초(Phase 5 실측 35–121초: A-10, B-12, D-6, E-11), 기본 1시간 지연 | names refresh 주기 단축, 또는 table을 SQL로 직접 조회(즉시 등록) | kernel (공통 metadata framework) | PASS (제약 있음) |
| N-06 | `SHOW TABLES`가 harness SQL API(`/api/v3/sql`) 경로에서 0 rows (`sys`도 0) | **harness bug였다** (B-2): coordinator에서 답하는 문장은 job status `rowCount`가 0이지만 `/results`는 row를 준다. `sql.sh`가 `rowCount>0`일 때만 결과를 읽었다 | `sql.sh` 수정 (항상 `/results` 조회) | harness | PASS (수정됨) |
| N-07 | REST v3 folder 생성 오류가 409와 class 이름 prefix("com.dremio.common.exceptions.UserException: …")로 나온다 (부모 없음 등) | API 오류 표기가 어색하다 (메시지는 정확) | — | kernel (`CatalogServiceHelper.createCatalogItem`) | NOT_SUPPORTED (kernel) |
| N-08 | `DropFolderHandler`가 `CatalogFolderNotEmptyException`을 잡지 않는다 | plugin이 validation error로 바꿔 우회 (사용자 영향 없음) | — | kernel | PASS (우회) |
| N-09 | `/api/v3/catalog/{id}/refresh`는 source metadata refresh가 아니다 (source에 404) | API로 source refresh를 강제할 수 없다 | names refresh 주기, SQL 직접 조회, `ALTER SOURCE … REFRESH STATUS`(state만), `ALTER TABLE … REFRESH METADATA`(table 1개), 설정이 바뀌는 source update(일회성 names refresh) | kernel | NOT_SUPPORTED |
| N-10 | 외부에서 drop한 table은 names refresh로 지워지지 않는다. 다음 full dataset refresh(`datasetRefreshAfterMs`, 기본 1시간, `deleteUnavailableDatasets=true`)까지 tree와 `INFORMATION_SCHEMA`에 남는다 (A-11, B-23; 짧은 주기에서는 E-13 56초). Query는 즉시 "not found" | 목록이 잠시 틀림 | `ALTER TABLE <t> REFRESH METADATA` ("no longer exists, metadata removed.") | kernel (공통 metadata framework) | PASS (제약 있음) |
| N-11 | `allowedNamespaces` 밖의 table은 직접 경로로 조회되지만, 밖의 view는 "not found in namespace"로 풀리지 않는다 (A-4) | table과 view 동작이 다르다 (N-01과 함께 접근 제어로 쓰면 안 된다) | 필요한 namespace를 목록에 추가 | plugin/kernel | PASS (제약 있음) |
| N-12 | 외부(Polaris/다른 engine)에서 바뀐 table은 `dremio.metadata_expiry_check_interval_in_secs`(기본 60초) + plugin table cache(3초) 동안 이전 metadata로 보인다 (B-13, E-14, E-19). 그 사이 schema가 바뀐 table에 INSERT하면 "Table schema … doesn't match with query schema" | 최대 약 1분 stale | `ALTER TABLE … REFRESH METADATA`, 또는 `datasetExpireAfterMs`/`datasetRefreshAfterMs` 단축 (E-16, E-17) | kernel (공통 framework) | PASS (제약 있음) |
| N-13 | Table load 뒤 3초(`plugins.restcatalog.table_cache.expire_after_write_seconds`) 안의 `REFRESH METADATA`는 cache된 이전 metadata를 저장할 수 있다 (B 1회 관찰). Fault 주입도 이 창 안에서는 보이지 않는다 (F) | refresh가 반영되지 않은 것처럼 보임 | 3초 이상 기다리거나 `REFRESH METADATA FORCE UPDATE`(cache 비움, live 미검증) | plugin (upstream 26.0.5 동작) | PASS (제약 있음) |
| N-14 | `plugins.restcatalog.*` option(table cache, catalog expire 등)은 accessor를 만들 때(plugin 시작) 읽는다. Source update는 connection config의 field가 하나라도 바뀌면 새 plugin을 만들어 시작하므로 그때 적용된다. `@NotMetadataImpacting` field(`isCachingEnabled`, `maxCacheSpacePct`, `isUsingVendedCredentials`)만 바꾼 update도 포함된다: `ManagedStoragePlugin.replacePlugin`(과 deprecated 경로)의 재시작 생략(`setLocals`)은 `ConnectionConf.equals`(protostuff byte 전체 비교)가 같을 때만이다. 재시작하지 않는 update는 `metadataPolicy`만 바꾼 update와 같은 config를 다시 보낸 update다. `@NotMetadataImpacting`은 재시작 여부가 아니라 names 전체 삭제/refresh 여부만 정한다 (code, unit `TestRestIcebergCatalogPluginConfig`, A-5의 `start()` INFO, E-20–E-22). Phase 5 E 보고의 "cache field만 바꾼 PUT은 재시작하지 않는다"는 이와 맞지 않아 정정했다 (live 재측정은 하지 않음) | option 변경이 바로 반영되지 않음 | connection config가 바뀌는 source update 또는 Dremio 재시작. `maxCacheSpacePct`의 C3 한도는 Dremio 재시작 뒤 적용 (S-14) | plugin/kernel | PASS (제약 있음) |
| N-15 | Namespace property update (`POST /v1/{prefix}/namespaces/{ns}/properties`). Dremio는 storage URI 변경만 거부한다 (C-09) | Phase 3, 5에서 실측하지 않았다 ([compatibility-matrix.md](compatibility-matrix.md) `TBD (Phase 6)`) | — | plugin | 미검증 ([progress.md Phase 6 handoff](progress.md#phase-6으로-넘기는-항목)) |

## 3. Source lifecycle / 연결

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| K-01 | Source update가 실패하면 `ManagedStoragePlugin.newStartSupplier`가 `SourceMetadataManager`를 닫는다. `replacePlugin`/`replacePluginDeprecated`가 `startAsync(config, false)`로 `closeMetaDataManager=true`를 넘기고, 시작 실패 시 `AutoCloseables.close(metadataManager, plugin)`이 `final wakeupTask`를 영구 취소한다 | Dremio 재시작 전까지 그 source의 background state/names/dataset refresh가 멈춘다 (기존 plugin은 계속 동작, 직접 query와 `ALTER SOURCE … REFRESH STATUS`는 동작). Phase 5 A-18–A-20: 같은 설정으로 다시 update해도(equal config → `setLocals`) 복구되지 않고, 설정이 바뀌는 update는 일회성 names refresh만 한다 | Dremio 재시작 (A-21). update 전 설정 검증. 수정안: update 경로는 metadata manager를 닫지 않도록 `newStartSupplier(config, false)` (최초 add만 닫기) | kernel | FAIL (kernel) |
| K-02 | Update 실패 메시지에 hint가 중복되고 `SourceBadStateException` 문구로 감싸진다 | 메시지가 장황하다 (hint는 있음) | — | kernel (`postSourceModify`) | PASS (제약 있음) |
| K-03 | REST client timeout 기본값 없음 (`rest.client.connection-timeout-ms`/`socket-timeout-ms`) | 응답 없는 catalog가 요청을 최대 3분 붙잡고, state가 일반 메시지("Source is not currently available") | `rest.client.connection-timeout-ms=10000`, `rest.client.socket-timeout-ms=60000` | 설계 결정 (D-7, generic 동작 유지) | PASS (제약 있음) |
| K-04 | Source state check는 catalog만 본다 | 잘못된 S3 key/endpoint여도 state `good`. 오류는 첫 SELECT/INSERT에서 나온다 | 등록 후 `SELECT *` 확인 | plugin (설계) | PASS (제약 있음) |
| K-05 | `token-refresh-enabled=false` | token 만료 직후 query 1건이 "Not authorized"로 실패, 다음 state check가 client 교체 | 기본값(true) 유지 | upstream (Iceberg) | PASS (제약 있음) |
| K-06 | Polaris `warehouse`/`scope` 필수 (G-05) | 없으면 source 생성 400 (hint 있음) | preset/문서대로 설정 | upstream (Polaris 요구사항) | PASS (hint) |
| K-07 | 외부 IdP `oauth2-server-uri`, static bearer `token` | 미검증 | — | 환경 / 미실행 | ENVIRONMENT_BLOCKED (외부 IdP) / TBD (token) |
| K-08 | SigV4 REST signing (`rest.sigv4-enabled`) | Polaris에는 해당 없음 | — | 범위 밖 | NOT_SUPPORTED |
| K-09 | Source 시작 실패 시 `DatasetFileSystemCache.close` NPE WARN | Phase 4에서 수정 | — | plugin | PASS (수정됨) |
| K-10 | Source rename: v3 PUT으로 `name`/`path`를 바꾸면 **HTTP 404** "Source name is immutable." (A-14) | rename 불가, status code가 어색 | 새 이름으로 source 생성 후 기존 source 삭제 (Polaris/S3 영향 없음, A-13) | kernel/dac | NOT_SUPPORTED |
| K-11 | 잘못된 `metadataPolicy` 값(최소값 미만, 모르는 `datasetUpdateMode`)이 400이 아니라 **404** (`CatalogResource.updateCatalogItem`이 `IllegalArgumentException`을 `NotFoundException`으로 매핑, 모든 source type) (E-10) | 값은 거부되지만 status가 틀림 | 메시지 확인 | dac | FAIL (minor, 공통 code) |
| K-12 | Dremio 재시작 때 시작에 실패한 source(Polaris down, credential 무효화)는 API에 kernel의 일반 "Source is not currently available."만 보인다. plugin hint는 server.log의 "Suggested User Action"에만 (F-4, F-7) | 원인 파악에 server.log 필요 | server.log 확인, `ALTER SOURCE … REFRESH STATUS` | kernel (`SourceState.NOT_AVAILABLE`) | PASS (제약 있음) |
| K-13 | `bad` state source는 `GET /api/v3/catalog/{id}`, by-path, `/apiv2/source/{name}`이 400이다. `/api/v3/source/{id}` GET+PUT으로만 고칠 수 있다 (harness `source.sh update`로는 불가) (F-7) | 설정 복구 경로가 제한됨 | `/api/v3/source/{id}` GET → 값 교체 → PUT | kernel/dac | PASS (우회) |
| K-14 | Polaris 동작: principal secret을 한 번 rotate하면 이전 secret도 유효, 두 번째 rotate 뒤 원래 secret 401. 빈 secret token 요청에 HTTP 500 (F-7) | — | — | upstream (Polaris) | 기록 |
| K-15 | 기본 S3A 재시도에서 storage가 응답하지 않으면 SELECT가 수 분 이상 hang하고 cancel이 storage 복구 전까지 끝나지 않는다 (F-5, 399초 이상) | 장애 시 query가 오래 붙잡힘 | `fs.s3a.attempts.maximum`, `fs.s3a.retry.limit`, `fs.s3a.connection.establish.timeout`을 낮춘다 (F-5: `1`/`1`/`3000`이면 2초에 IO_EXCEPTION). 정상 시 재시도 여유와 trade-off | plugins/s3 / Hadoop S3A 기본값 | FAIL (known, 설정으로 완화) |
| K-16 | INSERT 때 executor fragment가 `WARN AbstractRestCatalogAccessor - Missing user context when loading table`을 남긴다 (B, E; 쓰기 1회당) | log noise (결과 정상) | — | upstream 26.0.5 (`799ccbda4`) | 기록 |

## 4. Storage

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| S-01 | 실제 AWS S3 전부: 공식 static baseline, requester-pays bucket, `ListAllMyBuckets` 없는 IAM user, instance profile, assumed role, AWS STS vending | 검증하지 못했다 | MinIO로 같은 key 묶음 검증(PASS). AWS 계정 확보 시 [storage.md §16](storage.md#16-남은-항목-phase-56) 재실행 | 환경 | ENVIRONMENT_BLOCKED |
| S-02 | Multi-node executor (static, vended) | 미검증. vended cache 무효화는 DDL을 실행한 node에만 (다른 node는 만료 후 갱신) | — | 환경 | ENVIRONMENT_BLOCKED |
| S-03 | Vended credential만 있는 source(static key 없음)의 기본 table location 밖 explicit `LOCATION` | `AmazonS3Exception: Access Denied` (staged create credential은 기본 location만 덮음) | `LOCATION` 생략, 또는 그 location 권한이 있는 static key 추가 | plugin (후속 후보: staged create에 location 전달) | NOT_SUPPORTED |
| S-04 | Vended credential은 S3만 매핑. ADLS/GCS vended credential, `config` 없이 `storage-credentials`만 주는 catalog는 source storage 설정으로 fallback | 해당 catalog/storage에서 vended 미사용 | static credential | plugin | NOT_SUPPORTED |
| S-05 | Catalog가 vend하는 endpoint/region/path-style은 무시, source 값 사용 | vended source에도 storage 연결 설정 필요 | source에 endpoint 등 설정 | 설계 결정 (D-10) | PASS (제약 있음) |
| S-06 | Vended를 켜면 모든 node(executor 포함)가 catalog에 접근해야 한다 | network 요구 | — | 설계 | PASS (제약 있음) |
| S-07 | AWS region id가 아닌 region 이름(MinIO `minio-local` 등) | "… is not a valid AWS region." | MinIO region을 AWS region id로 설정 | kernel/plugins/s3 (`S3PluginUtils`, B P3 미구현) | NOT_SUPPORTED |
| S-08 | `dremio.s3.compat` 없이 S3-compatible endpoint 사용 시 MinIO access key ID(secret 아님)가 실제 AWS STS로 전송된다. 경고 없음 | 정보 노출 + STS 403 | `dremio.s3.compat=true` | plugins/s3 (B P4 WARN 미구현) | PASS (제약 있음) |
| S-09 | `dremio.s3.region`만으로는 non-default region MinIO에서 FAIL (S3A가 읽지 않음) | `AuthorizationHeaderMalformed` | `fs.s3a.endpoint.region` 사용 | plugins/s3 (기존) | PASS (설정 필요) |
| S-10 | Key도 provider도 없는 source는 fail closed. Host identity(`AWS_*` env, EC2 instance profile)는 provider를 빈 값으로 명시할 때만 (opt-in) | key 없이 host IAM을 쓰려면 명시 필요. `AWS_*` env opt-in 경로는 live 미검증 | `fs.s3a.aws.credentials.provider=`(빈 값) 명시 | 설계 결정 (D-9) | PASS / ENVIRONMENT_BLOCKED (instance profile) |
| S-11 | Polaris FILE storage를 Dremio에서 읽는 경로, Azure/GCS | 미검증 | — | 범위 밖 | TBD / NOT_SUPPORTED |
| S-12 | Harness truststore mount option 없음 (TLS MinIO는 수동) | E2E 자동화 제약 | 수동 truststore 설정 | harness | NOT_SUPPORTED (harness) |
| S-13 | `SELECT count(*)`(filter 없음)는 snapshot summary로 답해 S3를 읽지 않을 수 있다 | storage 설정 확인용으로 부적절 | `SELECT *` 사용 | kernel (최적화) | PASS (참고) |
| S-14 | C3 cache(OSS 배포본의 `dremio-ce-services-cachemanager`): async reader(`enableAsync=true`)로만 동작 (E-6). `maxCacheSpacePct` 변경은 JVM당 한 번 등록되어 Dremio 재시작 뒤 적용 (E-8). Profile에 `NUM_CACHE_HITS`/`NUM_CACHE_MISSES` 없음 | cache 설정 변경이 즉시 반영되지 않음 | 재시작, cache 사용은 `sys.cache.*`로 확인 | closed CE cachemanager (RESTCATALOG 전용 아님) | PASS (제약 있음) |

## 5. Security

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| X-01 | `secretPropertyList`가 KV store(`data/db`)에 평문 (G-12). `RESTCatalog.properties()`에도 남는다 | data dir 접근자가 secret을 읽을 수 있다 | `dremio-admin encrypt` 값(`secret:1.…`)을 넣는다 (live PASS). 단 key가 같은 data dir(`data/security`)에 있어 data dir 전체 backup에는 효과 없음. `env:`/`file:` URI는 property list에서 풀리지 않는다 | kernel | NOT_SUPPORTED (기본) / PASS (encrypt 완화책) |
| X-02 | Source principal 하나를 모든 Dremio 사용자가 공유 (G-27, `hasAccessPermission()` 항상 true) | Polaris 쪽 사용자별 권한 분리 불가 | 최소 권한 principal, source 분리, Dremio 권한 | 설계/kernel | NOT_SUPPORTED |
| X-03 | Log 고정은 나열한 logger만 막는다 (HttpClient 4/5 wire·headers, SigV4 signer, Netty `LoggingHandler`/`Http2FrameLogger`) | 다른 third-party logger를 DEBUG로 켜면 header가 찍힐 수 있다 | `conf/logback.xml` block 유지, 새 logger는 따로 고정 | 운영 | PASS (제약 있음) |
| X-04 | logback hot reload(`scan="true"`) 때 `no applicable action for [turboFilter]` 경고 관찰 | reload 후 `BlockLogLevelTurboFilter`가 빠지면 root level만으로 third-party DEBUG가 켜질 수 있다 (미확인) | 운영 중 logback 수정 후 재시작 | kernel/운영 | TBD (후속 확인) |
| X-05 | `propertyList`에 secret key를 넣으면 masking되지 않는다. UI는 막지만 API 저장은 backend WARN(key 이름만)뿐. Edit에서 기존 key는 막지 않는다 | 값이 GET 응답에 노출 | Catalog Credentials 사용 | plugin/UI | PASS (제약 있음) |
| X-06 | `com.dremio.exec.catalog.conf.Property.toString()`이 값을 포함 | 새 log 문에 `secretPropertyList`를 그대로 넘기면 노출 | code review 규칙 (plugin은 key 이름만 log) | kernel | PASS (주의) |
| X-07 | 실행 중 받은 값(OAuth2 access token, vended credential)은 `redactSecrets` 대상이 아니다 | memory에는 만료 직전까지 남는다 (log/KV/profile 0건 확인) | heap dump 보호 | 설계 | PASS (제약 있음) |
| X-08 | Harness의 `docker inspect`에 Polaris container env(MinIO secret, bootstrap credential)가 보인다 | 로컬 test 전용 | 운영 Polaris는 secret store/IAM | harness | 기록 |
| X-09 | Polaris client secret을 rotate/revoke해도 실행 중인 source는 끊기지 않는다. Session이 client secret 없이 token을 갱신하고, 새 client를 만들 때(Dremio 재시작, source update)만 실패한다 (F-7). `token-exchange-enabled=false`도 차이 없음 | 자격 증명 회수가 즉시 반영되지 않음 | 회수 후 source를 update(설정 변경)하거나 Dremio 재시작, Polaris 쪽 principal role/grant 회수 | upstream (Iceberg OAuth2 session) | PASS (제약 있음) |

## 6. UI / Packaging

| ID | 항목 | 영향 | 우회책 | 구분 | Status |
|---|---|---|---|---|---|
| U-01 | 브라우저 UI로 source 생성 | Phase 5 U-1–U-13 (Playwright): preset/generic 생성, validation, Edit masking, secret 0건. 오류 dialog의 hint 표시는 UI로 실행하지 않음 (같은 `errorMessage`는 API로 확인) | — | — | PASS |
| U-02 | `-Ddremio.no-ui` 또는 `dac/ui` 재build 없이 만든 tarball의 `dremio-dac-ui` jar는 Phase 2 이전 UI (preset/label/validator 없음). Phase 5 Prep에서 `distribution/server/target`의 tarball과 `~/.m2` jar가 여전히 이전 UI임을 확인했다. Phase 5 E2E 배포본은 새 bundle로 jar를 교체한 scratchpad 복사본이다 | release tarball에 preset 없음 | `dac/ui`를 포함한 Maven build로 tarball 생성 (Phase 6 release) | build | 확인됨 (Phase 6 release build 필요) |
| U-03 | Secret key 이름을 바꿀 때 값 비우기 미구현 (G-14 UI) | masked 값을 둔 채 key 이름만 바꾸면 `applySecretsFrom`이 null 원소를 남기고, backend가 이를 건너뛰어 그 secret이 빠진다 (NPE는 Phase 2에서 해결) | key 이름을 바꿀 때 새 값 입력 | UI | NOT_SUPPORTED (low) |
| U-04 | `location.state.selectedSourceType`로 직접 진입하면 Polaris preset이 적용되지 않는다 | preset 값 미리 채움 없음 | tile에서 선택 | UI | NOT_SUPPORTED (low) |
| U-05 | 기존 source의 `propertyList`에 secret key가 있어도 UI 경고 없음 | 사용자가 모를 수 있다 | server.log WARN 확인 | UI | NOT_SUPPORTED (low) |
| U-06 | `/apiv2/source/type/RESTCATALOG`는 404 (26.x는 `/api/v3`만) | 구 API client | `/api/v3/source/type` | kernel/dac | NOT_SUPPORTED |
| U-07 | `distribution/server/target`의 압축 해제 디렉터리는 rebuild 후에도 `conf/`가 갱신되지 않을 수 있다 | 새 `logback.xml` 미반영 가능 | tarball을 새로 풀어 사용 | build | 기록 |
| U-08 | Harness `s3.sh`는 `@`나 `/`가 들어간 S3 credential을 지원하지 않는다 | E2E 정리 실패 | 다른 credential 또는 `mc` 수동 | harness | NOT_SUPPORTED (harness) |
| U-09 | Layout의 MinIO help text가 `dremio.s3.region=<region>`을 권했다 (storage.md와 불일치, non-default region에서 FAIL) | Phase 5에서 `fs.s3a.endpoint.region=<region>`(us-east-1이 아닐 때)으로 수정하고 불필요한 requester-pays/bucket discovery key를 뺐다 (unit, I-1) | — | plugin | PASS (수정됨) |
