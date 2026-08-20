/**
 * Hand-written internal support types for the Orchestration ↔ Game Host contract.
 *
 * <p>Empty by design today. {@code IMP-M0-01} publishes the generated stubs; the "internal models"
 * in its title are whatever later items find they need on both sides of the wire and cannot put in
 * the proto. Anything added here is <em>internal to the platform</em> — this library is explicitly
 * not a public contract, which is {@code turnbase-sdk} (ADR-0012).
 *
 * <p>This package exists now rather than later for one reason: it is the scope NullAway is
 * configured against. Generated stubs under {@code io.turnbase.grpc.gamehost.v1} are excluded,
 * because they will never carry JSpecify annotations and noise on code nobody wrote is the fastest
 * route to somebody muting the tool across all five repositories (<a
 * href="../../../../../../docs/decisions/0015-static-analysis-error-prone-nullaway.md">ADR-0015</a>).
 * Declaring {@code @NullMarked} here means the first hand-written class is checked on the day it
 * lands, instead of arriving alongside a build change someone has to remember to make.
 */
@NullMarked
package io.turnbase.grpc.internal;

import org.jspecify.annotations.NullMarked;
