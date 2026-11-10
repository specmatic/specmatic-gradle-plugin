# Release and Promotion Retry Matrix

Status reflects the retry behavior currently implemented in the plugin.

| Phase / task | Operation | Status | Current behavior and caveat |
|---|---|---|---|
| `promotionPreFlight` | `git remote update` | Implemented | Retries three times with 5s/10s backoff. |
| `promotionPreFlight` | Branch, dirty-worktree, and incoming/outgoing checks | Intentional no-retry | Deterministic validation failures should be fixed rather than retried. |
| `downloadPromotionMavenArtifacts` | HTTP artifact downloads | Implemented | Retries downloads and deletes partial files. Uses the existing global backoff; HTTP status handling is not yet differentiated between permanent and transient failures. |
| `downloadPromotionMavenArtifacts` | Per-download future wait | Implemented | Waits up to 600s, allowing the download retry budget to finish. |
| `verifyPromotionMavenArtifacts` | Checksums, POMs, and JAR manifests | Intentional no-retry | Local deterministic validation. |
| `promoteMaven` | HTTP artifact PUTs | Implemented | Retries up to the configured limit. HTTP client has 120s call/write timeouts. Backoff behavior should be verified against the current branch implementation. |
| `promoteMaven` | Existing Maven metadata GET | Implemented | Retries transport failures, 429, and 5xx three times with 5s/10s backoff; 404 means metadata is absent. |
| `promoteMaven` | Maven metadata PUTs | Partial | Uses the artifact upload retry path, but concurrent releases can still overwrite each other's merged metadata. |
| `promoteMaven` | Checksum sidecar PUTs | Implemented | Uses the artifact upload retry path and deterministic same-path content. |
| `promoteMaven` | Maven Central bundle upload | Pending | Delegated to `SonatypeCentralPortal`; no state-aware retry is added. Blind resubmission after an ambiguous timeout could duplicate a deployment. |
| `promoteMaven` | Maven Central deployment validation/polling | Partial | Delegated to the portal library; plugin does not add an outer retry/status policy. |
| `inspectPromotionDockerImages` | Docker `buildx imagetools inspect` | Implemented | Retries command failures three times with 5s/10s backoff; output is reset between attempts. |
| `promoteDocker` | Version-tag `docker buildx imagetools create` | Implemented | Three attempts with 5s/10s backoff. Ambiguous failures are not followed by target-digest verification. |
| `promoteDocker` | `latest` tag promotion | Implemented | Same retry behavior as version tags; `latest` remains mutable and is not digest-verified. |
| `updatePromotionDockerHubReadme` | Docker Hub login | Implemented | Retries transport failures, HTTP 5xx, and 429 responses three times with 5s/10s backoff. Authentication/configuration 4xx errors fail fast. |
| `updatePromotionDockerHubReadme` | Docker Hub README PATCH | Implemented | Same retry policy. Same-content PATCH is effectively idempotent. `Retry-After` is not currently honored. |
| `promotionCreateReleaseTag` | Remote `git ls-remote --tags` lookup | Implemented | Retries three times with 5s/10s backoff. |
| `promotionCreateReleaseTag` | Local annotated tag creation | Intentional no-retry | Existing tags fail immediately. No state-aware “already exists and points to expected commit” handling yet. |
| `promotionPostReleaseBump` | Edit, stage, and commit `gradle.properties` | Intentional no-retry | Rerunning blindly could create duplicate commits or encounter a dirty worktree. |
| `promotionGitPush` | Branch push | Implemented | Three attempts with 5s/10s backoff. Does not independently verify remote state after an ambiguous failure. |
| `promotionGitPush` | Tag push | Implemented | Three attempts with 5s/10s backoff. Same remote-state caveat as branch push. |
| `promotionCreateGithubRelease` | Find release by tag/name | Implemented | Retries GitHub read failures three times with 5s/10s backoff. |
| `promotionCreateGithubRelease` | Create GitHub release | Partial | A rerun can find an already-created release, but an ambiguous create failure is not explicitly followed by a lookup before another create attempt. |
| `promotionCreateGithubRelease` | Delete existing release assets | Partial | Existing assets are deleted, and deletion failures are retried only indirectly by retrying the asset upload cycle. Deletes are destructive and do not have dedicated 404/5xx handling. |
| `promotionCreateGithubRelease` | Upload binary assets | Implemented | Five attempts with 5s/10s/20s/40s backoff; matching assets are deleted before each attempt. |
| `promotionCreateGithubRelease` | Upload `.SHA256` assets | Implemented | Binary and checksum upload are retried as one pair, preventing a successful binary with a stale checksum. |
| `promotionCreateGithubRelease` | Publish/un-draft release | Pending | No retry or post-failure state check for the final release update. |
| `promotionPrepareGithubReleaseArtifacts` | Delete/copy local release artifacts | Intentional no-retry | Local filesystem operation; rerun is safe after correcting the local failure. |
| `promote` aggregate | Re-running the complete promotion | Pending | No aggregate orchestration for resuming from the last completed side effect; task-specific reruns are required. |

## Cross-cutting gaps

- `GitOperations.execGit` has a fixed 30-second process timeout. Slow Git operations can fail before task-level retry logic runs.
- HTTP retries should eventually classify failures consistently: transport errors, 429, and 5xx are generally retryable; authentication, validation, and most other 4xx responses are not.
- Maven Central upload and GitHub release creation need state checks after ambiguous failures before resubmission.
- Docker promotion should compare the target digest after an ambiguous `imagetools create` failure.

