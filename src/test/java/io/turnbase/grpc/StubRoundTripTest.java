package io.turnbase.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.HeartbeatRequest;
import io.turnbase.grpc.gamehost.v1.HeartbeatResponse;
import io.turnbase.grpc.gamehost.v1.LogEffect;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.Team;
import io.turnbase.grpc.gamehost.v1.TerminationReason;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stubs compile is a weaker claim than the stubs work. This wires a real server and a real
 * channel in process and calls across them, which is what proves the descriptors, marshallers and
 * generated builders actually agree.
 *
 * <p>In-process transport, deliberately: it exercises the full stub and marshalling path with no
 * port, no TLS and no container, so it belongs in the unit tier (conventions.md §3) rather than
 * behind {@code -Pintegration}.
 *
 * <p>The server implementations here are stand-ins. This repository publishes the contract; the
 * behaviour behind it is IMP-M3-04 in {@code turnbase-game-host}. What is asserted is that a value
 * put into a request arrives at the server unchanged, and a value put into a response arrives back
 * unchanged.
 */
class StubRoundTripTest {

  private static final String SESSION_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
  private static final String INSTANCE_ID = "game-host-0";

  private Server server;
  private ManagedChannel channel;

  @BeforeEach
  void startServer() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(new StandInGameHost())
            .addService(new StandInRegistry())
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterEach
  void stopServer() throws Exception {
    channel.shutdownNow();
    server.shutdownNow();
    server.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("CreateSession carries the roster and returns the initial effects")
  void createSessionRoundTrip() {
    var stub = GameHostServiceGrpc.newBlockingStub(channel);

    CreateSessionResponse response =
        stub.createSession(
            CreateSessionRequest.newBuilder()
                .setSessionId(SESSION_ID)
                .addTeams(
                    Team.newBuilder()
                        .setTeamId("team-1")
                        .setName("Attacker 1")
                        .addPlayers(PlayerRef.newBuilder().setPlayerId("p1").setDisplayName("Ada")))
                .setParameters(ByteString.copyFromUtf8("{\"rounds\":3}"))
                .build());

    assertThat(response.getResult().getSessionId()).isEqualTo(SESSION_ID);
    assertThat(response.getResult().getStatus()).isEqualTo(ProcessStatus.PROCESS_STATUS_OK);
    assertThat(response.getResult().getEffectsList()).hasSize(1);
    assertThat(response.getResult().getEffects(0).getLog().getEntry()).isEqualTo("Attacker 1");
  }

  @Test
  @DisplayName("ProcessInput echoes the sequence, which is what the delivery watermark keys on")
  void processInputEchoesSequence() {
    var stub = GameHostServiceGrpc.newBlockingStub(channel);

    ProcessInputResponse response =
        stub.processInput(
            ProcessInputRequest.newBuilder().setSessionId(SESSION_ID).setSequence(42L).build());

    assertThat(response.getResult().getSequence()).isEqualTo(42L);
  }

  @Test
  @DisplayName(
      "a reserved enum value stays absent, so a future wire value cannot silently reuse it")
  void reservedTerminationReasonIsNotGenerated() {
    // The proto reserves 1 (was HOST_ENDED, ADR-0010). Reserved rather than reused so that
    // an old peer's 1 never decodes as a new meaning. If someone reuses the number, this
    // test is what notices: the enum gains a constant whose number is 1.
    assertThat(TerminationReason.values())
        .noneMatch(r -> r != TerminationReason.UNRECOGNIZED && r.getNumber() == 1);
    assertThat(TerminationReason.TERMINATION_REASON_ROOM_CLOSED.getNumber()).isEqualTo(2);
  }

  @Test
  @DisplayName("Heartbeat is bidirectional and the drain instruction comes back on the stream")
  void heartbeatStreamsBothWays() throws Exception {
    var stub = GameHostRegistryServiceGrpc.newStub(channel);
    List<HeartbeatResponse> received = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(1);

    StreamObserver<HeartbeatRequest> requests =
        stub.heartbeat(
            new StreamObserver<>() {
              @Override
              public void onNext(HeartbeatResponse value) {
                received.add(value);
              }

              @Override
              public void onError(Throwable t) {
                done.countDown();
              }

              @Override
              public void onCompleted() {
                done.countDown();
              }
            });

    requests.onNext(HeartbeatRequest.newBuilder().setInstanceId(INSTANCE_ID).build());
    requests.onNext(
        HeartbeatRequest.newBuilder().setInstanceId(INSTANCE_ID).setActiveSessions(501).build());
    requests.onCompleted();

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(received).hasSize(2);
    assertThat(received.get(0).getDrain()).isFalse();
    // The stand-in drains above 500 active sessions; the point is that a response
    // produced mid-stream reaches the client, not the threshold itself.
    assertThat(received.get(1).getDrain()).isTrue();
  }

  @Test
  @DisplayName("Register returns the lease parameters the Game Host needs to schedule heartbeats")
  void registerRoundTrip() {
    var stub = GameHostRegistryServiceGrpc.newBlockingStub(channel);

    RegisterResponse response =
        stub.register(
            RegisterRequest.newBuilder()
                .setInstanceId(INSTANCE_ID)
                .setAddress("10.1.2.3:9090")
                .addRuntimeTypes("jvm")
                .build());

    assertThat(response.getHeartbeatIntervalSeconds()).isEqualTo(5);
    assertThat(response.getLeaseTtlSeconds()).isEqualTo(15);
  }

  /** Echoes enough of the request back to prove the round trip; behaviour is IMP-M3-04. */
  private static final class StandInGameHost extends GameHostServiceGrpc.GameHostServiceImplBase {

    @Override
    public void createSession(
        CreateSessionRequest request, StreamObserver<CreateSessionResponse> observer) {
      String teamName = request.getTeamsCount() > 0 ? request.getTeams(0).getName() : "";
      observer.onNext(
          CreateSessionResponse.newBuilder()
              .setResult(
                  ProcessResult.newBuilder()
                      .setSessionId(request.getSessionId())
                      .setStatus(ProcessStatus.PROCESS_STATUS_OK)
                      .addEffects(
                          Effect.newBuilder().setLog(LogEffect.newBuilder().setEntry(teamName))))
              .build());
      observer.onCompleted();
    }

    @Override
    public void processInput(
        ProcessInputRequest request, StreamObserver<ProcessInputResponse> observer) {
      observer.onNext(
          ProcessInputResponse.newBuilder()
              .setResult(
                  ProcessResult.newBuilder()
                      .setSessionId(request.getSessionId())
                      .setSequence(request.getSequence())
                      .setStatus(ProcessStatus.PROCESS_STATUS_OK))
              .build());
      observer.onCompleted();
    }
  }

  /** Stand-in registry; the drain threshold is arbitrary and local to this test. */
  private static final class StandInRegistry
      extends GameHostRegistryServiceGrpc.GameHostRegistryServiceImplBase {

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> observer) {
      observer.onNext(
          RegisterResponse.newBuilder()
              .setHeartbeatIntervalSeconds(5)
              .setLeaseTtlSeconds(15)
              .build());
      observer.onCompleted();
    }

    @Override
    public StreamObserver<HeartbeatRequest> heartbeat(StreamObserver<HeartbeatResponse> responses) {
      return new StreamObserver<>() {
        @Override
        public void onNext(HeartbeatRequest request) {
          responses.onNext(
              HeartbeatResponse.newBuilder().setDrain(request.getActiveSessions() > 500).build());
        }

        @Override
        public void onError(Throwable t) {
          responses.onError(t);
        }

        @Override
        public void onCompleted() {
          responses.onCompleted();
        }
      };
    }
  }
}
