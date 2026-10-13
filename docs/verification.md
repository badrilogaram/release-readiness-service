# Local verification

Verified on **9 October 2026** using Java 21.0.12.1, Apache Maven 3.10.0, and Spring Boot 4.1.1 on Windows.

## Build and automated tests

`mvn -B clean verify` completed successfully using the local Maven dependency cache: **52 tests, 0 failures, 0 errors, 0 skipped**. The build produced an executable Spring Boot JAR and a JaCoCo report.

| Test area | Passing cases |
| --- | ---: |
| Component checks and retry rules | 7 |
| Evidence queries, bounds, and redaction | 5 |
| HTTP deadlines, response limits, and redirects | 4 |
| Configuration validation | 12 |
| Alert configuration and notification behaviour | 7 |
| Complete HTTP API integration | 3 |
| Excel report content and literal-cell handling | 3 |
| Run cancellation, shutdown, and admission control | 2 |
| Persistence, interrupted-run recovery, and retention | 9 |

Tests use local HTTP endpoints, isolated H2 databases, and a mocked mail sender. Mockito loads through its startup Java agent, avoiding dynamic attachment during tests. JaCoCo recorded 451 of 510 lines and 209 of 296 branches covered; these figures describe this suite, not a reliability guarantee.

## Packaged application demo

The executable JAR was started on an available localhost port with a separate file-backed H2 database and original sample data. Alerts and Elasticsearch were disabled.

| Check | Observed result |
| --- | --- |
| Public application health | `UP` |
| API and metrics without an API key | `401` |
| Initial three-component validation | `FAILED`: 6 observations, 2 failed payments observations, each exhausting 3 attempts |
| Failure evidence | Synthetic diagnostic logs persisted with failed observations |
| Downloaded Excel report | Valid `Summary` and `Checks` sheets, all 6 detail rows, matching counts, no formula cells |
| Fresh validation after setting payments to `UP` | `PASSED`: 6 observations, 0 failures |
| Intermittently failing payments | `PASSED`: both observations recovered on attempt 2 |
| Cancellation | `CANCELLED` |
| Authenticated Prometheus endpoint | Check, completed-run, and active-run metrics present |

The smoke-test application was stopped afterward. No external emails were sent and no external application components were probed.

## Scope of verification

Docker/Compose was reviewed but not executed because Docker was unavailable. Mailpit delivery and a real Elasticsearch cluster have not been tested; automated tests cover the mail message/attachment and Elasticsearch HTTP request contracts. The local verification recorded here did not include a remote GitHub Actions run; check the repository's Actions tab for subsequent CI results.

The official Maven Wrapper 3.3.4 scripts are included with a SHA-256 checksum for Maven 3.10.0. Its first-download setup was blocked by a temporary-directory rename restriction in the local sandbox; the successful build used installed Maven. The runnable JAR does not require Maven.

This verification does not include load testing, multi-instance operation, or production deployment.
