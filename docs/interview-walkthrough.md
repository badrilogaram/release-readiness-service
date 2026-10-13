# ReleaseGuard interview walkthrough

## Introduce the problem

“I built ReleaseGuard as a personal reference project for post-deployment validation. A caller selects configured components and a validation window. The service runs bounded HTTP checks, keeps the results in a local database, and produces an Excel report. Optional email alerts make a failed run visible to a team.”

Keep the distinction clear: your professional deployment and modernization work provides context for the problem; this repository is a separate educational implementation with sample data. Do not describe it as a production deployment or a maintainer-reviewed open-source contribution.

## Five-minute demonstration

1. Start the service and show `GET /api/components` with the `X-API-Key` header. Explain that the request selects approved IDs rather than accepting target URLs.
2. Submit a six-second run for `catalog`, `payments`, and `identity`. Show the `202` acknowledgement and the run ID.
3. Poll `GET /api/runs/{id}`. Point out the lifecycle, individual attempts, response latency, and failed-check count.
4. Download the Excel report. Show the relationship between summary information and individual observations.
5. Set the demo `payments` component to `UP`, submit a fresh run, and compare the new result with the previous failure.
6. If using Compose, open Mailpit to show the captured failed-run notification. Explain that this is a local inbox.

Use the commands in the README. Keep the first walkthrough short; explore `SLOW`, `FLAKY`, cancellation, or restart recovery only when relevant to the conversation.

## Explain the engineering choices

### Why an asynchronous API?

“A validation window takes longer than a normal request-response operation. The API acknowledges acceptance and returns a resource the caller can poll. The final report comes from persisted observations.”

Discuss what `202` guarantees and what it does not: acceptance does not mean the deployment has passed.

### Why virtual threads and explicit limits?

“The workload waits on HTTP. Virtual threads let me express that flow with blocking code, while admission limits, timeouts, a maximum window, and a maximum planned-check count bound resource use.”

Do not claim a throughput or memory improvement without a benchmark. Explain how you would measure it: fixed target behavior, equivalent workloads, controlled concurrency, latency percentiles, errors, and resource usage.

### How do retries affect correctness?

“I retry selected temporary HTTP and transport failures within limits and record the attempt count. That distinguishes a clean response from recovery after a retry. Success depends on the expected status and response content, with a timeout for each attempt. I record latency but do not claim a separate latency SLO.”

Be ready to discuss why retrying indefinitely can amplify an outage, how delayed responses affect a sampling window, and why a health check should be a safe operation to repeat.

### What happens when the application stops?

“History is persistent, but worker execution is in-process. On restart I mark unfinished runs aborted rather than silently reporting success or pretending execution continued. Resuming a durable workflow would require a different design.”

Discuss the boundary between persisted state and a durable work queue, including cancellation and race conditions.

### Why is target selection configured?

“The start-run request accepts component IDs, not URLs. Operators configure the destinations, and the HTTP client does not follow redirects. That limits request-driven access to arbitrary destinations.”

Do not call this complete SSRF protection. Explain the remaining need for trusted configuration, credential handling, network controls, and authorization before broader deployment.

### How reliable are email alerts?

“Validation results are saved independently of notification outcome. Email is optional, and the project records its outcome. There is no transactional outbox, so I would not claim exactly-once delivery across process failure.”

An extension could write an outbox event in the result transaction and have a separate dispatcher retry delivery with idempotency controls. Present this as a proposed improvement.

### Why H2?

“It keeps the project easy to run while demonstrating persisted history and restart behavior. It is a single-instance choice. A shared production service would need database and concurrency decisions that match its reliability requirements.”

## Walk through verification

Run the project's Maven verification command and open the generated test and JaCoCo reports. Describe tests that actually exist in the repository, and show a relevant assertion before claiming a behavior is covered.

Useful behaviors to discuss include input bounds, target allowlisting, temporary-failure retries, cancellation, persistence, restart recovery, report contents, and notification behavior. If a behavior lacks a test, identify that gap rather than implying complete coverage.

The CI workflow automates verification and report collection. It does not deploy the application or push repository changes.

## Describe the project accurately

A suitable portfolio description after reviewing the code and running it yourself:

> Built ReleaseGuard, a Java 21 and Spring Boot reference service for automated deployment validation, with bounded HTTP checks, persistent run history, Excel reports, optional SMTP alerts, and a repeatable local failure-and-recovery demo.

Only say you independently designed or implemented the parts you actually did. Be transparent about AI assistance when asked, and be able to explain the code, tests, tradeoffs, and changes you made. Do not present generated code as evidence of professional experience you have not had.

To make an open-source contribution later, identify a real upstream issue, reproduce it, discuss scope with maintainers when appropriate, add a focused fix and tests, and submit it for their review. Creating this personal project and having an upstream change accepted are different achievements.
