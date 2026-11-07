# turnbase-common — CLAUDE.md

Internal shared library: generated gRPC stubs and internal models. Publishes
`io.github.turnbase-platform:turnbase-grpc`, consumed by `turnbase-platform` (client) and `turnbase-game-host`
(server).

**This file is additive** — corpus, registry and conventions live in the parent repository:

| | |
|---|---|
| Registry | `../docs/implementation/registry.md` — `IMP-M0-01` |
| Conventions | `../docs/implementation/conventions.md` — **normative**, especially §5 |
| Contract | `../docs/specs/internal-grpc/gamehost.proto` and `orchestration-gamehost-protocol.md` |

## The proto here is a copy

**`gamehost.proto` is authoritative in the parent repository**, at
`docs/specs/internal-grpc/gamehost.proto`. This repo holds a **committed copy** at
`src/main/proto/turnbase/gamehost/v1/gamehost.proto` (ADR-0012 as amended by IMP-GATE-02) — nothing
in this repo's build may read a path outside it, since CI checks out this repo alone.

**Editing the copy is a defect.** The change belongs in the parent repo's spec directory, and the
copy is refreshed from it with `scripts/sync-proto.sh` (run from the parent). A divergence here is
invisible — both sides compile, and the two services disagree on the wire.

Where `orchestration-gamehost-protocol.md`'s prose disagrees with the proto, **the proto wins for
wire shape** — but raise the disagreement rather than silently following one.

## Internal, not public

This is the one library that is explicitly *not* a public contract — that is `turnbase-sdk`. Nothing
outside `turnbase-platform` and `turnbase-game-host` may depend on it, and in particular the SDK must
not, transitively or otherwise.

Generated code lands under `io.turnbase.grpc`.

## Before you commit

```bash
./mvnw spotless:apply
./mvnw verify        # buf lint + buf breaking run in CI
```

**Almost everything here is generated**, so Error Prone and NullAway are scoped to hand-written code
only ([ADR-0015](../docs/decisions/0015-static-analysis-error-prone-nullaway.md)). Noise on generated
stubs is the fastest route to somebody muting the tool in all five repositories.

`git config core.hooksPath .githooks` once, to get the `pre-push` fast-fail.

**`buf breaking` is the real gate here.** Both consumers are pinned to an exact version of this
artifact (`conventions.md` §5), so a breaking change does not surface until someone bumps the
property in their own repo. That is the right place for it to surface — but only if `buf breaking`
told you it was breaking first.

Release order when the proto changes: **this repo merges and tags → CI publishes → each consumer
bumps `<turnbase-common.version>` in its own PR.** There is no atomic multi-repo commit.
