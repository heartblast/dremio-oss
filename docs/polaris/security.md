# Polaris RESTCATALOG 보안 (secret, 로그, 오류 메시지)

- 이 문서는 RESTCATALOG source가 다루는 secret이 어디로 흘러가고 어디에 남지 않아야 하는지 정리한다.
- Phase 3에서 처음 작성했다. Phase 4(Object Storage, vended credentials)에서 storage credential 항목을 보강한다.
- 기준 코드: `feature/polaris-restcatalog` Phase 3 작업 트리. Iceberg `1.7.0-5f7c992-20250730084652-3bf8b99` (Dremio fork). `HTTPClient`는 Apache HttpClient `5.3.1`을 relocation 없이 쓴다.
- 관련 문서: [phase3-oauth-catalog.md](phase3-oauth-catalog.md) (Phase 3 시나리오 결과), [progress.md](progress.md), [compatibility-matrix.md](compatibility-matrix.md).
- 이 문서의 모든 secret은 placeholder(`<client_id>:<client_secret>`, `<s3AccessKey>`, `<s3SecretKey>`)다. Polaris bootstrap root secret `s3cr3t`는 throwaway container용 로컬 테스트 값이다.

## 1. 보호 대상

로그, API 응답, 오류 메시지에 절대 나오면 안 되는 값 (polaris-catalog-support.md Phase 3 Agent D):

| 값 | 설정 위치 | 비고 |
|---|---|---|
| OAuth2 `client_secret` / `credential` (`<client_id>:<client_secret>`) | `secretPropertyList` (Catalog Credentials) | Iceberg `RESTCatalog`의 OAuth2 client_credentials grant로만 전송된다 |
| OAuth2 access token (`token`, 발급받은 Bearer token) | `secretPropertyList` 또는 runtime | Bearer token은 Iceberg client 내부에만 있다 |
| S3 secret key / access key | `secretPropertyList` (`fs.s3a.secret.key`, `fs.s3a.access.key`) | Hadoop conf로 S3 FileSystem에 전달된다 |
| S3 session token | `fs.s3a.session.token`, vended `s3.session-token` | vended credential은 Phase 4 범위다 (G-04) |

## 2. 경로별 처리

| 경로 | 처리 | 근거 |
|---|---|---|
| REST API GET (`/api/v3/catalog/{id}`, `/api/v3/source/{id}`, `/apiv2/source/{name}`) | secret은 `$DREMIO_EXISTING_VALUE$`로 masking. masked 값 그대로 PUT하면 기존 값 유지 | Phase 2 live gate #4/#5, Phase 3 D live L3/L4 |
| Source state 메시지 (`getState`) | `redactSecrets`: source config에 있는 secret 값과 그 `:` 분할 부분을 `****`로 바꾼다. 긴 값부터 치환하고, 4자 미만 값은 건너뛰며, 8자 미만 값은 앞뒤가 영숫자가 아닐 때만 치환한다 | unit (`TestRestCatalogHttpErrors`, `TestRestCatalogOAuth2`, `TestRestIcebergCatalogPluginConfig`) |
| Source state 메시지의 server 응답 부분 | redact → HTML tag 제거, 공백 정리, 300자 제한 → 다시 redact (Phase 3 integration). 자른 뒤에 secret 일부가 남지 않도록 자르기 전에 먼저 redact한다 | unit `testHtmlErrorPageIsStrippedFromTheMessage`, `testLongServerMessagesAreAbbreviated` |
| 실패 기록 cache (`RECENT_FAILURES`) | redact된 `SourceState`만 저장한다. 2분 후 만료, 성공하면 삭제 | Agent A |
| Endpoint 표시 | user info, query, fragment를 뺀 URI만 메시지에 넣는다 (`sanitizeEndpoint`) | unit |
| Hadoop conf | REST client 전용 key(`credential`, `token`, `scope`, `oauth2-server-uri`, `audience`, `resource`, `token-*`, `header.*`, `rest.auth.*`, `rest.client.*`, token-exchange type key)는 Hadoop conf로 복사하지 않는다. `rest.client.*`는 Phase 3 integration에서 추가했다 (secret은 아니지만 HTTP client 전용) | unit |
| `propertyList`(plain)에 secret key | UI validator가 저장을 막고 backend는 key 이름만 WARN한다 (G-13) | Phase 2 |
| Config 객체 | `RestIcebergCatalogPluginConfig.toString()`은 `Object.toString()`이다 (값 없음). `clearSecrets()` 후 JSON은 secret 4개 모두 `$DREMIO_EXISTING_VALUE$` | D unit #21/#22 |
| 동작 중 오류 (`RestCatalogExceptionMapper`) | Review 반영: plugin이 `redactSecrets`를 accessor와 mapper에 넘긴다. 401/403 permission error의 server message는 redact → 300자 제한 → redact. cause로 남기는 Iceberg 예외도 message에 secret이 있으면 redact된 같은 type의 예외(원래 stack trace)로 바꾼다. job profile과 server.log에 찍히는 cause chain에도 secret이 남지 않는다 | unit `testTableExists403/401IsRaisedWithoutSecrets`(server가 secret echo), `testServerMessageIsRedactedInMessageAndCause` |

## 3. Log 설정

- **HttpClient wire/headers logger.** `org.apache.hc.client5.http.wire`와 `org.apache.hc.client5.http.headers`를 DEBUG로 두면 `Authorization: Bearer <token>`과 token 요청 body의 `client_secret`이 그대로 찍힌다 (D의 임시 probe로 확인, 제출하지 않음).
  - `distribution/resources/src/main/resources/conf/logback.xml`이 두 logger를 INFO로 고정한다 (commit `ffd1cc1b2`). root level을 올려도 이 두 logger는 DEBUG가 되지 않는다.
  - Phase 3 integration에서 `distribution/resources`와 `distribution/server`를 다시 build해 tarball의 `conf/logback.xml`에 이 block이 들어간 것을 확인했다.
  - 주의: `distribution/server/target/dremio-community-*/dremio-community-*/` 압축 해제 디렉터리는 rebuild 후에도 `conf/logback.xml`이 갱신되지 않았다. Live 검증은 tarball(`*.tar.gz`)을 새로 풀어서 했다.
- **`BlockLogLevelTurboFilter`.** 기본 설정은 `com.dremio` 외의 DEBUG를 막는다. D는 live 검증에서 `org.apache.iceberg,DEBUG`와 `org.apache.hc.client5,DEBUG`를 추가해 1,458줄의 DEBUG log를 만들었고, 이때도 secret 노출은 0건이었다 (wire/headers는 INFO 유지).
- **`Property.toString()`.** `sabot/kernel`의 `com.dremio.exec.catalog.conf.Property`는 `toString()`에 value를 넣는다. `secretPropertyList`를 그대로 log에 넘기면 secret이 찍힌다. 현재 plugin code는 key 이름만 log에 남긴다. 새 log 문을 쓸 때 주의한다.
- **namespace listing 실패 로그.** Phase 3 integration에서 `NoSuchNamespaceException`/`ForbiddenException`은 stack trace 없는 WARN 한 줄로 바꿨다 (`Skipping namespace … : <server message>`, `Namespace … does not exist …`). Review 반영으로 `Skipping namespace`의 server message도 redact하고 300자로 줄인다 (unit `testListNamespaces403IsReportedWithoutSecrets`가 secret을 echo하는 403으로 확인). 그 밖의 예외는 기존대로 ERROR와 stack trace를 남긴다.
- **E2E harness.** `common.sh`의 `redact()`는 `S3_SECRET_KEY`, `AWS_SECRET_ACCESS_KEY`, `MINIO_SECRET_KEY`, `POLARIS_ROOT_SECRET`, `E2E_SRC_CREDENTIAL`, `SOURCE_CREDENTIAL`, `E2E_PRINCIPAL_SECRET`, `E2E_EXTRA_SECRET`와 `$WORK/principal-*.cred`의 secret을 가린다. `id:secret` 값은 secret 부분도 따로 가린다. `scan-secrets.sh`는 rotate된 `log/archive/*.gz`도 풀어서 센다 (Review 반영).

## 4. Secret 노출 검증 결과

### 4.1 Unit

`TestRestCatalogHttpErrors`(D)와 `TestRestCatalogOAuth2`(A)는 fake REST catalog server가 error message 안에 credential과 S3 key를 일부러 되돌려 보내게(echo) 해서 redaction을 실제로 확인한다.

- 검사 위치: state message, `SourceState.toString()`, 예외 chain의 message/`toString()`, DEBUG로 수집한 log event (`com.dremio.plugins.icebergcatalog`, `org.apache.iceberg`, `org.apache.hc.client5`. wire/headers는 INFO).
- 검사 값: client secret, S3 access key, S3 secret key, S3 session token, fake server가 발급한 access token, `Bearer`, `access_token`, `client_secret` marker.
- 결과: 모든 시나리오에서 0건. echo된 값은 `****`로 바뀌었다.
- A의 첫 scan에서 `client_secret` 46건이 나왔는데, 모두 hint 문구의 placeholder `<client_id>:<client_secret>`였다. 문구를 "client ID and client secret separated by ':'"로 바꾼 뒤 0건이 되었다.

### 4.2 Live

| 실행 | 대상 | 검사 값 / marker | 결과 |
|---|---|---|---|
| Harness smoke (INSTANCE=0) | Dremio `log/` 전체, Polaris container log | MinIO secret, Polaris root secret, `Bearer`, `access_token`, `s3.secret-access-key`, `s3.session-token`, `client_secret` | 0 |
| A (INSTANCE=1, DEBUG 포함) | 동일 | 위 항목 + 잘못 넣은 secret | 0 |
| B (INSTANCE=2) | 동일 | 위 항목 | 0 |
| C (INSTANCE=3) | 동일 | 위 항목 | 0 |
| D (INSTANCE=4, iceberg/hc DEBUG) | `server.log`, `server.out`, `json/server.json`, `queries.json`, `metadata_refresh.log`, `access.log`, Polaris `docker logs` | MinIO secret, Polaris root secret, read-only principal secret, 잘못 넣은 credential, `Bearer`/`Authorization`, `access_token`/`client_secret`, JWT `eyJ`, `s3.secret-access-key`/`s3.session-token`, `fs.s3a.secret.key=`/`fs.s3a.access.key=`/`credential=` | 0 |
| Integration 재검증 (INSTANCE=5, Phase 3 최종 tarball) | Dremio `log/` 전체, Polaris container log | MinIO secret, Polaris root secret, 잘못 넣은 secret, `reader`/`nolist` principal secret, `Bearer`, `access_token`, `s3.secret-access-key`, `s3.session-token`, `client_secret`, JWT `eyJ` | 0 ([phase3-oauth-catalog.md §5](phase3-oauth-catalog.md#5-integration-재검증-instance5) #19) |
| Review 반영 재검증 (INSTANCE=6) | Dremio `log/` 전체(.gz 포함), Polaris container log | MinIO secret, Polaris root secret, `Bearer`, `access_token`, `s3.secret-access-key`, `s3.session-token`, `client_secret` | 0 ([phase3-oauth-catalog.md §9](phase3-oauth-catalog.md#9-review-반영-instance6)) |

## 5. 재시도와 timeout (Iceberg REST client)

Dremio fork 1.7 `org.apache.iceberg.rest.HTTPClient`와 `ExponentialHttpRequestRetryStrategy`의 동작 (D가 bytecode와 unit test로 확인):

| 항목 | 동작 |
|---|---|
| 재시도 횟수 | `rest.client.max-retries`. 기본값 5 |
| 재시도하는 status | 429, 502, 503, 504. Method와 관계없이 재시도한다. Token `POST`도 포함된다 |
| 재시도하지 않는 I/O 예외 | `InterruptedIOException`(read/connect timeout 포함), `UnknownHostException`, `ConnectException`, `ConnectionClosedException`, `NoRouteToHostException`, `SSLException`. 그 밖의 I/O 예외는 idempotent method만 재시도한다 |
| 대기 시간 | `Retry-After`가 있으면 그 값(초 또는 HTTP-date)을 상한 없이 쓴다. 없으면 `1000ms × 2^(n-1)`(최대 64배)에 최대 10% jitter. 기본 5회면 약 31초 |
| timeout | `rest.client.connection-timeout-ms`, `rest.client.socket-timeout-ms`. 설정하지 않으면 HttpClient 기본값 3분 |

영향과 권장:
- 응답하지 않는 catalog는 요청 하나를 최대 3분 붙잡는다. Dremio의 source state check는 10초에서 끊기므로 state는 일반 메시지("Source is not currently available")가 된다. timeout을 설정하면 state에 "did not respond in time" hint가 나온다 (A live).
- 운영 권장값 (기본값으로 넣지는 않았다. 기존 generic RESTCATALOG 동작을 바꾸기 때문이다): `rest.client.connection-timeout-ms=10000`, `rest.client.socket-timeout-ms=60000`. 큰 `Retry-After`를 주는 server라면 `rest.client.max-retries`를 낮춘다.
- TLS handshake 실패는 요청을 보내기 전에 일어나므로 credential은 전송되지 않는다 (D unit).

## 6. 남은 위험과 다음 Phase

| 항목 | 내용 | 다음 단계 |
|---|---|---|
| G-12 at-rest 평문 | `secretPropertyList`는 KV store에 평문이다 (`encryptSecrets`는 `SecretRef` 전용). `RESTCatalog.properties()`에도 남는다 | kernel 범위 open item. Phase 6 known-limitations |
| 동작 중 오류의 redaction | 401/403 매핑과 namespace listing WARN은 redact한다 (Review 반영). 매핑하지 않는 오류(예: `tableExists` 500의 raw `ServiceFailureException`)와 DEBUG log의 stack trace는 server message를 그대로 담는다 | server가 secret을 echo하는 catalog가 있으면 매핑 범위를 넓힌다 |
| 실행 중 받은 값 | OAuth2 access token, vended S3 credential은 config에 없으므로 `redactSecrets` 대상이 아니다 | Phase 4 vended credential 구현 때 함께 다룬다 |
| G-27 단일 principal | 모든 Dremio 사용자가 source의 principal 하나로 Polaris에 접근한다 (`hasAccessPermission()`은 항상 true) | 최소 권한 principal 예시는 [phase3-oauth-catalog.md](phase3-oauth-catalog.md) §4.3. Phase 6 문서 |
| allowedNamespaces는 접근 제어가 아님 | 직접 경로로 SELECT/CREATE하면 allow list 밖도 접근된다 (C B-2) | Polaris grant로 권한을 제한한다. 강제 여부는 Phase 5/6 결정 |
| Vended credential | Phase 4 범위 | Phase 4에서 이 문서를 확장한다 |
