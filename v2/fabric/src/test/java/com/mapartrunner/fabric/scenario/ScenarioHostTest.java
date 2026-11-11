package com.mapartrunner.fabric.scenario;

import com.mapartrunner.fabric.scenario.threading.PacketHandlerAwait;
import com.mapartrunner.fabric.scenario.threading.RegistrationAcknowledgement;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.impl.client.gametest.screenshot.TestScreenshotOptionsImpl;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioHostTest {
    @Test void automaticClientAndServerCallbacksKeepResultsAndCallOrder() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var result = new Object();
        host.runOnClient(client -> { assertNull(client); nativeHost.calls.add("client action"); });
        assertSame(result, host.computeOnClient(client -> {
            assertNull(client); nativeHost.calls.add("client result"); return result;
        }));
        host.createWorld();
        host.runOnServer(server -> { assertNull(server); nativeHost.calls.add("server action"); });
        assertSame(result, host.computeOnServer(server -> {
            assertNull(server); nativeHost.calls.add("server result"); return result;
        }));
        assertEquals(List.of("ClientGameTestContext.runOnClient", "client action", "ClientGameTestContext.computeOnClient",
                "client result", "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getServer", "TestServerContext.runOnServer", "server action",
                "TestSingleplayerContext.getServer", "TestServerContext.computeOnServer", "server result"), nativeHost.calls);
    }

    @Test void automaticTickSaveScreenshotAndCloseUseOriginalNativeRoutes() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        host.createWorld();
        assertSame(nativeHost.saveDirectory, host.saveDirectory());
        host.waitTick();
        var directory = Path.of("bounded-evidence");
        assertSame(nativeHost.screenshot, host.takeScreenshot(directory, "before-reset"));
        assertSame(directory, nativeHost.options.destinationDir);
        assertEquals("before-reset", nativeHost.options.name);
        assertFalse(nativeHost.options.counterPrefix);
        assertEquals(1, nativeHost.options.tickDelta);
        assertNull(nativeHost.options.size);
        host.closeWorld();
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory", "ClientGameTestContext.waitTick",
                "ClientGameTestContext.takeScreenshot", "TestSingleplayerContext.close"), nativeHost.calls);
    }

    @Test void successfulCreationKeepsNativeHandleWhenFirstServerValidationThrows() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var failure = new AssertionError("first server validation");
        host.createWorld();
        assertSame(failure, assertThrows(AssertionError.class, () -> host.computeOnServer(server -> { throw failure; })));
        assertSame(nativeHost.saveDirectory, host.saveDirectory());
        host.closeWorld();
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getServer", "TestServerContext.computeOnServer",
                "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory", "TestSingleplayerContext.close"), nativeHost.calls);
    }

    @Test void throwingCreationDoesNotDispatchOrCloseAnUncreatedWorld() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var failure = new IllegalStateException("create failed");
        nativeHost.failureAt = "TestWorldBuilder.create";
        nativeHost.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, host::createWorld));
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create"), nativeHost.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clientRun", "clientCompute", "serverRun", "serverCompute"})
    void automaticCallbacksPropagateOriginalFailure(String route) {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        host.createWorld();
        for (Throwable failure : List.of(new IllegalStateException("callback"), new AssertionError("callback"))) {
            assertSame(failure, assertThrows(Throwable.class, () -> {
                switch (route) {
                    case "clientRun" -> host.runOnClient(client -> rethrow(failure));
                    case "clientCompute" -> host.computeOnClient(client -> { rethrow(failure); return null; });
                    case "serverRun" -> host.runOnServer(server -> rethrow(failure));
                    case "serverCompute" -> host.computeOnServer(server -> { rethrow(failure); return null; });
                    default -> throw new AssertionError(route);
                }
            }));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ClientGameTestContext.runOnClient", "ClientGameTestContext.computeOnClient",
            "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create", "TestSingleplayerContext.getServer",
            "TestServerContext.runOnServer", "TestServerContext.computeOnServer", "ClientGameTestContext.waitTick",
            "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory",
            "ClientGameTestContext.takeScreenshot", "TestSingleplayerContext.close"})
    void automaticTransportPropagatesOriginalFailure(String boundary) {
        for (Throwable failure : List.of(new IllegalStateException(boundary), new AssertionError(boundary))) {
            var nativeHost = new NativeContext();
            var host = ScenarioHost.automatic(nativeHost.client);
            if (!boundary.equals("ClientGameTestContext.worldBuilder") && !boundary.equals("TestWorldBuilder.create"))
                host.createWorld();
            nativeHost.failureAt = boundary;
            nativeHost.failure = failure;
            assertSame(failure, assertThrows(Throwable.class, () -> {
                switch (boundary) {
                    case "ClientGameTestContext.runOnClient" -> host.runOnClient(client -> fail("must not dispatch"));
                    case "ClientGameTestContext.computeOnClient" -> host.computeOnClient(client -> fail("must not dispatch"));
                    case "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create" -> host.createWorld();
                    case "TestSingleplayerContext.getServer", "TestServerContext.runOnServer" -> host.runOnServer(server -> fail("must not dispatch"));
                    case "TestServerContext.computeOnServer" -> host.computeOnServer(server -> fail("must not dispatch"));
                    case "ClientGameTestContext.waitTick" -> host.waitTick();
                    case "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory" -> host.saveDirectory();
                    case "ClientGameTestContext.takeScreenshot" -> host.takeScreenshot(Path.of("bounded-evidence"), "failure");
                    case "TestSingleplayerContext.close" -> host.closeWorld();
                    default -> throw new AssertionError(boundary);
                }
            }));
        }
    }

    @Test void fixtureHelpersHaveOneGametestOwnedOrigin() throws Exception {
        for (Class<?> helper : List.of(RegistrationAcknowledgement.class, PacketHandlerAwait.class, TraversalCapture.class)) {
            String resource = helper.getName().replace('.', '/') + ".class";
            var origins = Collections.list(helper.getClassLoader().getResources(resource));
            var loadedFrom = helper.getProtectionDomain().getCodeSource().getLocation();
            System.out.println(helper.getName() + " loaded=" + loadedFrom + "; resources=" + origins);
            assertEquals(1, origins.size(), helper.getName() + " must have exactly one class resource");
            assertTrue(loadedFrom.getPath().endsWith("/fabric/build/classes/java/gametest/"),
                    () -> helper.getName() + " must load from gametest output: " + loadedFrom);
            assertEquals(loadedFrom.toExternalForm() + resource, origins.getFirst().toExternalForm());
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }

    /** Native interface doubles only; every test calls the production automatic adapter. */
    private static final class NativeContext implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final Path saveDirectory = Path.of("native-save");
        final Path screenshot = Path.of("native-screenshot.png");
        final ClientGameTestContext client = proxy(ClientGameTestContext.class);
        final TestWorldBuilder builder = proxy(TestWorldBuilder.class);
        final TestSingleplayerContext world = proxy(TestSingleplayerContext.class);
        final TestServerContext server = proxy(TestServerContext.class);
        final TestWorldSave save = proxy(TestWorldSave.class);
        String failureAt;
        Throwable failure;
        TestScreenshotOptionsImpl options;

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this));
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            String boundary = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            calls.add(boundary);
            if (boundary.equals(failureAt)) throw failure;
            return switch (method.getName()) {
                case "runOnClient", "runOnServer" -> { ((FailableConsumer<?, ?>) arguments[0]).accept(null); yield null; }
                case "computeOnClient", "computeOnServer" -> ((FailableFunction<?, ?, ?>) arguments[0]).apply(null);
                case "worldBuilder" -> builder;
                case "create" -> world;
                case "getServer" -> server;
                case "getWorldSave" -> save;
                case "getSaveDirectory" -> saveDirectory;
                case "waitTick", "close" -> null;
                case "takeScreenshot" -> { options = assertInstanceOf(TestScreenshotOptionsImpl.class, arguments[0]); yield screenshot; }
                default -> throw new AssertionError("Unexpected native call: " + boundary);
            };
        }
    }
}
