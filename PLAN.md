# Release Promotion Plan

## Goal

Add a separate `promote` workflow that republishes already-built artifacts instead of rebuilding them, while leaving the existing `release` flow unchanged.

## Scope

1. Add new tasks:
   - `promote`
   - `promoteMaven`
   - `promoteDocker`
   - `verifyPromotionProvenance`
2. Add a small `promotion` DSL block for:
   - canonical Maven repository
   - target Maven repositories
   - canonical Docker image names
   - target Docker image names
3. Keep `release` and current publish tasks unchanged.

## Approach

### 1. Download existing artifacts from canonical sources

Do not trust local publications or local build outputs for promotion inputs.

Promotion should use the current project version as the version to promote and treat the canonical repositories as the source of truth.

For Maven:
- resolve the remote version directory for each artifact to be promoted
- download all artifact files found there
- include signatures and checksum sidecars such as `.asc`, `.md5`, `.sha1`, `.sha256`, `.sha512`
- avoid filtering too aggressively; promotion should preserve the remote artifact set as published

For Docker:
- resolve and pull/inspect the canonical multi-platform image tags that correspond to the current version
- treat the remote manifest and labels as the source of truth

### 2. Verify provenance before promotion

Promotion must be gated by git SHA, not just version.

Reuse existing provenance already present in:
- jar manifest entries
- generated `version.properties`
- Docker image revision annotation

Add git SHA into the published POM as well, so provenance can be checked directly from fetched Maven metadata.

`verifyPromotionProvenance` should fail unless:
- fetched POM git SHA matches current repo `HEAD`
- fetched jar manifest git SHA matches current repo `HEAD`
- source Docker image revision matches current repo `HEAD`

This phase happens after all canonical artifacts are downloaded and before any promotion begins.

### 3. Promote artifacts without rebuild

Implement custom promotion code that:
- downloads canonical Maven artifacts into a staging directory
- verifies provenance
- republishes the same bytes to target repositories

For Maven Central:
- create a deployment bundle from fetched files
- call the documented Central Publisher API
- poll status and publish or fail cleanly

For Reposilite:
- upload the same files using standard Maven repository layout and credentials

Do not regenerate jars, poms, signatures, or checksums during promotion.

### 4. Promote Docker images without rebuild

Use Docker manifest-copy style promotion rather than build/push.

Preferred command:

```sh
docker buildx imagetools create --tag <target-image:tag> <source-image:tag>
```

This preserves multi-platform images and avoids rebuilding per architecture.

### 4. Post-promotion steps

After promotion succeeds:
- update Docker Hub README content
- create the GitHub release
- bump version in the repo using the same post-release behavior already used by the existing workflow

This should behave like the current release tail, but run after promotion rather than after rebuild/publish.

### 5. End-to-end flow

The process should remain explicitly phased:

1. Download existing artifacts from canonical Maven and Docker sources
2. Validate all artifact provenance against current repo `HEAD`
3. Promote artifacts to target Maven and Docker destinations
4. Run post-promotion tasks:
   - update Docker Hub README
   - create GitHub release
   - bump version similar to the existing release workflow

### 6. Add focused test coverage

Add only the minimum tests that protect the non-trivial logic:
- unit tests for provenance extraction/verification
- functional test for the promote task graph
- integration tests using `MockWebServer` for Maven download/upload and Central API interactions

Skip live Central/Reposilite integration in plugin tests.

## Rollout

### Phase 1

Add:
- `promotion` DSL
- remote artifact download/staging
- POM git SHA metadata
- provenance verification task

### Phase 2

Add:
- `promoteMaven`
- `promoteDocker`
- `promote`

### Phase 3

Add post-promotion tasks, `MockWebServer` integration coverage, and README documentation after the core workflow is stable.
