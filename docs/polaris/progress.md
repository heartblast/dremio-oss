# Polaris RESTCATALOG 진행 현황

- Branch: `feature/polaris-restcatalog` (base: `799ccbda4 Release 26.0.5`)
- 작업 정의: [`docs/reference_docs/catalog-support/polaris-catalog-support.md`](../reference_docs/catalog-support/polaris-catalog-support.md)
- 상태 값: `PASS` / `FAIL` / `ENVIRONMENT_BLOCKED` / `NOT_SUPPORTED`. 진행 중인 항목은 `IN_PROGRESS`, 시작하지 않은 항목은 `NOT_STARTED`로 표시한다.
- 최종 갱신: 2026-10-03 (Phase 1 Integration)

## Phase 현황

| Phase | 내용 | 상태 | 주요 산출물 | Commit SHA |
|---|---|---|---|---|
| 1 | 구조 분석 / Gap Analysis | PASS (commit/push 대기) | [phase1-analysis.md](phase1-analysis.md), [compatibility-matrix.md](compatibility-matrix.md), progress.md | TBD |
| 2 | RESTCATALOG OSS Source 완성 | NOT_STARTED | `@SourceType`, `isUsingVendedCredentials`(tag 13), `restcatalog-layout.json`, Polaris preset, registration/round-trip test | TBD |
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
[ ] git diff/status 검토        (Lead)
[x] 문서 갱신                   (docs/polaris/ 3개 파일)
[ ] commit 완료                 (Lead)
[ ] origin push 완료            (Lead)
[ ] commit SHA 기록             (Lead)
```

Phase 1 결과:
- 분석: **PASS**
- Live probe: Polaris 1.1.0 client-probe와 Dremio tarball prototype 모두 **PASS**
- Dremio 내부 data path(S3/DremioFileIO) E2E: **TBD (Phase 4/5)**

## 완료 기준 현황

`[ ]`는 아직 충족하지 않았다는 뜻이다. 현재 상태와 근거를 함께 적는다.

| # | 기준 | 현재 상태 | 근거 / 다음 단계 |
|---|---|---|---|
| 1 | `[ ]` Dremio OSS에서 RESTCATALOG Source 노출 | FAIL | `@SourceType` 누락 (G-01). annotation을 붙인 prototype으로는 노출을 확인했다. Phase 2 |
| 2 | `[ ]` UI Source 생성 성공 | FAIL | G-01, G-02 (layout 없음). Phase 2 |
| 3 | `[ ]` REST API Source 생성 성공 | FAIL | 400 `An invalid value was found: RESTCATALOG`. prototype에서는 200. Phase 2 |
| 4 | `[ ]` Polaris OAuth2 인증 성공 | TBD (Phase 3) | client-probe와 prototype에서는 PASS (`scope=PRINCIPAL_ROLE:ALL` 필수) |
| 5 | `[ ]` Namespace 탐색 성공 | TBD (Phase 3) | prototype에서 `ns1` 노출 PASS |
| 6 | `[ ]` Table 탐색 성공 | TBD (Phase 3) | client-probe PASS |
| 7 | `[ ]` SELECT 성공 | TBD (Phase 5) | Dremio data path 미검증 (G-28) |
| 8 | `[ ]` CREATE TABLE 또는 CTAS 성공 | TBD (Phase 5) | client-probe에서 create와 staged create PASS |
| 9 | `[ ]` INSERT 성공 | TBD (Phase 5) | |
| 10 | `[ ]` Polaris + S3/MinIO 접근 성공 | TBD (Phase 4) | Polaris 서버 측 MinIO write는 PASS. Dremio 측(`fs.s3a.*`)은 미검증 |
| 11 | `[ ]` Source restart/reload 성공 | TBD (Phase 5) | |
| 12 | `[ ]` allowedNamespaces 동작 | TBD (Phase 3) | 코드는 존재 (`AbstractRestCatalogAccessor.java:145-156`) |
| 13 | `[ ]` secret masking 정상 | TBD (Phase 2) | prototype에서 API masking PASS. at-rest 암호화는 NOT_SUPPORTED (G-12) |
| 14 | `[ ]` Generic RESTCATALOG 회귀 없음 | TBD (Phase 2/5) | |
| 15 | `[ ]` 관련 unit/integration/E2E 검증 | TBD (Phase 2~5) | registration, layout, round-trip test 없음 (G-16) |
| 16 | `[ ]` 문서 완료 | IN_PROGRESS | Phase 1 문서 완료. Phase 6 문서 세트 남음 |
| 17 | `[ ]` 모든 Phase commit/push 완료 | IN_PROGRESS | Phase 1 commit 대기 |

## Gap 요약 (상세: [phase1-analysis.md §4](phase1-analysis.md#4-gap-목록))

| 심각도 | Gap |
|---|---|
| blocker | G-01 `@SourceType` 누락, G-02 layout 누락 |
| high | G-03 `isUsingVendedCredentials` 없음, G-04 vended runtime 미지원, G-05 Polaris `warehouse`/`scope` 필수, G-06 DROP VIEW 403, G-07 fs.s3a lazy 주입, G-08 MinIO endpoint scheme / requester-pays, G-28 data path 미검증 |
| medium | G-09 오류 매핑, G-10 비어 있지 않은 namespace, G-11 catalog lifecycle, G-12 secret at-rest/복사, G-13 propertyList 안의 secret, G-15 preset, G-16 test, G-22 LOCATION 403 |
| low | G-14, G-17~G-21, G-23~G-27 |

## 환경 메모

- **JDK와 build**
  - Build는 JDK 21, test는 JDK 11 toolchain을 쓴다.
  - Module 단위 build와 test는 `scripts/dev`로 한다.
  - `clean`과 `-Dmaven.test.skip`은 쓰지 않는다.
- **Background `dac/ui` Maven build**
  - Node 의존성 설치를 위해 진행 중이다.
  - 다른 `dac/ui` Maven build를 동시에 시작하지 않는다.
  - UI spec은 이 build가 끝난 뒤에 실행한다.
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
