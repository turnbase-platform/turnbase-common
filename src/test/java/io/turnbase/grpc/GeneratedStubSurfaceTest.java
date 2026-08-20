package io.turnbase.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * IMP-M0-01's central <em>Done when</em> clause: the jar contains generated client and server stubs
 * for <em>every</em> service in {@code gamehost.proto}, under {@code io.turnbase.grpc}.
 *
 * <p>The expected services and RPCs are parsed out of the committed {@code .proto} rather than
 * listed here. A hard-coded list would assert that codegen produced what this test's author typed,
 * which stays green when someone adds a service to the contract and forgets everything downstream —
 * exactly the case worth catching. Parsing the source of truth means the contract growing is what
 * makes the test demand more.
 */
class GeneratedStubSurfaceTest {

  /** The committed copy; authoritative file lives in the parent repo (conventions.md §5). */
  private static final Path PROTO = Path.of("src/main/proto/turnbase/gamehost/v1/gamehost.proto");

  private static final String GENERATED_PACKAGE = "io.turnbase.grpc.gamehost.v1";

  private static final Pattern SERVICE =
      Pattern.compile("^service\\s+(\\w+)\\s*\\{", Pattern.MULTILINE);
  private static final Pattern RPC =
      Pattern.compile("^\\s*rpc\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

  private static String protoSource() throws IOException {
    assertThat(PROTO)
        .as("the committed proto copy; refresh it with the parent's scripts/sync-proto.sh")
        .exists();
    return Files.readString(PROTO, StandardCharsets.UTF_8);
  }

  /** Service names declared in the proto — the set every assertion below is driven by. */
  static List<String> declaredServices() throws IOException {
    List<String> services = SERVICE.matcher(protoSource()).results().map(m -> m.group(1)).toList();
    assertThat(services)
        .as("no service found in the proto — the parse is wrong, not the contract")
        .isNotEmpty();
    return services;
  }

  @Test
  @DisplayName("the proto declares exactly the two services the protocol document describes")
  void declaresBothServices() throws IOException {
    assertThat(declaredServices())
        .containsExactlyInAnyOrder("GameHostService", "GameHostRegistryService");
  }

  @ParameterizedTest(name = "{0} has a server base class")
  @MethodSource("declaredServices")
  @DisplayName("every service gets a server-side base class")
  void everyServiceHasServerStub(String service) {
    assertThatCode(
            () -> Class.forName(GENERATED_PACKAGE + "." + service + "Grpc$" + service + "ImplBase"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0} has blocking, async and future client stubs")
  @MethodSource("declaredServices")
  @DisplayName("every service gets all three client stub flavours")
  void everyServiceHasClientStubs(String service) throws Exception {
    Class<?> grpcClass = Class.forName(GENERATED_PACKAGE + "." + service + "Grpc");

    // A unary-only service gets all three; a service with a streaming RPC still gets
    // newStub, and grpc-java omits neither factory, so all three are required.
    assertThat(grpcClass.getMethod("newStub", io.grpc.Channel.class)).isNotNull();
    assertThat(grpcClass.getMethod("newBlockingStub", io.grpc.Channel.class)).isNotNull();
    assertThat(grpcClass.getMethod("newFutureStub", io.grpc.Channel.class)).isNotNull();
  }

  @ParameterizedTest(name = "{0} exposes every rpc declared for it")
  @MethodSource("declaredServices")
  @DisplayName("no RPC is silently dropped between the proto and the service descriptor")
  void everyRpcReachesTheServiceDescriptor(String service) throws Exception {
    String body = serviceBody(service);
    Set<String> declaredRpcs =
        RPC.matcher(body).results().map(m -> m.group(1)).collect(Collectors.toSet());
    assertThat(declaredRpcs).as("rpcs parsed from service %s", service).isNotEmpty();

    Class<?> grpcClass = Class.forName(GENERATED_PACKAGE + "." + service + "Grpc");
    ServiceDescriptor descriptor =
        (ServiceDescriptor) grpcClass.getMethod("getServiceDescriptor").invoke(null);

    Set<String> generatedRpcs =
        descriptor.getMethods().stream()
            .map(MethodDescriptor::getFullMethodName)
            // "turnbase.gamehost.v1.GameHostService/CreateSession" -> "CreateSession"
            .map(full -> full.substring(full.indexOf('/') + 1))
            .collect(Collectors.toSet());

    assertThat(generatedRpcs).containsExactlyInAnyOrderElementsOf(declaredRpcs);
  }

  @ParameterizedTest(name = "{0} keeps the proto package on the wire")
  @MethodSource("declaredServices")
  @DisplayName("the wire name is turnbase.gamehost.v1.*, independent of the Java package")
  void wireNameIsProtoPackageNotJavaPackage(String service) throws Exception {
    Class<?> grpcClass = Class.forName(GENERATED_PACKAGE + "." + service + "Grpc");
    ServiceDescriptor descriptor =
        (ServiceDescriptor) grpcClass.getMethod("getServiceDescriptor").invoke(null);

    // IMP-GATE-01 moved the Java package to io.turnbase.grpc.*. That must not have moved
    // the wire identity: a renamed service name is a breaking change for both peers, and
    // it would be invisible here because both sides regenerate from the same proto.
    assertThat(descriptor.getName()).isEqualTo("turnbase.gamehost.v1." + service);
  }

  @Test
  @DisplayName("generated types land under io.turnbase.grpc, per conventions.md §1")
  void generatedCodeIsUnderTheRepositoryRootPackage() throws Exception {
    Class<?> anyMessage = Class.forName(GENERATED_PACKAGE + ".ProcessResult");
    assertThat(anyMessage.getPackageName())
        .as("IMP-GATE-01: the root is io.turnbase.grpc, not com.turnbase")
        .startsWith("io.turnbase.grpc");
  }

  private static String serviceBody(String service) throws IOException {
    String source = protoSource();
    Matcher m = Pattern.compile("service\\s+" + service + "\\s*\\{").matcher(source);
    assertThat(m.find()).as("service %s present in the proto", service).isTrue();
    int depth = 0;
    int start = source.indexOf('{', m.start());
    for (int i = start; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(start + 1, i);
        }
      }
    }
    throw new AssertionError("unbalanced braces in service " + service);
  }
}
