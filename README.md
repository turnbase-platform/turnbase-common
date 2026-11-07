# turnbase-common

Internal shared library for TurnBase: generated gRPC stubs for the Orchestration Service ↔ Game Host
contract, plus internal models. Published as `io.turnbase:turnbase-grpc`.

**This is not a public contract.** The public Game Development Kit is
[`turnbase-sdk`](https://github.com/turnbase-platform/turnbase-sdk). Nothing outside
`turnbase-platform` and `turnbase-game-host` may depend on this artifact.

## Build

```bash
./mvnw verify                      # Spotless, Error Prone + NullAway, codegen, tests, coverage
./mvnw spotless:apply              # before opening a PR, not after CI rejects it
./mvnw install                     # to the local repository, for a consumer's -D override
buf lint                           # contract lint; buf breaking runs in CI
```

Toolchain is pinned in `.sdkmanrc` (`21.0.2-graalce`). Enable the local fast-fail once per clone:

```bash
git config core.hooksPath .githooks
```

## The proto is a copy, and editing it here is a defect

`src/main/proto/turnbase/gamehost/v1/gamehost.proto` is a **committed copy**. The authoritative file
is `docs/specs/internal-grpc/gamehost.proto` in the [parent
repository](https://github.com/turnbase-platform/turnbase), which refreshes this one:

```bash
scripts/sync-proto.sh          # in the parent repo
```

It is committed rather than copied during the build so that this repository builds from a standalone
clone — which is what its own CI checks out (ADR-0012, amended by IMP-GATE-02). The parent's
`pre-push` hook runs `scripts/sync-proto.sh --check`, because drift is otherwise silent: both
repositories keep compiling while the two services disagree on the wire.

## What is generated, and where

`option java_package = "io.turnbase.grpc.gamehost.v1"` — under this repository's root package
(`conventions.md` §1), with the proto's own package as the tail so a future `turnbase.gamehost.v2`
can sit beside v1 on one classpath.

The wire identity is `turnbase.gamehost.v1.*` and is independent of the Java package. A test asserts
that, because moving the Java package must never move the service name and both peers regenerate
from the same file, so nothing else would notice.

## Two things worth knowing before you change the build

- **Almost everything here is generated.** NullAway is scoped to `io.turnbase.grpc.internal` — the
  hand-written package — and the stubs are excluded. Noise on code nobody wrote is the fastest route
  to somebody muting the tool in all five repositories ([ADR-0015](../docs/decisions/0015-static-analysis-error-prone-nullaway.md)).
- **The coverage gate is currently inert**, and deliberately so: it measures the hand-written
  remainder, which is empty, so JaCoCo reports success over a bundle of zero classes. It becomes
  load-bearing on its own the moment a class lands under `io.turnbase.grpc.internal`. Do not lower
  the threshold or include the generated stubs to make the number look real.

## Releasing

`buf breaking` is the gate. Both consumers pin an exact version (`conventions.md` §5), so a breaking
change does not surface until someone bumps the property in their own repository — which is the right
place for it to surface, but only if `buf breaking` said it was breaking first.

Order when the proto changes: **this repo merges and tags → CI publishes → each consumer bumps
`<turnbase-common.version>` in its own PR.** There is no atomic multi-repo commit.

## Third-party licences

What this repository distributes is audited for its dependencies' licences (allowed families:
`docs/implementation/conventions.md` §6 *Third-party licences*). The report is generated, not committed:

```bash
./mvnw org.codehaus.mojo:license-maven-plugin:2.8.0:add-third-party -Dlicense.excludedScopes=test,provided
# -> target/generated-sources/license/THIRD-PARTY.txt
```
