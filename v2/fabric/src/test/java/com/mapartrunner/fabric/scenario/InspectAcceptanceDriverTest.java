package com.mapartrunner.fabric.scenario;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import com.mapartrunner.core.run.BuildEngine.ControlOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static com.mapartrunner.fabric.scenario.ScenarioCommands.*;
import static com.mapartrunner.fabric.scenario.ScenarioRunner.*;
import static com.mapartrunner.fabric.scenario.ScenarioHost.Command;
import static org.junit.jupiter.api.Assertions.*;

/** Simulated input/clock policy checks. These do not establish native keyboard or gameplay acceptance. */
class InspectAcceptanceDriverTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrapN1SourceFixtures() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize();
    }
    @Test void foreignCodecErrorsAndOutputFailureCannotProduceAnAcknowledgement() {
        assertEquals(1,InspectAcceptanceDriver.checkedEncode(com.mojang.serialization.Codec.intRange(0,1),com.mojang.serialization.JsonOps.INSTANCE,1).getAsInt());
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.checkedEncode(com.mojang.serialization.Codec.intRange(0,1),com.mojang.serialization.JsonOps.INSTANCE,2));
        foreign(f -> {
            f.flushFailure=true; f.start(); f.until(() -> f.input.stops==1);
            assertEquals(28,f.reads); assertEquals(1,f.flushes); assertEquals(0,f.sealAttempts); assertNull(f.ack);
            assertEquals("SEAL",f.proofRows().getLast().get("kind").getAsString());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("stdout failure")));
        });
    }

    @Test void foreignFailureRetiresOutstandingReadOnceAndRetainsPrimaryAndSecondary() throws Exception {
        foreign(f -> {
            f.start(); f.until(() -> f.reads==1); var request=f.lastRead;
            f.retireFailure=true; f.input.refusal="binding primary"; f.tick();
            assertTrue(request.retired.get()); assertEquals(1,f.retireCalls); assertEquals(1,f.input.stops);
            try {
                var field=InspectAcceptanceDriver.class.getDeclaredField("driverFailure"); field.setAccessible(true); var failure=(Throwable)field.get(f.driver);
                assertEquals("binding primary",failure.getMessage()); assertEquals("retire secondary",failure.getSuppressed()[0].getMessage());
            } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
            f.tick(3); assertEquals(1,f.retireCalls); assertEquals(1,f.reads);
        });
    }

    @Test void foreignClockAdmissionKeepsOneFrozenRequestAndRequiresIndependentServerProgress() {
        var previous=foreignSample(1,true);
        var request=new InspectAcceptanceDriver.ForeignRead(2,100,"frozen-client",previous);
        request.probeEntry(); assertFalse(request.admit(new InspectAcceptanceDriver.ForeignClock(1,9_007_199_254_740_994L,1)));
        assertFalse(request.requeue(),"Pending must first be consumed on END");
        assertNull(request.consume(2,2)); assertTrue(request.requeue()); assertFalse(request.requeue(),"No overlapping queue callbacks");
        request.probeEntry(); assertThrows(IllegalStateException.class,request::probeEntry);
        assertTrue(request.admit(new InspectAcceptanceDriver.ForeignClock(2,9_007_199_254_740_995L,2)));
        request.complete(foreignSample(2,true)); assertEquals(foreignSample(2,true),request.consume(2,99));
        assertEquals("frozen-client",request.client); assertEquals(2,request.ordinal); assertEquals(100,request.deadline);
        assertFalse(request.requeue()); assertThrows(IllegalStateException.class,request::probeEntry);
        var regression=new InspectAcceptanceDriver.ForeignRead(2,100,"same",previous);
        regression.probeEntry(); assertFalse(regression.admit(new InspectAcceptanceDriver.ForeignClock(2,9_007_199_254_740_995L,1)));
        regression.consume(2,1); assertTrue(regression.requeue()); regression.probeEntry();
        assertThrows(IllegalStateException.class,() -> regression.admit(new InspectAcceptanceDriver.ForeignClock(1,9_007_199_254_740_995L,2)),"Across-pending age regression remains failure even above old baseline");
        var initial=new InspectAcceptanceDriver.ForeignRead(1,100,"initial"); initial.probeEntry();
        assertTrue(initial.admit(new InspectAcceptanceDriver.ForeignClock(0,0,0)),"B0 has no synthetic predecessor");
    }

    @Test void foreignPendingClockDeliveryDoesNotRecaptureClientOrAddPairsAndFinalExpiryFails() {
        foreign(f -> {
            f.clockPendingOrdinal=4; f.clockPendingRemaining=3; f.start(); f.until(() -> f.ack!=null);
            assertEquals(28,f.reads); assertEquals(28,f.fullCaptures); assertEquals(3,f.clockRequeues);
            assertEquals(28,f.proofRows().stream().filter(r -> r.get("kind").getAsString().equals("SNAPSHOT") && r.get("side").getAsString().equals("client")).count());
        });
        foreign(f -> {
            f.clockPendingOrdinal=28; f.clockPendingRemaining=100; f.start(); f.until(() -> f.reads==28);
            f.tick(); f.now.set(f.pending.cleanupDeadlineNanos()); f.tick();
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("deadline expired")),f.logs.toString());
            assertEquals(28,f.reads); assertEquals(27,f.fullCaptures); assertNull(f.ack); assertEquals(0,f.flushes);
            assertTrue(f.proofRows().stream().noneMatch(r -> r.get("kind").getAsString().equals("SEAL")));
        });
    }

    @Test void foreignNativeButtonSelectionUsesActualVisibleEnabledLabelAndOneIdentity() {
        var button=net.minecraft.client.gui.widget.ButtonWidget.builder(net.minecraft.text.Text.literal("native create"),ignored -> {}).build();
        assertSame(button,InspectAcceptanceDriver.uniqueButton(button,"native create"));
        button.active=false; assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.uniqueButton(button,"native create"));
        button.active=true; button.visible=false; assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.uniqueButton(button,"native create"));
        button.visible=true; assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.uniqueButton(button,"wrong label"));
    }

    @Test void foreignInventorySchemaChecksActualNativeMappingAndCompleteCheckedCellUnion() {
        var equipment=new HashMap<Integer,net.minecraft.entity.EquipmentSlot>(); equipment.putAll(net.minecraft.entity.player.PlayerInventory.EQUIPMENT_SLOTS);
        InspectAcceptanceDriver.requireForeignInventorySchema(43,36,9,equipment);
        var changed=new HashMap<>(equipment); changed.put(42,net.minecraft.entity.EquipmentSlot.MAINHAND);
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.requireForeignInventorySchema(43,36,9,changed));
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.requireForeignInventorySchema(42,36,9,equipment));
        var definition=ScenarioDefinition.load(); var cells=InspectAcceptanceDriver.foreignCells(definition);
        var expected=new HashSet<net.minecraft.util.math.BlockPos>();
        definition.worldCells().keySet().forEach(p -> expected.add(new net.minecraft.util.math.BlockPos(p.x(),p.y(),p.z())));
        var origin=definition.origin(); definition.expectedTargets().keySet().forEach(p -> expected.add(new net.minecraft.util.math.BlockPos(
                Math.addExact(origin.x(),p.x()),Math.addExact(origin.y(),p.y()),Math.addExact(origin.z(),p.z()))));
        assertEquals(expected,new HashSet<>(cells)); assertEquals(expected.size(),cells.size());
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.checkedForeignChunk(null,0,0,new net.minecraft.util.math.BlockPos(0,64,0)));
    }

    @Test void foreignFixedProgramOwnSide28PairsAndSameFrozenClaimsSurviveContentionWithoutRemeasurement() {
        foreign(f -> {
            f.start(); f.until(() -> f.ack!=null);
            assertEquals(List.of("/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest rerun grounded-two-lane",
                    "/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest rerun grounded-two-lane","/maparttest last"),f.input.commands);
            assertEquals(List.of("exit","create:before-show","create:after-show","create:before-press","bind","settle","settle"),f.nativeSteps);
            assertEquals(28,f.reads); assertEquals(3,f.releaseAttempts); assertEquals(3,f.replayAttempts); assertEquals(3,f.sealAttempts);
            assertEquals(1,f.flushes); assertEquals(0,f.input.stops); assertTrue(f.logs.stream().noneMatch(s -> s.contains("DRIVER_FAIL")),f.logs.toString());
            var rows=f.proofRows(); assertEquals(1,rows.stream().filter(r -> r.get("kind").getAsString().equals("SEAL")).count());
            assertEquals(InspectAcceptanceDriver.PAIR_LABELS,rows.stream().filter(r -> r.get("kind").getAsString().equals("SNAPSHOT")
                    && r.get("side").getAsString().equals("client") && r.get("chunk").getAsInt()==0).map(r -> r.get("pair").getAsString()).toList());
            assertEquals(4,rows.stream().filter(r -> r.get("kind").getAsString().equals("CHAT_REQUESTED")).count());
            int count=f.logs.size(); f.tick(10); assertEquals(count,f.logs.size()); assertEquals(28,f.reads); assertEquals(0,f.input.stops);
        });
    }

    @Test void foreignPendingClaimChangesFailWithoutNewMeasurementOrOrdinal() {
        for(var point:List.of("release","P","F")) for(var mutation:List.of("request","deadline","stage","binding","stop","case","expiry")) foreign(f -> {
            f.start();
            f.until(() -> point.equals("release")?f.releaseAttempts==1:point.equals("P")?f.replayAttempts==1:f.sealAttempts==1);
            int reads=f.reads,commands=f.input.commands.size();
            var old=f.pending;
            if(mutation.equals("binding")) f.input.refusal="foreign binding replaced";
            else if(mutation.equals("stop")) f.driver.releaseOnStopping(null);
            else if(mutation.equals("case")) System.setProperty("mapart.test.inspect.acceptance.case","baseline");
            else if(mutation.equals("expiry")) f.now.set(point.equals("F")?old.cleanupDeadlineNanos():old.workDeadlineNanos());
            else f.pending=new ForeignObservation(old.sessionId(),old.operationId(),old.ordinal(),mutation.equals("stage")?"ABANDONED":old.stage(),
                    old.workDeadlineNanos(),old.queueEntered(),old.replayRejected(),old.cancelled(),old.boundaryRetired(),
                    mutation.equals("request")?old.requestOrdinal()+1:old.requestOrdinal(),mutation.equals("deadline")?old.cleanupDeadlineNanos()+1:old.cleanupDeadlineNanos(),old.guards(),old.finalFacts(),old.seal());
            f.tick(); assertTrue(f.logs.stream().anyMatch(s -> s.contains("DRIVER_FAIL")),point+"/"+mutation+f.logs);
            assertEquals(reads,f.reads); assertEquals(commands,f.input.commands.size()); assertNull(f.ack);
        });
    }

    private static void foreign(java.util.function.Consumer<ForeignFixture> test) {
        var previous=System.getProperty("mapart.test.inspect.acceptance.case");
        try { System.setProperty("mapart.test.inspect.acceptance.case","foreign-world"); test.accept(new ForeignFixture()); }
        finally { if(previous==null) System.clearProperty("mapart.test.inspect.acceptance.case"); else System.setProperty("mapart.test.inspect.acceptance.case",previous); }
    }

    /** Partial native/claim doubles execute the production finite controller, never native authority. */
    private static final class ForeignFixture implements InspectAcceptanceDriver.ForeignClaims {
        final AtomicLong now=new AtomicLong(1);
        final List<String> logs=new ArrayList<>(),nativeSteps=new ArrayList<>();
        final UUID session=UUID.randomUUID(),operation=UUID.randomUUID();
        final PublishedCompletion reset=new PublishedCompletion(session,UUID.randomUUID(),1,Command.RESET,Optional.empty(),"PASS",true,"RELEASED",true,"reset","reset/summary",Optional.empty());
        final PublishedCompletion run=new PublishedCompletion(session,UUID.randomUUID(),2,Command.RUN,Optional.of(UUID.randomUUID()),"PASS",true,"RELEASED",true,"run","run/summary",Optional.empty());
        InspectObservation observation=idle(null); ForeignObservation pending;
        int reads,settles,releaseAttempts,replayAttempts,sealAttempts,flushes,fullCaptures,clockPendingOrdinal,clockPendingRemaining,clockRequeues;
        ForeignProofAck candidate,ack;
        boolean flushFailure,retireFailure;
        int retireCalls;
        InspectAcceptanceDriver.ForeignRead lastRead;
        final SimulatedChat input=new SimulatedChat() {
            @Override public void key(int code,boolean press,Object screen) {
                super.key(code,press,screen);
                if(code==84 && press) { this.screen=chat; draft=""; }
            }
            @Override public void exitForeign() {
                nativeSteps.add("exit"); observation=new InspectObservation(false,true,true,Optional.of(run)); pending=projection("CANCELLED_HOLD",false,false,0,0);
            }
            @Override public boolean createForeign(Runnable revalidate,UUID expected) {
                assertEquals(session,expected); nativeSteps.add("create:before-show"); revalidate.run(); nativeSteps.add("create:after-show"); revalidate.run(); nativeSteps.add("create:before-press"); return true;
            }
            @Override public boolean bindForeign() { nativeSteps.add("bind"); return true; }
            @Override public boolean settleForeign() { nativeSteps.add("settle"); return ++settles==2; }
            @Override public boolean foreignClockAdvanced(String previous) { return true; }
            @Override public InspectAcceptanceDriver.ForeignRead readForeign(long ordinal,long deadline,String previousServer,ForeignObservation expected) {
                assertEquals(pending,expected);
                reads++; var read=new InspectAcceptanceDriver.ForeignRead(ordinal,deadline,foreignSample(ordinal,false),previousServer); lastRead=read;
                if(ordinal==clockPendingOrdinal && clockPendingRemaining>0) { read.probeEntry(); assertFalse(read.admit(unitClock(ordinal-1))); }
                else { read.enter(); read.complete(foreignSample(ordinal,true)); fullCaptures++; }
                return read;
            }
            @Override public void continueForeignRead(InspectAcceptanceDriver.ForeignRead read) {
                if(!read.requeue()) return;
                clockRequeues++; read.probeEntry();
                if(--clockPendingRemaining>0) assertFalse(read.admit(unitClock(read.ordinal-1)));
                else { assertTrue(read.admit(unitClock(read.ordinal))); read.complete(foreignSample(read.ordinal,true)); fullCaptures++; }
            }
            @Override public JsonObject foreignMetadata() { var out=new JsonObject(); out.addProperty("fixtureHash","partial-unit-fixture"); return out; }
            @Override public void flushForeign() { flushes++; if(flushFailure) throw new IllegalStateException("stdout failure"); }
            @Override public void retireForeignRead(InspectAcceptanceDriver.ForeignRead read) {
                retireCalls++; read.retired.set(true); if(retireFailure) throw new IllegalStateException("retire secondary");
            }
        };
        final InspectAcceptanceDriver driver=new InspectAcceptanceDriver(() -> observation,input,now::get,() -> true,logs::add,this);
        ForeignFixture() { input.now=now; }
        InspectAcceptanceDriver.ForeignClock unitClock(long ordinal) { return new InspectAcceptanceDriver.ForeignClock(ordinal,9_007_199_254_740_993L+ordinal,ordinal); }
        ForeignObservation projection(String stage,boolean replay,boolean retired,long request,long cleanup) {
            var guards=request==0?List.<ForeignGuardFact>of():List.of(new ForeignGuardFact("CLEANUP_PREFLIGHT",1,1,0,0));
            return new ForeignObservation(session,operation,3,stage,600_000_000_000L,true,replay,true,retired,request,cleanup,guards,
                    request==0?null:new ForeignFinalFacts(true,true,"java.lang.IllegalStateException","original disconnect",guards),null);
        }
        void start() {
            until(() -> input.commands.size()==1); observation=idle(reset); tick(2);
            until(() -> input.commands.size()==2); observation=idle(run); tick(2);
            until(() -> input.commands.size()==3); observation=new InspectObservation(true,true,false,Optional.of(run));
            pending=new ForeignObservation(session,operation,3,"HELD",600_000_000_000L,true,false,false,false,0,0,List.of(),null,null);
        }
        void tick() { now.addAndGet(1_000_000); driver.tick(null); }
        void tick(int count) { for(int i=0;i<count;i++) tick(); }
        void until(java.util.function.BooleanSupplier condition) {
            for(int i=0;i<400 && !condition.getAsBoolean();i++) tick();
            assertTrue(condition.getAsBoolean(),logs.toString());
        }
        List<JsonObject> proofRows() { return logs.stream().filter(s -> s.startsWith(InspectAcceptanceDriver.ForeignProof.PREFIX))
                .map(s -> JsonParser.parseString(s.substring(InspectAcceptanceDriver.ForeignProof.PREFIX.length())).getAsJsonObject()).toList(); }
        public ForeignObservation observe() { return pending; }
        public boolean release(MinecraftClient client) {
            assertEquals(26,reads); if(++releaseAttempts<3) return false; pending=projection("REPLAY_REJECTED",true,false,0,0); return true;
        }
        public boolean replay(MinecraftClient client,UUID id,long deadline) {
            assertEquals(operation,id); assertEquals(600_000_000_000L,deadline); assertEquals(27,reads);
            if(++replayAttempts<3) return false; pending=projection("FINAL_NATIVE_READ_REQUIRED",true,true,1,now.get()+10_000_000_000L); return true;
        }
        public boolean seal(MinecraftClient client,ForeignProofAck seal) {
            assertEquals(28,reads); assertEquals(1,flushes); assertEquals("SEAL",proofRows().getLast().get("kind").getAsString());
            if(candidate==null) candidate=seal; else assertSame(candidate,seal,"Contention must deliver same frozen seal object");
            if(++sealAttempts<3) return false; ack=seal; return true;
        }
    }

    private static String foreignSample(long tick,boolean server) {
        var state=new JsonObject(); var clocks=new JsonObject();
        clocks.addProperty("age",tick); clocks.addProperty("worldTime",9_007_199_254_740_993L+tick); clocks.addProperty("timeOfDay",tick);
        if(server) clocks.addProperty("serverTick",tick); else clocks.add("serverTick",com.google.gson.JsonNull.INSTANCE);
        state.add("clocks",clocks); state.addProperty("binding","exact-"+server);
        var player=new JsonObject(); var hunger=new net.minecraft.nbt.NbtCompound();
        hunger.putInt("foodLevel",20); hunger.putInt("foodTickTimer",(int)tick); hunger.putFloat("foodSaturationLevel",5);
        player.add("hunger",InspectAcceptanceDriver.typedNbt(hunger));
        player.add("inventory",JsonParser.parseString("[{\"index\":0,\"stack\":{\"count\":2,\"components\":{\"custom\":\"original\"}}}]"));
        player.add("velocity",InspectAcceptanceDriver.floating(-0.0784)); state.add("player",player);
        state.add("cells",JsonParser.parseString("[{\"position\":[0,64,0],\"loaded\":true,\"state\":{\"Name\":\"minecraft:grass_block\"},\"pending\":null}]"));
        return InspectAcceptanceDriver.canonicalText(state);
    }

    @Test void foreignOwnSideComparisonRejectsActualValuesSlotsComponentsTypesIdentityAndNonadvancingClocks() {
        for(boolean server:List.of(false,true)) {
            var baseline=foreignSample(1,server); var after=foreignSample(2,server);
            InspectAcceptanceDriver.compareForeign(baseline,baseline,after,server);
            assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.compareForeign(baseline,baseline,baseline,server));
            for(var mutation:List.of("binding","slot","component","hungerType","velocity","block","pending","loaded","clock","serverCredit")) {
                var bad=JsonParser.parseString(after).getAsJsonObject(); var p=bad.getAsJsonObject("player");
                switch(mutation) {
                    case "binding" -> bad.addProperty("binding","replacement");
                    case "slot" -> p.getAsJsonArray("inventory").get(0).getAsJsonObject().addProperty("index",1);
                    case "component" -> p.getAsJsonArray("inventory").get(0).getAsJsonObject().getAsJsonObject("stack").getAsJsonObject("components").addProperty("custom","changed");
                    case "hungerType" -> p.getAsJsonObject("hunger").getAsJsonObject("value").getAsJsonObject("foodLevel").addProperty("type",1);
                    case "velocity" -> p.add("velocity",InspectAcceptanceDriver.floating(-0.0785));
                    case "block" -> bad.getAsJsonArray("cells").get(0).getAsJsonObject().getAsJsonObject("state").addProperty("Name","minecraft:air");
                    case "pending" -> bad.getAsJsonArray("cells").get(0).getAsJsonObject().addProperty("pending","unknown");
                    case "loaded" -> bad.getAsJsonArray("cells").get(0).getAsJsonObject().addProperty("loaded",false);
                    case "clock" -> bad.getAsJsonObject("clocks").addProperty("worldTime",9_007_199_254_740_994L);
                    case "serverCredit" -> { if(server) bad.getAsJsonObject("clocks").addProperty("serverTick",1); else bad.getAsJsonObject("clocks").addProperty("serverTick",10); }
                }
                assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.compareForeign(baseline,baseline,InspectAcceptanceDriver.canonicalText(bad),server),mutation);
            }
        }
    }

    @Test void foreignNativeRequestIsNonblockingSingleEntrySingleConsumptionAndOriginalDeadlineBound() {
        var read=new InspectAcceptanceDriver.ForeignRead(1,100,foreignSample(1,false));
        assertNull(read.consume(1,1)); read.enter(); assertThrows(IllegalStateException.class,read::enter);
        read.complete(foreignSample(1,true)); assertEquals(foreignSample(1,true),read.consume(1,99));
        assertThrows(IllegalStateException.class,() -> read.consume(1,99));
        assertThrows(IllegalStateException.class,() -> read.complete("late"));
        var expired=new InspectAcceptanceDriver.ForeignRead(2,100,"{}"); expired.enter(); expired.complete("{}");
        assertThrows(IllegalStateException.class,() -> expired.consume(2,100));
        assertThrows(IllegalStateException.class,() -> expired.consume(3,1));
    }

    @Test void foreignTypedEncodingPreservesTypesLongsArraysAndRawFloatingBits() {
        var data=new net.minecraft.nbt.NbtCompound();
        data.putByte("b",(byte)1); data.putInt("i",1); data.putLong("l",Long.MAX_VALUE);
        data.putFloat("f",1.5f); data.putDouble("d",1.5d);
        data.putByteArray("ba",new byte[]{-1,2}); data.putIntArray("ia",new int[]{-1,2});
        data.putLongArray("la",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});
        var list=new net.minecraft.nbt.NbtList(); list.add(net.minecraft.nbt.NbtString.of("\u00e9\n")); data.put("list",list);
        var encoded=InspectAcceptanceDriver.typedNbt(data).getAsJsonObject("value");
        assertNotEquals(encoded.get("b"),encoded.get("i"));
        assertEquals(Long.MAX_VALUE,encoded.getAsJsonObject("l").get("value").getAsLong());
        assertEquals("3fc00000",encoded.getAsJsonObject("f").getAsJsonObject("value").get("bits").getAsString());
        assertEquals("3ff8000000000000",encoded.getAsJsonObject("d").getAsJsonObject("value").get("bits").getAsString());
        assertEquals("80000000",InspectAcceptanceDriver.floating(-0.0f).get("bits").getAsString());
        assertEquals("8000000000000000",InspectAcceptanceDriver.floating(-0.0d).get("bits").getAsString());
        assertNotEquals(encoded.get("ba"),encoded.get("ia")); assertNotEquals(encoded.get("ia"),encoded.get("la"));
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.floating(Float.NaN));
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.floating(Double.POSITIVE_INFINITY));
        assertThrows(IllegalStateException.class,() -> InspectAcceptanceDriver.typedNbt(null));
    }

    @Test void foreignProofChunksAreByteExactContiguousLfHashedAndSealedOnce() throws Exception {
        var lines=new ArrayList<String>(); var flushed=new int[1];
        var proof=new InspectAcceptanceDriver.ForeignProof(lines::add,() -> flushed[0]++);
        var raw="{\"unicode\":\""+"\u00e9".repeat(2500)+"\",\"long\":9223372036854775807}";
        long request=0;
        for(var pair:InspectAcceptanceDriver.PAIR_LABELS) proof.pair(pair,++request,raw,raw);
        var observation=new ForeignObservation(UUID.randomUUID(),UUID.randomUUID(),3,"FINAL_NATIVE_READ_REQUIRED",Long.MAX_VALUE,
                true,true,true,true,1,Long.MAX_VALUE,List.of(),null,null);
        var ack=proof.seal(observation,"full-F-identity",Long.MAX_VALUE-1);
        assertEquals(1,flushed[0]); assertEquals(lines.size()-1,ack.rows());
        var digest=java.security.MessageDigest.getInstance("SHA-256"); long bytes=0; int row=0;
        var decoded=new java.io.ByteArrayOutputStream();
        for(var line:lines.subList(0,lines.size()-1)) {
            assertTrue((line+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);
            var json=line.substring(InspectAcceptanceDriver.ForeignProof.PREFIX.length());
            var value=JsonParser.parseString(json).getAsJsonObject(); assertEquals(row++,value.get("row").getAsInt());
            var data=Base64.getDecoder().decode(value.get("base64").getAsString()); assertTrue(data.length<=1536);
            if(value.get("pair").getAsString().equals("B0") && value.get("side").getAsString().equals("client")) decoded.write(data);
            var hashBytes=(json+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8); digest.update(hashBytes); bytes+=hashBytes.length;
        }
        assertEquals(raw,decoded.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(bytes,ack.bytes()); assertEquals(HexFormat.of().formatHex(digest.digest()),ack.sha256());
        assertThrows(IllegalStateException.class,() -> proof.row("LATE",new JsonObject()));
        assertThrows(IllegalStateException.class,() -> proof.seal(observation,"duplicate",1));
        var overflow=new InspectAcceptanceDriver.ForeignProof(lines::add,() -> {});
        int before=lines.size(); assertThrows(IllegalStateException.class,() -> overflow.pair("B0",1,"x".repeat(262145),"{}"));
        assertEquals(before,lines.size());
        assertThrows(IllegalStateException.class,() -> overflow.pair("B0",1,"","{}")); assertEquals(before,lines.size());
        assertThrows(IllegalStateException.class,() -> overflow.row("TOO_BIG",JsonParser.parseString("{\"x\":\""+"x".repeat(4096)+"\"}").getAsJsonObject()));
        assertThrows(IllegalStateException.class,() -> overflow.seal(observation,"missing pairs",1));
    }

    @Test void foreignSelectionStartsWithRealResetInput() {
        var previous=System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            System.setProperty("mapart.test.inspect.acceptance.case","foreign-world");
            var f=new Fixture(); f.send();
            assertEquals(List.of("/maparttest reset grounded-two-lane"),f.input.commands,f.logs.toString());
            assertEquals(0,f.input.stops);
        } finally {
            if(previous==null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case",previous);
        }
    }

    @Test void resetArchiveSelectionStartsWithExplicitResetWithoutDisplacementOrIdle() {
        var previous = System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            System.setProperty("mapart.test.inspect.acceptance.case", "reset-archive");
            var f = new Fixture(); f.send();
            assertEquals(List.of("/maparttest reset grounded-two-lane"), f.input.commands, f.logs.toString());
            assertEquals(0, f.input.stops); assertEquals(0, f.idleReads);
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case", previous);
        }
    }

    @Test void resetArchiveProgramHasEightSettledMutationsFourLastsAndTwoDistinctRecoveryRuns() {
        resetArchive(f -> {
            for(int ordinal=1;ordinal<=8;ordinal++) {
                f.send(); var result=f.completion(ordinal);
                f.observation=new InspectObservation(true,true,false,Optional.of(result)); f.tick(3);
                assertEquals(ordinal,f.input.commands.stream().filter(c -> !c.equals("/maparttest last")).count());
                f.observation=idle(result); f.tick();
                if(Set.of(1,4,5,8).contains(ordinal)) { f.send(); f.tick(); }
            }
            f.tick(3);
            assertEquals(List.of("/maparttest reset grounded-two-lane","/maparttest last","/maparttest run grounded-two-lane",
                    "/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest last",
                    "/maparttest reset grounded-two-lane","/maparttest last","/maparttest run grounded-two-lane",
                    "/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest last"),f.input.commands);
            assertEquals(1,f.input.stops); assertEquals(0,f.idleReads); assertFalse(f.input.pressed);
            assertTrue(f.input.events.stream().noneMatch(e -> e.startsWith("87")));
            assertTrue(f.logs.stream().noneMatch(e -> e.contains("DRIVER_FAIL") || e.startsWith("INSPECT_ACCEPTANCE_IDLE")),f.logs.toString());
            assertNotEquals(f.completion(4).runId(),f.completion(8).runId());
        });
    }

    @Test void resetArchiveRejectsReclassifiedFaultsNoReadinessAndInvalidEvidenceBeforeSuccessorInput() {
        for(int ordinal:List.of(1,2,3,4,5,6,7,8)) for(String bad:List.of("class","message","PASS","run","complete","settled")) resetArchive(f -> {
            for(int prior=1;prior<ordinal;prior++) {
                f.sendCompletion(prior);
                if(Set.of(1,4,5).contains(prior)) { f.send(); f.tick(); }
            }
            f.send(); var c=f.completion(ordinal);
            var failure=c.failure().orElse(new FailureObservation("unexpected","unexpected"));
            f.observation=idle(new PublishedCompletion(c.sessionId(),c.operationId(),c.ordinal(),c.command(),
                    bad.equals("run") ? c.runId().isPresent()?Optional.empty():Optional.of(UUID.randomUUID()) : c.runId(),
                    bad.equals("PASS")?c.verdict().equals("PASS")?"FAIL":"PASS":c.verdict(),!bad.equals("complete"),"RELEASED",!bad.equals("settled"),
                    c.evidenceDirectory(),c.summaryPath(), bad.equals("class") || bad.equals("message") ? Optional.of(new FailureObservation(
                    bad.equals("class")?"wrong":failure.className(),bad.equals("message")?"wrong":failure.message())):c.failure()));
            f.tick(); f.assertFailed("completion");
            assertEquals(ordinal,f.input.commands.stream().filter(command -> !command.equals("/maparttest last")).count());
        });
        resetArchive(f -> {
            for(int ordinal=1;ordinal<8;ordinal++) { f.sendCompletion(ordinal); if(Set.of(1,4,5).contains(ordinal)) { f.send(); f.tick(); } }
            f.runs[7]=f.runs[3]; f.sendCompletion(8); f.assertFailed("completion");
        });
    }

    @Test void changedResetArchiveCaseStopsBeforeFurtherInput() {
        resetArchive(f -> {
            f.sendCompletion(1); int events=f.input.events.size();
            System.setProperty("mapart.test.inspect.acceptance.case","baseline"); f.tick();
            f.assertFailed("acceptance case changed"); assertEquals(events,f.input.events.size());
        });
    }

    private static void resetArchive(java.util.function.Consumer<Fixture> test) {
        var previous=System.getProperty("mapart.test.inspect.acceptance.case");
        try { System.setProperty("mapart.test.inspect.acceptance.case","reset-archive"); var f=new Fixture(); f.resetArchive=true; test.accept(f); }
        finally { if(previous==null) System.clearProperty("mapart.test.inspect.acceptance.case"); else System.setProperty("mapart.test.inspect.acceptance.case",previous); }
    }

    @Test void settledFailureBWaitsWithoutChatInputOrStop() {
        displaced(f -> {
            f.beginForward(); f.input.x = 0.5; f.tick(); f.sendCompletion(3);
            int effects = f.input.events.size();
            f.tick(3);
            assertEquals(effects, f.input.events.size(), "Settled B must idle before LAST or RESET input");
            assertEquals(3, f.input.commands.size()); assertEquals(0, f.input.stops);
        });
    }

    @Test void idleRequiresBothStrictBoundsAndFollowingObservationCallbackWithoutPerTickSnapshots() {
        for (boolean timeFirst : List.of(false,true)) displaced(f -> {
            f.beginFailedIdle(); int events = f.input.events.size(), logs = f.logs.size();
            if (timeFirst) f.now.set(600_000_000_001L);
            f.input.reenter = () -> f.driver.tick(null); f.input.reenterNeutral = true;
            f.tick(6000);
            assertEquals(1,f.idleReads); assertEquals(logs,f.logs.size()); assertEquals(events,f.input.events.size());
            if (timeFirst) {
                f.tick(); assertEquals(1,f.idleReads); // callback6001 qualifies, reentrant calls do not count.
            } else {
                f.tick(); // 6001 callbacks alone cannot qualify.
                f.now.set(600_000_000_000L); f.tick(); // exactly600000ms cannot qualify.
                assertEquals(1,f.idleReads); assertEquals(logs,f.logs.size());
                f.now.incrementAndGet(); f.tick();
            }
            assertEquals(1,f.idleReads); assertEquals(events,f.input.events.size());
            f.tick(); assertEquals(2,f.idleReads); assertEquals(events,f.input.events.size());
            var end = f.idleMarkers().getLast();
            assertEquals(timeFirst ? 6001 : 6003,end.get("qualifyingCallbacks").getAsInt());
            assertEquals(timeFirst ? 6002 : 6004,end.get("observationCallbacks").getAsInt());
            f.tick(); assertTrue(f.input.events.size() > events); assertEquals(0,f.input.stops);
        });
    }

    @Test void twoIdleMarkersKeepFixedIdentityIndependentOriginsAndFreshNextInputDeadline() {
        displaced(f -> {
            f.beginFailedIdle(); f.finishIdle();
            f.send(); f.tick(); f.sendCompletion(4);
            f.retainedObserver = boundObservation();
            f.now.addAndGet(123_000_000L); f.sendCompletion(5);
            assertEquals(3,f.idleReads); assertEquals(6,f.input.commands.size());
            f.finishIdle();
            assertEquals(4,f.idleReads);
            var markers = f.idleMarkers(); assertEquals(4,markers.size());
            for (int pair=0;pair<2;pair++) {
                var begin = markers.get(pair*2); var end = markers.get(pair*2+1);
                assertEquals("BEGIN",begin.get("event").getAsString()); assertEquals("END",end.get("event").getAsString());
                for (String field : List.of("schema","kind","mode","acceptanceCase","nativePid","nativeStartedUtc","launchId",
                        "sessionId","operationId","ordinal","command","verdict","runId","originNanos","originUtc",
                        "beforeSamples","beforeWitnesses","beforeBound")) assertEquals(begin.get(field),end.get(field),field);
                assertEquals("mapartrunner.inspect.idle.v1",begin.get("schema").getAsString());
                assertEquals("inspect",begin.get("mode").getAsString()); assertEquals("displaced",begin.get("acceptanceCase").getAsString());
                assertEquals(pair==0?"failed":"terminal",begin.get("kind").getAsString());
                assertEquals(pair==0?3:5,begin.get("ordinal").getAsInt()); assertEquals("RUN",begin.get("command").getAsString());
                assertEquals(pair==0?"FAIL":"PASS",begin.get("verdict").getAsString());
                assertEquals(f.session.toString(),begin.get("sessionId").getAsString());
                assertEquals(f.operations[pair==0?2:4].toString(),begin.get("operationId").getAsString());
                assertEquals(42,begin.get("nativePid").getAsLong());
                assertEquals("2026-11-12T00:00:00Z",begin.get("nativeStartedUtc").getAsString());
                assertEquals("00000000-0000-0000-0000-000000000001",begin.get("launchId").getAsString());
                assertEquals(pair==0,begin.get("runId").isJsonNull());
                assertEquals(600_000_000_001L,end.get("elapsedNanos").getAsLong());
                assertEquals(end.get("endNanos").getAsLong()-begin.get("originNanos").getAsLong(),end.get("elapsedNanos").getAsLong());
                assertEquals(6001,end.get("qualifyingCallbacks").getAsInt()); assertEquals(6002,end.get("observationCallbacks").getAsInt());
                assertTrue(end.get("retainedEqual").getAsBoolean());
                assertEquals(pair!=0,begin.get("beforeBound").getAsBoolean());
                assertEquals(begin.get("beforeSamples"),end.get("afterSamples"));
                assertEquals(begin.get("beforeWitnesses"),end.get("afterWitnesses"));
                assertFalse(Instant.parse(end.get("endUtc").getAsString()).isBefore(Instant.parse(begin.get("originUtc").getAsString())));
            }
            assertTrue(markers.get(2).get("originNanos").getAsLong()>markers.get(1).get("endNanos").getAsLong());
            assertNotEquals(markers.get(0).get("originUtc"),markers.get(2).get("originUtc"));
            f.tick(); f.now.addAndGet(29_000_000_000L); f.openChat(); f.tick(); f.tick();
            assertEquals(1,f.input.stops); assertTrue(f.logs.stream().noneMatch(s -> s.contains("DRIVER_FAIL")));
            assertTrue(f.logs.stream().allMatch(s -> s.length()<=4096));
        });
    }

    @Test void idleLossOrChangedRetainedValueFailsWithoutSuccessorInputOrEndMarker() {
        for (String lost : List.of("case","busy","last","ready","restart","gate","world","focus","screen","neutral","guard","snapshot","clock"))
            displaced(f -> {
                f.beginFailedIdle(); int effects = f.input.events.size();
                switch (lost) {
                    case "case" -> System.setProperty("mapart.test.inspect.acceptance.case","baseline");
                    case "busy" -> f.observation = new InspectObservation(true,true,false,f.observation.last());
                    case "last" -> f.observation = idle(f.changed(f.completion(3),"session"));
                    case "ready" -> f.observation = new InspectObservation(false,false,false,f.observation.last());
                    case "restart" -> f.observation = new InspectObservation(true,false,true,f.observation.last());
                    case "gate" -> f.enabled=false;
                    case "world","focus" -> f.input.refusal=lost;
                    case "screen" -> f.input.screen=new Object();
                    case "neutral" -> f.input.neutral=false;
                    case "guard" -> f.guardFailure=lost;
                    case "snapshot" -> f.retainedObserver=boundObservation();
                    case "clock" -> f.now.set(-1);
                }
                if (lost.equals("snapshot")) f.finishIdle(); else f.tick();
                f.assertFailed("DRIVER_FAIL"); assertEquals(effects,f.input.events.size());
                assertEquals(3,f.input.commands.size()); assertEquals(1,f.idleMarkers().size());
            });
    }

    @Test void idleRevalidatesOnFinalReadAndTerminalRequiresEveryRetainedHistory() {
        displaced(f -> {
            f.beginFailedIdle(); f.now.set(600_000_000_001L); f.tick(6001);
            f.guardFailure="final guard"; f.tick(); f.assertFailed("final guard");
            assertEquals(1,f.idleReads); assertEquals(1,f.idleMarkers().size());
        });
        for (String missing : List.of("samples","witnesses","binding")) displaced(f -> {
            f.beginFailedIdle(); f.finishIdle(); f.send(); f.tick(); f.sendCompletion(4);
            f.retainedObserver=boundObservation(missing); f.sendCompletion(5);
            f.assertFailed("Terminal idle requires"); assertEquals(2,f.idleMarkers().size());
        });
    }

    @Test void publicationBeforeWorkerSettlementDoesNotStartIdleAndBoundaryReadRechecksAuthority() {
        displaced(f -> {
            f.beginForward(); f.input.x=0.5; f.tick(); f.send();
            f.observation=new InspectObservation(true,true,false,Optional.of(f.completion(3))); f.tick(4);
            assertEquals(0,f.idleReads); assertTrue(f.idleMarkers().isEmpty());
            f.observation=idle(f.completion(3)); f.boundaryFailure="boundary authority lost";
            f.tick(); f.assertFailed("boundary authority lost"); assertTrue(f.idleMarkers().isEmpty());
        });
    }

    @Test void idleGateLossOnRejectedEntryDefersStopUntilAValidCallback() {
        displaced(f -> {
            f.beginFailedIdle(); int effects=f.input.events.size();
            f.enabled=false; f.input.entryRefusal="rejected idle entry"; f.tick();
            assertEquals(0,f.input.stops); assertEquals(effects,f.input.events.size());
            f.input.entryRefusal=null; f.tick(); f.assertFailed("rejected idle entry");
            assertEquals(effects,f.input.events.size()); assertEquals(1,f.idleMarkers().size());
        });
    }
    @Test void activeUnknownCaseFailsBeforeAnyInputButInactiveSelectionIsInert() {
        var previous = System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            for (var value : List.of("", "unknown")) {
                System.setProperty("mapart.test.inspect.acceptance.case", value);
                var inactive = new Fixture(); inactive.enabled = false; inactive.tick();
                assertTrue(inactive.input.events.isEmpty()); assertEquals(0, inactive.input.stops);
                var active = new Fixture(); active.tick(); active.assertFailed("Unknown acceptance case");
                assertTrue(active.input.events.isEmpty());
            }
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case", previous);
        }
    }
    @Test void explicitDoubleOptInAndAbsentAutomaticModeAreRequired() {
        for (var inspect : Arrays.asList(null, "false", "yes", "true"))
            for (var acceptance : Arrays.asList(null, "false", "1", "true"))
                for (var automatic : Arrays.asList(null, "false", "true"))
                    assertEquals("true".equals(inspect) && "true".equals(acceptance) && automatic == null,
                            InspectAcceptanceDriver.enabled(inspect, acceptance, automatic));
        var f = new Fixture(); f.enabled = false; f.tick(10);
        assertTrue(f.input.events.isEmpty()); assertTrue(f.logs.isEmpty()); assertEquals(0, f.input.stops);
    }

    @Test void displacedProgramRequiresExactFailureBThenLastExplicitResetAndSuccess() {
        displaced(f -> {
            f.beginForward(); f.input.x = 0.5; f.tick(); assertFalse(f.input.pressed);
            f.sendCompletion(3); f.finishIdle(); f.send(); f.tick();
            f.sendCompletion(4); f.retainedObserver=boundObservation(); f.sendCompletion(5); f.finishIdle(); f.send(); f.tick();
            assertEquals(List.of("/maparttest run grounded-two-lane", "/maparttest reset grounded-two-lane",
                    "/maparttest run grounded-two-lane", "/maparttest last", "/maparttest reset grounded-two-lane",
                    "/maparttest run grounded-two-lane", "/maparttest last"), f.input.commands);
            assertEquals(1, f.input.stops); assertFalse(f.input.pressed);
            assertEquals(5, f.logs.stream().filter(s -> s.contains("RESULT command=")).count());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("ordinal=3 verdict=FAIL") && s.contains("run=none")));
            assertEquals(2, f.logs.stream().filter(s -> s.contains("LAST submitted")).count());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("SEQUENCE_COMPLETE")));
        });
    }

    @Test void displacementRequiresSettledResetAndMeasuredDistanceWithTwentiethCallbackInclusive() {
        displaced(f -> {
            f.sendCompletion(1); f.send();
            f.observation = new InspectObservation(true, true, false, Optional.of(f.completion(2)));
            f.tick(4); assertFalse(f.input.pressed);
            f.observation = idle(f.completion(2)); f.tick(); assertFalse(f.input.pressed);
            f.tick(); assertTrue(f.input.pressed);
            f.input.reenter = () -> f.driver.tick(null);
            f.input.x = 0.499; f.tick(19); assertTrue(f.input.pressed); assertEquals(2, f.input.commands.size());
            f.input.x = 0.5; f.tick(); assertFalse(f.input.pressed);
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("distance=0.5 callbacks=20")));
            assertEquals(1, Collections.frequency(f.input.events, "87+"));
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
        });
        displaced(f -> { f.beginForward(); f.tick(20); f.assertFailed("20 END callbacks"); assertFalse(f.input.pressed); });
        displaced(f -> { f.beginForward(); f.input.z = -0.6; f.tick(); assertFalse(f.input.pressed); });
    }

    @Test void nativeForwardKeyPolicyRejectsRemapCollisionPressedControlsAndProductionOwnerWithoutRepair() {
        var forward = new KeyBinding("key.mapart.k-forward", 87, KeyBinding.Category.MOVEMENT);
        var other = new KeyBinding("key.mapart.k-other", 65, KeyBinding.Category.MOVEMENT);
        var keys = new KeyBinding[]{forward, other}; var controls = List.of(forward, other);
        assertDoesNotThrow(() -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        for (var held : controls) {
            held.setPressed(true);
            assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
            assertTrue(held.isPressed(), "Guard must not repair manual input"); held.setPressed(false);
        }
        for (var owner : List.of(ControlOwner.NATIVE, ControlOwner.TRAVEL))
            assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, owner));
        other.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(87));
        assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        other.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(65));
        forward.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(88));
        assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        forward.setBoundKey(forward.getDefaultKey());
    }

    @Test void forwardDeadlineCarriesThroughBChatAndRejectsLateThresholdAndNonfinitePose() {
        displaced(f -> {
            f.beginForward(); f.now.set(29_000_000_000L); f.input.x = 0.5; f.tick();
            f.send(); f.now.set(30_000_000_000L); f.tick(); f.assertFailed("Input deadline expired");
        });
        displaced(f -> {
            f.beginForward(); f.now.set(30_000_000_000L); f.input.x = 1; f.tick();
            f.assertFailed("Input deadline expired"); assertFalse(f.input.pressed);
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
        });
        displaced(f -> { f.input.x = Double.NaN; f.beginForward(); f.assertFailed("Nonfinite"); assertFalse(f.input.pressed); });
        displaced(f -> { f.beginForward(); f.input.z = Double.POSITIVE_INFINITY; f.tick(); f.assertFailed("Nonfinite"); assertFalse(f.input.pressed); });
    }

    @Test void wrongBClassMessageVerdictOrRunCannotBecomeAcceptedFailure() {
        for (var bad : List.of("class", "message", "verdict", "run")) displaced(f -> {
            f.beginForward(); f.input.x = 1; f.tick(); f.send(); var c = f.completion(3);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), c.ordinal(), c.command(),
                    bad.equals("run") ? Optional.of(UUID.randomUUID()) : c.runId(), bad.equals("verdict") ? "PASS" : "FAIL",
                    true, "RELEASED", true, c.evidenceDirectory(), c.summaryPath(), Optional.of(new FailureObservation(
                    bad.equals("class") ? "java.lang.IllegalStateException" : "java.lang.AssertionError",
                    bad.equals("message") ? "other" : "Start state changed without physics"))));
            f.tick(); f.assertFailed("completion");
        });
    }

    @Test void heldInputLosesGameplayAuthorityWithOnlyExactCapturedFalseCleanup() {
        for (var lost : List.of("window", "world", "connection", "focus", "gate", "guard")) displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            if (lost.equals("gate")) f.enabled = false;
            else if (lost.equals("guard")) f.guardFailure = "guard";
            else f.input.refusal = lost;
            f.tick(); f.tick(3); f.assertFailed(lost.equals("gate") ? "gate disabled" : lost);
            assertFalse(f.input.pressed);
            assertEquals(List.of("captured-false"), f.input.events.subList(effects, f.input.events.size()));
            assertEquals(2, f.input.commands.size());
        });
    }

    @Test void releaseDebtSurvivesWrongThreadAndStoppingNeedsNoFollowingEndTick() {
        for (var lost : List.of("client", "thread")) displaced(f -> {
            f.beginForward(); f.input.clientRefusal = lost; f.tick();
            assertTrue(f.input.pressed); assertEquals(0, f.input.stops);
            f.input.clientRefusal = null; f.input.refusal = "world"; f.tick();
            assertFalse(f.input.pressed); f.assertFailed(lost);
            assertEquals(1, Collections.frequency(f.input.events, "captured-false"));
        });
        displaced(f -> {
            f.beginForward(); f.input.refusal = "window"; f.driver.releaseOnStopping(null);
            assertFalse(f.input.pressed); int events = f.input.events.size();
            f.driver.releaseOnStopping(null); assertEquals(events, f.input.events.size());
        });
        displaced(f -> {
            f.beginForward(); f.input.pressed = false; int events = f.input.events.size();
            f.driver.releaseOnStopping(null); assertEquals(events, f.input.events.size());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedCurrentEntryRetainsForwardDebtEvenWhenCapturedClientCleanupWouldSucceed(boolean stopping) {
        displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            f.input.entryRefusal = "foreign current";
            // Model a foreign current argument on the healthy installed client's own thread.
            assertTrue(f.input.forwardPressed(f.input.forward), "Captured-client checks remain valid");
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertTrue(f.input.pressed, "Rejected entry must retain the exact release debt");
            assertEquals(effects, f.input.events.size()); assertEquals(0, f.input.stops);
            f.input.entryRefusal = null;
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertFalse(f.input.pressed);
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertEquals(0, Collections.frequency(f.input.events, "captured-false"));
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertEquals(1, f.logs.stream().filter(s -> s.contains("DRIVER_FAIL") && s.contains("foreign current")).count());
        });
    }

    @Test void changingPublishedValueOrAdmissionWhileHeldStopsBeforeAnyNewGameplayInput() {
        for (boolean busy : List.of(true, false)) displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            f.observation = new InspectObservation(true, busy, false,
                    Optional.of(busy ? f.completion(2) : f.changed(f.completion(2), "session")));
            f.tick(); f.assertFailed(busy ? "admission while moving" : "Retained completion changed");
            assertFalse(f.input.pressed);
            assertEquals(List.of("captured-false"), f.input.events.subList(effects, f.input.events.size()));
        });
    }

    @Test void applyingPressOrReleaseExceptionsCannotLeakOrResendForwardAndCleanupFailureIsNotSuccess() {
        for (var event : List.of("87+", "87-")) displaced(f -> {
            f.input.throwAt = event; f.input.reenter = () -> f.driver.tick(null);
            f.beginForward(); if (event.equals("87-")) { f.input.x = 1; f.tick(); }
            f.tick(3); f.assertFailed("callback"); assertFalse(f.input.pressed);
            assertEquals(1, Collections.frequency(f.input.events, "87+"));
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
        });
        displaced(f -> {
            f.beginForward(); f.input.refusal = "world"; f.input.clearFails = true; f.tick();
            assertTrue(f.input.pressed); assertEquals(0, f.input.stops);
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
            f.input.clearFails = false; f.tick(); assertFalse(f.input.pressed); assertEquals(1, f.input.stops);
        });
    }

    private static void displaced(java.util.function.Consumer<Fixture> test) {
        var previous = System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            System.setProperty("mapart.test.inspect.acceptance.case", "displaced");
            var f = new Fixture(); f.displaced = true; test.accept(f);
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case", previous);
        }
    }

    @Test void startupWaitDoesNotConsumeInputBoundAndInitialLastMustBeAbsent() {
        var f = new Fixture(); f.observation = new InspectObservation(false, true, false, Optional.empty());
        f.tick(); f.now.set(100_000_000_000L); f.tick(); assertTrue(f.input.events.isEmpty());
        f.observation = idle(null); f.tick(); assertEquals(List.of("84+", "84-"), f.input.events);
        var stale = new Fixture(); stale.observation = idle(stale.completion(1)); stale.tick();
        stale.assertFailed("Initial last must be absent");
    }

    @Test void fixedSequenceAcceptsFastCompletionsAndOnlyReportsLastSubmitted() {
        var f = new Fixture();
        for (int ordinal = 1; ordinal <= 5; ordinal++) {
            f.send(); assertEquals(ordinal, f.input.commands.size());
            var completion = f.completion(ordinal);
            f.observation = idle(completion); f.tick(); f.tick();
        }
        f.send(); assertEquals(List.of("/maparttest run grounded-two-lane", "/maparttest reset grounded-two-lane",
                "/maparttest run grounded-two-lane", "/maparttest rerun grounded-two-lane",
                "/maparttest rerun grounded-two-lane", "/maparttest last"), f.input.commands);
        assertEquals(0, f.input.stops, "Enter delivery alone cannot finish LAST");
        f.tick(); assertEquals(1, f.input.stops); f.tick(10); assertEquals(1, f.input.stops);
        assertEquals(12, f.input.events.size() / 2);
        assertTrue(f.idleMarkers().isEmpty()); assertEquals(0,f.idleReads);
        assertEquals(1, f.logs.stream().filter(s -> s.contains("SEQUENCE_COMPLETE")).count());
        assertTrue(f.logs.stream().anyMatch(s -> s.contains("LAST submitted")));
        assertTrue(f.logs.stream().noneMatch(s -> s.contains("response proved") || s.contains("normal-close PASS")));
        var complete = f.logs.stream().filter(s -> s.contains("SEQUENCE_COMPLETE")).findFirst().orElseThrow();
        for (int i = 2; i < 5; i++) assertTrue(complete.contains(f.runs[i].toString()));
    }

    @Test void matchingPublicationStillBusyCannotAdvanceAndOldResultWaits() {
        var f = new Fixture(); f.send(); var first = f.completion(1);
        f.observation = new InspectObservation(true, true, false, Optional.of(first)); f.tick(3);
        assertEquals(1, f.input.commands.size());
        f.observation = idle(first); f.tick(); f.tick(); f.send();
        f.tick(3); assertEquals(2, f.input.commands.size()); assertEquals(0, f.input.stops);
        f.observation = idle(f.completion(2)); f.tick(); f.tick();
        assertEquals(0, f.input.stops);
    }

    @Test void originalDeadlineIncludesChatOpeningTypingAndAdmissionWithoutRenewal() {
        var f = new Fixture(); f.tick(); f.now.set(29_000_000_000L); f.openChat(); f.tick();
        f.now.set(30_000_000_000L); f.tick(); f.assertFailed("Input deadline expired");
        assertEquals(1, f.input.commands.size());
        var opening = new Fixture(); opening.tick(); opening.now.set(30_000_000_000L); opening.tick();
        opening.assertFailed("Input deadline expired"); assertTrue(opening.input.commands.isEmpty());
    }

    @Test void admittedWorkUsesExistingSupervisionRatherThanInputDeadline() {
        var f = new Fixture(); f.send();
        f.observation = new InspectObservation(true, true, false, Optional.empty()); f.tick();
        f.now.set(600_000_000_000L); f.tick(3); assertEquals(0, f.input.stops);
        f.observation = idle(f.completion(1)); f.tick(); assertEquals(0, f.input.stops);
    }

    @Test void callbackExceptionAndReentrancyCannotResendAndKeysArePaired() {
        for (var event : List.of("84+", "char", "257+", "257-")) {
            var f = new Fixture(); f.input.reenter = () -> f.driver.tick(null); f.input.throwAt = event;
            f.send(); f.tick(5); f.assertFailed("callback");
            assertTrue(Collections.frequency(f.input.events, "84+") <= 1);
            assertTrue(Collections.frequency(f.input.events, "257+") <= 1);
            assertEquals(Collections.frequency(f.input.events, "84+"), Collections.frequency(f.input.events, "84-"));
            assertEquals(Collections.frequency(f.input.events, "257+"), Collections.frequency(f.input.events, "257-"));
        }
    }

    @Test void invalidNativeIdentityScreenDraftFocusOrPauseStopsOnceWithoutRepair() {
        for (var refusal : List.of("window", "world", "server", "player", "handler", "connection", "pause", "focus", "binding")) {
            var f = new Fixture(); f.tick(); f.input.refusal = refusal; f.tick(3); f.assertFailed(refusal);
            assertTrue(f.input.commands.isEmpty());
        }
        var screen = new Fixture(); screen.tick(); screen.input.screen = new Object(); screen.tick(); screen.assertFailed("screen");
        var draft = new Fixture(); draft.tick(); draft.openChat(); draft.input.draft = "foreign"; draft.tick(); draft.assertFailed("draft");
        var replaced = new Fixture(); replaced.tick(); replaced.openChat(); replaced.input.replaceOnChar = true;
        replaced.tick(); replaced.assertFailed("screen"); assertTrue(replaced.input.commands.isEmpty());
    }

    @Test void wrongClientOrThreadCannotDeliverInputOrStopUntilAValidCallback() {
        for (var refusal : List.of("client", "thread")) {
            var f = new Fixture(); f.input.clientRefusal = refusal; f.tick(3);
            assertTrue(f.input.events.isEmpty()); assertEquals(0, f.input.stops);
            f.input.clientRefusal = null; f.tick(3); f.assertFailed(refusal);
            assertTrue(f.input.events.isEmpty());
        }
    }

    @Test void preEnterForeignAdmissionAndPendingResultChangesFailWithoutResend() {
        var before = new Fixture(); before.tick();
        before.observation = new InspectObservation(true, true, false, Optional.empty()); before.openChat(); before.tick();
        before.assertFailed("admission before Enter"); assertTrue(before.input.commands.isEmpty());
        var pending = new Fixture(); pending.send(); var c = pending.completion(1);
        pending.observation = new InspectObservation(true, true, false, Optional.of(c)); pending.tick();
        pending.observation = idle(pending.changed(c, "session")); pending.tick(); pending.assertFailed("completion changed");
        assertEquals(1, pending.input.commands.size());
    }

    @Test void lastRequiresNaturalHandoffAndUnchangedPublishedResult() {
        var f = new Fixture();
        for (int i = 1; i <= 5; i++) { f.send(); f.observation = idle(f.completion(i)); f.tick(); f.tick(); }
        f.send(); f.observation = idle(f.changed(f.completion(5), "session")); f.tick();
        f.assertFailed("Retained completion changed");
        assertTrue(f.logs.stream().noneMatch(s -> s.contains("SEQUENCE_COMPLETE")));
    }

    @Test void failureDiagnosticIsBoundedAndNeverRepeated() {
        var f = new Fixture(); f.tick(); f.input.refusal = "x".repeat(6000); f.tick(5);
        f.assertFailed("DRIVER_FAIL");
        assertEquals(4096, f.logs.stream().filter(s -> s.contains("DRIVER_FAIL")).findFirst().orElseThrow().length());
    }

    @Test void restartOrReadinessLossCannotBecomeSuccess() {
        for (var observation : List.of(new InspectObservation(true, false, true, Optional.empty()),
                new InspectObservation(false, false, false, Optional.empty()))) {
            var f = new Fixture(); f.send(); f.observation = observation; f.tick(); f.assertFailed("unavailable");
        }
    }

    @Test void foreignSkippedReclassifiedDuplicateAndIncompleteResultsAreRejected() {
        for (var bad : List.of("ordinal", "command", "session", "operation", "directory", "summary", "cleanup", "settled", "complete", "verdict", "failure", "run")) {
            var f = new Fixture(); f.send(); f.observation = idle(f.completion(1)); f.tick(); f.tick(); f.send();
            f.observation = idle(f.changed(f.completion(2), bad)); f.tick(); f.assertFailed("completion");
        }
        var changed = new Fixture(); changed.send(); changed.observation = idle(changed.completion(1)); changed.tick(); changed.tick(); changed.send();
        changed.observation = idle(changed.changed(changed.completion(1), "verdict")); changed.tick(); changed.assertFailed("Retained completion changed");
    }

    @Test void expectedFailureARequiresExactCauseAndRemainsFail() {
        for (var cause : List.of("PASS", "other class", "other message", "with run")) {
            var f = new Fixture(); f.send(); var c = f.completion(1);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), 1, c.command(),
                    cause.equals("with run") ? Optional.of(UUID.randomUUID()) : c.runId(), cause.equals("PASS") ? "PASS" : "FAIL",
                    true, "RELEASED", true, c.evidenceDirectory(), c.summaryPath(), Optional.of(new FailureObservation(
                    cause.equals("other class") ? "java.lang.IllegalStateException" : "java.lang.AssertionError",
                    cause.equals("other message") ? "other" : "Run requires this invocation's successful reset"))));
            f.tick(); f.assertFailed("completion");
        }
        var f = new Fixture(); f.send(); f.observation = idle(f.completion(1)); f.tick();
        assertTrue(f.logs.stream().anyMatch(s -> s.contains("verdict=FAIL"))); assertEquals(0, f.input.stops);
    }

    @Test void repeatedRunUuidAndUnexpectedBuildFailureAreRejected() {
        for (boolean duplicate : List.of(true, false)) {
            var f = new Fixture();
            for (int i = 1; i <= 3; i++) { f.send(); f.observation = idle(f.completion(i)); f.tick(); f.tick(); }
            f.send(); var c = f.completion(4);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), 4, c.command(),
                    duplicate ? f.completion(3).runId() : c.runId(), duplicate ? "PASS" : "FAIL", true, "RELEASED", true,
                    c.evidenceDirectory(), c.summaryPath(), duplicate ? Optional.empty() : Optional.of(new FailureObservation("x", "x"))));
            f.tick(); f.assertFailed("completion");
        }
    }

    @Test void expiryFixedFiveChatProgramRetainsFailedRunAndWaitsForWorkerBeforeReset3AndLast() {
        expiry(f -> {
            f.start();
            f.now.set(f.work-1); f.tick(8);
            assertEquals(2,f.input.commands.size()); assertEquals(0,f.neutralChecks); assertEquals(0,f.input.stops);
            f.busy(); f.until(() -> f.ackAttempts==1);
            assertEquals(3,f.input.commands.size()); assertEquals(2,f.observation.last().orElseThrow().ordinal()+1);
            f.observation=new InspectObservation(true,true,false,Optional.of(f.run)); f.tick(3);
            assertEquals(3,f.input.commands.size()); assertEquals(1,f.ackAttempts);
            f.observation=idle(f.run); f.tick();
            f.until(() -> f.input.commands.size()==4); f.observation=idle(f.reset3); f.tick();
            f.until(() -> f.input.stops==1);
            assertEquals(List.of("/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane",
                    "/maparttest rerun grounded-two-lane","/maparttest reset grounded-two-lane","/maparttest last"),f.input.commands);
            assertEquals(3,f.logs.stream().filter(s -> s.contains("RESULT command=")).count());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("EXPIRY_RETAINED") && s.contains(f.operation.toString())
                    && s.contains(f.run.runId().orElseThrow().toString()) && s.contains(f.run.summaryPath()) && s.contains("work-expiry-proof.json")));
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("LAST submitted") && s.contains(f.reset3.operationId().toString())));
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("command=/maparttest run") && s.contains("denied RERUN role")));
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("DRIVER_FAIL")),f.logs.toString());
            assertEquals(1,f.ackAttempts); assertEquals(10,f.input.events.stream().filter(s -> s.endsWith("+")).count());
            assertEquals(10,f.input.events.stream().filter(s -> s.endsWith("-")).count());
        });
    }

    @Test void expirySameFrozenAckRetriesOnlyLaterCallbacksAndNeverAgainAfterSuccess() {
        expiry(f -> {
            f.ackFalse=2; f.start(); f.busy(); f.until(() -> f.ackAttempts==1);
            var candidate=f.candidate; int commands=f.input.commands.size(),neutral=f.neutralChecks;
            f.tick(); assertEquals(2,f.ackAttempts); assertSame(candidate,f.candidate);
            f.tick(); assertEquals(3,f.ackAttempts); assertSame(candidate,f.ack);
            f.tick(10); assertEquals(3,f.ackAttempts); assertEquals(commands,f.input.commands.size());
            assertEquals(neutral+2,f.neutralChecks); f.assertHealthy();
        });
    }

    @Test void expiryNullAndFiniteIntermediateProjectionsNeverAcquireInputOrNeutralOwner() {
        expiry(f -> {
            f.start(); f.input.neutral=false;
            f.now.set(300_000_000_000L); f.tick(10);
            for(var stage:List.of("ARMED","HELD","EXPIRED","REPLAY_QUEUED","CLEANUP_PENDING")) {
                f.projection=f.project(stage,0,0,null); f.tick();
            }
            assertEquals(0,f.neutralChecks); assertEquals(2,f.input.commands.size()); assertEquals(0,f.ackAttempts); f.assertHealthy();
        });
    }

    @Test void expiryRejectsChangedIdentityCountsTimeoutProofCutoffsAndPrematureAcknowledgement() {
        for(var field:List.of("session","operation","run","ordinal","work","outer","wrapper","action","replayCount","rejected","distinct",
                "primaryClass","primaryMessage","replayClass","replayMessage","retired","request","cleanup","tooEarly","late","outerLate","refusal","ack","incomplete","replayFailed","unknown")) expiry(f -> {
            f.start(); f.projection=f.project("HELD",0,0,null); f.tick(); f.busyProjection();
            var p=f.projection;
            f.projection=new ExpiryObservation(field.equals("session")?UUID.randomUUID():p.sessionId(),
                    field.equals("operation")?UUID.randomUUID():p.operationId(),field.equals("run")?UUID.randomUUID():p.runId(),
                    field.equals("ordinal")?3:2,field.equals("incomplete")?"INCOMPLETE":field.equals("replayFailed")?"REPLAY_FAILED":field.equals("unknown")?"UNKNOWN":p.stage(),
                    field.equals("work")?p.workDeadlineNanos()+1:p.workDeadlineNanos(),field.equals("outer")?p.outerCutoffNanos()+1:p.outerCutoffNanos(),
                    field.equals("wrapper")?1:2,field.equals("action")?1:0,field.equals("replayCount")?2:1,!field.equals("rejected"),!field.equals("retired"),
                    field.equals("cleanup")?p.outerCutoffNanos()+1:p.cleanupDeadlineNanos(),field.equals("request")?2:1,field.equals("refusal")?1:0,
                    field.equals("ack")?new ExpiryBusyAck(f.operation,1,p.cleanupDeadlineNanos()):null,
                    new FailureObservation(field.equals("primaryClass")?"java.lang.IllegalStateException":f.timeout.className(),field.equals("primaryMessage")?"stale scope":f.timeout.message()),
                    new FailureObservation(field.equals("replayClass")?"java.lang.IllegalStateException":f.timeout.className(),field.equals("replayMessage")?"cancelled":f.timeout.message()),!field.equals("distinct"));
            if(field.equals("tooEarly")) f.now.set(f.work-1);
            if(field.equals("late")) f.now.set(p.cleanupDeadlineNanos());
            if(field.equals("outerLate")) f.now.set(p.outerCutoffNanos());
            f.tick(); f.assertFailed(); assertEquals(2,f.input.commands.size(),field); assertEquals(0,f.ackAttempts,field);
        });
    }

    @Test void expiryCleanupDeadlineBeatsFreshInputAllowanceAndZeroRefusalNeverRetypes() {
        for(boolean zero:List.of(false,true)) expiry(f -> {
            f.start(); f.recordRefusal=!zero; f.busy();
            if(zero) f.tick(4);
            f.now.set(f.projection.cleanupDeadlineNanos()); f.tick(); f.assertFailed();
            assertEquals(zero?3:2,f.input.commands.size()); assertEquals(0,f.ackAttempts);
            f.tick(3); assertEquals(zero?3:2,f.input.commands.size());
        });
    }

    @Test void expiryRevalidatesEveryCharacterEnterHandoffAndAckWithoutResendOrUncertainRetry() {
        for(var failure:List.of("screen","binding","char","enter","charGate","enterCase","handoffScreen","ackThrow","ackBinding","duplicate","changedRequest","ackLost")) expiry(f -> {
            f.start(); f.busy();
            switch(failure) {
                case "screen" -> f.input.screen=new Object();
                case "binding" -> f.input.refusal="binding replaced";
                case "char" -> f.input.throwAt="char";
                case "enter" -> f.input.throwAt="257+";
                case "charGate" -> f.input.reenter=() -> { f.enabled=false; f.driver.tick(null); };
                case "enterCase" -> f.onBusyEnter=() -> System.setProperty("mapart.test.inspect.acceptance.case","baseline");
            }
            f.tick();
            if(List.of("handoffScreen","ackThrow","ackBinding","duplicate","changedRequest","ackLost").contains(failure)) {
                if(failure.equals("handoffScreen")) f.input.screen=new Object();
                if(failure.equals("duplicate")) f.projection=f.withRefusals(2);
                if(failure.equals("changedRequest")) {
                    var p=f.projection; f.projection=new ExpiryObservation(p.sessionId(),p.operationId(),p.runId(),2,p.stage(),p.workDeadlineNanos(),p.outerCutoffNanos(),2,0,1,true,true,
                            p.cleanupDeadlineNanos()-1,1,p.busyRefusals(),null,p.primary(),p.replay(),true);
                }
                f.tick();
                if(failure.equals("ackThrow")) f.ackThrow=true;
                if(failure.equals("ackBinding")) f.input.refusal="ack binding replaced";
                if(failure.equals("ackLost")) f.projection=f.project("SEALED_BEFORE_END",1,1,new ExpiryBusyAck(f.operation,1,f.projection.cleanupDeadlineNanos()));
            }
            f.tick(3); f.assertFailed();
            assertTrue(f.input.commands.size()<=3,failure); assertTrue(f.ackAttempts<=(failure.equals("ackThrow")?1:0),failure);
            assertEquals(f.input.events.stream().filter(s -> s.equals("257+")).count(),f.input.events.stream().filter(s -> s.equals("257-")).count(),failure);
        });
    }

    @Test void expiryReentrantInputCallbacksAndAckCannotDuplicateFiniteProgramActions() {
        expiry(f -> {
            f.start(); f.input.reenter=() -> f.driver.tick(null); f.ackReenter=true; f.busy(); f.until(() -> f.ackAttempts==1);
            f.tick(5); assertEquals(3,f.input.commands.size()); assertEquals(1,f.ackAttempts); f.assertHealthy();
        });
    }

    @Test void expiryBusyChatWaitsForNaturalClosureAndStopsAtItsOriginalCleanupLimit() {
        for(boolean expire:List.of(false,true)) expiry(f -> {
            f.start(); f.keepBusyScreen=true; f.busy(); f.tick(); f.tick(3);
            assertEquals(3,f.input.commands.size()); assertEquals(0,f.ackAttempts);
            if(expire) { f.now.set(f.projection.cleanupDeadlineNanos()); f.tick(); f.assertFailed(); }
            else { f.input.screen=null; f.tick(2); assertEquals(1,f.ackAttempts); f.assertHealthy(); }
            assertEquals(3,f.input.commands.size());
        });
    }

    @Test void expiryWorkScreenLostProjectionAndBusyOpenCallbackFailureCannotAcquireMoreInput() {
        for(var failure:List.of("screen","projection","open")) expiry(f -> {
            f.start();
            if(failure.equals("screen")) f.input.screen=new Object();
            if(failure.equals("projection")) { f.projection=f.project("HELD",0,0,null); f.tick(); f.projection=null; }
            if(failure.equals("open")) { f.input.throwAt="84+"; f.busyProjection(); }
            f.tick(3); f.assertFailed(); assertEquals(2,f.input.commands.size()); assertEquals(0,f.ackAttempts);
            assertEquals(f.input.events.stream().filter(s -> s.equals("84+")).count(),f.input.events.stream().filter(s -> s.equals("84-")).count());
        });
    }

    @Test void expiryAcknowledgementDoesNotAllowUnsealedOrChangedProofAndCannotBeRetriedAfterException() {
        for(var failure:List.of("unsealed","changedSeal","appliedThenThrow")) expiry(f -> {
            f.start(); f.busy();
            if(failure.equals("appliedThenThrow")) f.ackApplyThenThrow=true;
            f.until(() -> f.ackAttempts==1);
            if(!failure.equals("appliedThenThrow")) {
                if(failure.equals("changedSeal")) f.tick();
                var p=f.projection;
                f.projection=new ExpiryObservation(p.sessionId(),p.operationId(),p.runId(),2,failure.equals("unsealed")?"BUSY_REQUIRED":p.stage(),
                        p.workDeadlineNanos(),p.outerCutoffNanos(),2,0,1,true,true,p.cleanupDeadlineNanos(),1,1,p.acknowledgement(),p.primary(),p.replay(),true);
                if(failure.equals("changedSeal")) f.projection=f.withRefusals(0);
                f.observation=idle(f.run);
            }
            f.tick(3); f.assertFailed(); assertEquals(1,f.ackAttempts); assertEquals(3,f.input.commands.size());
        });
    }

    @Test void expiryReset3RemainsAnOrdinaryFreshSuccessfulReset() {
        for(var failure:List.of("run","failure","operation","directory","summary","evidence","settled")) expiry(f -> {
            f.start(); f.busy(); f.until(() -> f.ackAttempts==1); f.observation=idle(f.run); f.tick();
            f.until(() -> f.input.commands.size()==4);
            var c=f.reset3;
            f.observation=idle(new PublishedCompletion(c.sessionId(),failure.equals("operation")?f.operation:c.operationId(),3,Command.RESET,
                    failure.equals("run")?Optional.of(f.runId):Optional.empty(),"PASS",!failure.equals("evidence"),"RELEASED",!failure.equals("settled"),
                    failure.equals("directory")?f.run.evidenceDirectory():c.evidenceDirectory(),failure.equals("summary")?f.run.summaryPath():c.summaryPath(),
                    failure.equals("failure")?Optional.of(f.timeout):Optional.empty()));
            f.tick(); f.assertFailed(); assertEquals(4,f.input.commands.size());
        });
    }

    @Test void expiryGateCaseStoppingAndInvalidEntryRetireLocalActivityDuringWorkChatAndAck() {
        for(var point:List.of("work","chat","ack")) for(var change:List.of("gate","case","stop","entry")) expiry(f -> {
            f.start();
            if(!point.equals("work")) f.busy();
            if(point.equals("ack")) f.tick(2);
            int commands=f.input.commands.size(),keys=f.input.events.size();
            switch(change) {
                case "gate" -> f.enabled=false;
                case "case" -> System.setProperty("mapart.test.inspect.acceptance.case","baseline");
                case "stop" -> f.driver.releaseOnStopping(null);
                case "entry" -> f.input.entryRefusal="wrong thread/current client";
            }
            f.tick(3);
            assertEquals(commands,f.input.commands.size(),point+change); assertEquals(keys,f.input.events.size(),point+change);
            assertEquals(0,f.ackAttempts,point+change);
            if(!change.equals("stop")) assertTrue(f.logs.stream().anyMatch(s -> s.contains("DRIVER_FAIL")),point+change+f.logs);
            if(change.equals("entry") || change.equals("stop")) assertEquals(0,f.input.stops);
        });
    }

    @Test void expiryRequiresSealedProofExactFailedRunEvidenceAndStablePublicationBeforeWorkerIdle() {
        for(var field:List.of("session","operation","run","verdict","class","message","cancelled","stale","complete","settled","cleanup","directory","summary","early","pending")) expiry(f -> {
            f.start(); f.busy();
            if(!field.equals("early")) f.until(() -> f.ackAttempts==1);
            var c=f.run;
            var bad=new PublishedCompletion(field.equals("session")?UUID.randomUUID():c.sessionId(),field.equals("operation")?UUID.randomUUID():c.operationId(),2,Command.RUN,
                    field.equals("run")?Optional.of(UUID.randomUUID()):c.runId(),field.equals("verdict")?"PASS":"FAIL",!field.equals("complete"),
                    field.equals("cleanup")?"UNRESOLVED":"RELEASED",!field.equals("settled"),field.equals("directory")?f.reset.evidenceDirectory():c.evidenceDirectory(),
                    field.equals("summary")?f.reset.summaryPath():c.summaryPath(),Optional.of(new FailureObservation(field.equals("class") || field.equals("cancelled") || field.equals("stale")?"java.lang.IllegalStateException":f.timeout.className(),
                    field.equals("message") || field.equals("cancelled") || field.equals("stale")?field:f.timeout.message())));
            if(field.equals("pending")) {
                f.observation=new InspectObservation(true,true,false,Optional.of(c)); f.tick();
                bad=new PublishedCompletion(c.sessionId(),c.operationId(),2,c.command(),c.runId(),c.verdict(),true,"RELEASED",true,c.evidenceDirectory(),"changed-summary",c.failure());
            }
            f.observation=idle(bad); f.tick(); f.assertFailed(); assertEquals(field.equals("early")?2:3,f.input.commands.size());
        });
    }

    private static void expiry(java.util.function.Consumer<ExpiryFixture> test) {
        var previous=System.getProperty("mapart.test.inspect.acceptance.case");
        try { System.setProperty("mapart.test.inspect.acceptance.case","work-expiry"); test.accept(new ExpiryFixture()); }
        finally { if(previous==null) System.clearProperty("mapart.test.inspect.acceptance.case"); else System.setProperty("mapart.test.inspect.acceptance.case",previous); }
    }

    /** Immutable owner/input doubles drive the actual policy; they establish no native expiry/effect evidence. */
    private static final class ExpiryFixture implements InspectAcceptanceDriver.ExpiryClaims {
        final AtomicLong now=new AtomicLong(1);
        final long work=600_000_000_000L;
        final UUID session=UUID.randomUUID(),operation=UUID.randomUUID(),runId=UUID.randomUUID();
        final FailureObservation timeout=new FailureObservation("com.mapartrunner.fabric.scenario.ScenarioHost$ScopeTimeoutException","Inspect scope absolute deadline expired");
        final PublishedCompletion reset=new PublishedCompletion(session,UUID.randomUUID(),1,Command.RESET,Optional.empty(),"PASS",true,"RELEASED",true,"reset1","reset1/summary",Optional.empty());
        final PublishedCompletion run=new PublishedCompletion(session,operation,2,Command.RUN,Optional.of(runId),"FAIL",true,"RELEASED",true,"run2","run2/summary",Optional.of(timeout));
        final PublishedCompletion reset3=new PublishedCompletion(session,UUID.randomUUID(),3,Command.RESET,Optional.empty(),"PASS",true,"RELEASED",true,"reset3","reset3/summary",Optional.empty());
        final List<String> logs=new ArrayList<>();
        InspectObservation observation=idle(null);
        ExpiryObservation projection;
        boolean enabled=true,recordRefusal=true,ackThrow,ackReenter,ackApplyThenThrow,keepBusyScreen;
        int neutralChecks,ackAttempts,ackFalse;
        Runnable onBusyEnter;
        ExpiryBusyAck candidate,ack;
        final SimulatedChat input=new SimulatedChat() {
            @Override public void key(int code,boolean press,Object expected) {
                super.key(code,press,expected);
                if(code==84 && press) { screen=chat; draft=""; }
                if(code==257 && press && commands.size()==3) {
                    if(recordRefusal) projection=withRefusals(1);
                    if(keepBusyScreen) screen=chat;
                    if(onBusyEnter!=null) onBusyEnter.run();
                }
            }
            @Override public void requireNeutral() { neutralChecks++; super.requireNeutral(); }
        };
        final InspectAcceptanceDriver driver=new InspectAcceptanceDriver(() -> observation,input,now::get,() -> enabled,logs::add,this);
        ExpiryFixture() { input.now=now; }
        void tick() { driver.tick(null); }
        void tick(int count) { for(int i=0;i<count;i++) tick(); }
        void until(java.util.function.BooleanSupplier done) { for(int i=0;i<30 && !done.getAsBoolean();i++) tick(); assertTrue(done.getAsBoolean(),logs.toString()); }
        void start() {
            until(() -> input.commands.size()==1); observation=idle(reset); tick();
            until(() -> input.commands.size()==2); observation=new InspectObservation(true,true,false,Optional.of(reset)); tick();
        }
        void busyProjection() { now.set(work+1); projection=project("BUSY_REQUIRED",1,0,null); }
        void busy() { busyProjection(); tick(); }
        ExpiryObservation project(String stage,long request,int refusals,ExpiryBusyAck ack) {
            return new ExpiryObservation(session,operation,runId,2,stage,work,work+10_000_000_000L,
                    request==0?1:2,0,request==0?0:1,request!=0,request!=0,request==0?0:work+7_000_000_000L,request,refusals,ack,
                    request==0?null:timeout,request==0?null:new FailureObservation(timeout.className(),timeout.message()),request!=0);
        }
        ExpiryObservation withRefusals(int count) {
            var p=projection; return new ExpiryObservation(p.sessionId(),p.operationId(),p.runId(),p.ordinal(),p.stage(),p.workDeadlineNanos(),p.outerCutoffNanos(),
                    p.wrapperEntries(),p.actionEntries(),p.replayCount(),p.replayRejected(),p.boundaryRetired(),p.cleanupDeadlineNanos(),p.requestOrdinal(),count,p.acknowledgement(),p.primary(),p.replay(),p.distinctReplayFailure());
        }
        public ExpiryObservation observe() { return projection; }
        public boolean acknowledge(MinecraftClient client,ExpiryBusyAck value) {
            ackAttempts++; if(candidate==null) candidate=value; else assertSame(candidate,value);
            if(ackReenter) driver.tick(null);
            if(ackThrow) throw new IllegalStateException("uncertain ack application");
            if(ackAttempts<=ackFalse) return false;
            ack=value; projection=project("SEALED_BEFORE_END",1,1,ack);
            if(ackApplyThenThrow) throw new IllegalStateException("ack applied then threw");
            return true;
        }
        void assertHealthy() { assertTrue(logs.stream().noneMatch(s -> s.contains("DRIVER_FAIL")),logs.toString()); assertEquals(0,input.stops); }
        void assertFailed() { assertEquals(1,logs.stream().filter(s -> s.contains("DRIVER_FAIL")).count(),logs.toString()); }
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1FixedSevenChatsRetainTwoPositiveCompletionsAndStopOnlyAfterLastHandoff(String selected) {
        n1(selected,f -> {
            f.start(); f.settle();
            f.until(() -> f.input.commands.size()==7);
            assertEquals(0,f.input.stops); f.tick(); assertEquals(0,f.input.stops);
            f.tick(); assertEquals(1,f.input.stops); f.tick(5);
            assertEquals(List.of("/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest reset grounded-two-lane",
                    "/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane","/maparttest rerun grounded-two-lane","/maparttest last"),f.input.commands);
            assertEquals(2,f.logs.stream().filter(s -> s.contains("RESULT command=")).count());
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("ordinal=3") || s.contains("DRIVER_FAIL")),f.logs.toString());
            assertEquals(f.run,f.observation.last().orElseThrow());
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1AuthenticatesEveryDetachedFieldBeforeAndAfterTheFirstSnapshot(String selected) {
        var fields=List.of("absent","state","admission","intent","close","outputAttempted","outputSucceeded","case","session","operation","reusedOperation",
                "directory","reusedDirectory","longDirectory","reserved","reset","starts","trigger","attempts","collision","header","link","host","scope","absentScope","cancelled","hostOrdinal",
                "prior","priorOperation","priorRun","hash","files","lastSame","watch","samples","witnesses","failure","class","cause","diagnostics","primary");
        for(boolean changed:List.of(false,true)) for(var field:fields) n1(selected,f -> {
            f.start(); f.settle(); if(changed) f.tick();
            f.projection=corruptN1(f.projection,field,f);
            f.tick(5); f.failed(); assertEquals(3,f.input.commands.size(),field+" changed="+changed); assertEquals(1,f.input.stops);
        });
        for(var field:List.of("hashChanged","messageChanged","causeMessageChanged","timeChanged","newOperation","newDirectory")) n1(selected,f -> {
            f.start(); f.settle(); f.tick(); f.projection=corruptN1(f.projection,field,f);
            f.tick(); f.failed(); assertEquals(3,f.input.commands.size(),field);
        });
    }

    private static final String SYNTHETIC_N1_PREFIX="[source-fixture/INFO]: ";
    private static final String N1_COMMON="schema:s,event:s,seq:i,case:s,sessionId:u,operationId:u,ordinal:i,command:s,occurredNanos:t,emittedNanos:t,diagnosticsComplete:b";
    private static final Map<String,String> N1_SHAPES=Map.ofEntries(
            Map.entry("ADMISSION_FAILED",N1_COMMON+",operationDirectory:p,reservedOrdinal:i,resetOrdinal:i,countedStarts:i,trigger:trigger,host:host,prior:prior,failure:failure,primaryIdentityRetained:b"),
            Map.entry("EXIT_REQUESTED",N1_COMMON+",workerIdleAtIntent:b,exitDeadlineNanos:t,primaryIdentityRetained:b"),
            Map.entry("CLOSE_FINAL",N1_COMMON+",intentNanos:t,exitDeadlineNanos:t,cleanupIssuedNanos:?t,cleanupDeadlineNanos:?t,shutdown:shutdown,reservedOrdinal:i,resetOrdinal:i,countedStarts:i,retainedLastSame:b,primaryIdentityRetained:b,release:release,freeze:freeze,options:options,close:close,failure:failure,priorRowsOutputChecked:b"),
            Map.entry("trigger","attempts:i,collisionCreated:b,headerWriteEntered:b,afterLinkReached:b"),
            Map.entry("host","ordinaryScopeIssued:b,currentScopeAbsent:b,cancelled:b,lastOrdinal:i"),
            Map.entry("prior","operationId:u,runId:u,summarySha256:h,closedFilesVerified:b,lastSame:b,watchSame:b,samples:i,witnesses:i"),
            Map.entry("failure","className:c,message:?m,causeClassName:?c,causeMessage:?m"),
            Map.entry("shutdown","operationId:?u,kind:?s,issues:i"),
            Map.entry("release","invocations:i,guard:g,beforeKeys:?i,afterKeys:?i,beforeOwner:?s,afterOwner:?s,error:?failure"),
            Map.entry("freeze","invocations:i,guard:g,watchSame:?b,error:?failure"),
            Map.entry("options","helperInvocations:i,autoJumpSetCalls:i,viewDistanceSetCalls:i,settingsSendCalls:i,guard:g,ownedBefore:?b,ownedAfter:?b,savedAutoJump:?b,savedViewDistance:?j,beforeAutoJump:?b,afterAutoJump:?b,beforeViewDistance:?j,afterViewDistance:?j,error:?failure"),
            Map.entry("close","invocations:i,guard:g,serverCheckpoint:g,returned:b,worldCleared:b,error:?failure"));

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1SourceReconstructionJoinsExactFixedSchemasAndSignedLongTimes(String selected) {
        n1(selected,f -> {
            for(long start:new long[]{9_007_199_254_740_993L,Long.MAX_VALUE-2,Long.MIN_VALUE}) {
                var wire=n1Wire(f,start); var records=n1Records(wire);
                var reconstructed=reconstructN1(records,f.run);
                assertEquals(wire,reconstructed);
                var crlf=records.stream().map(bytes -> new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\n","\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toList();
                assertEquals(wire,reconstructN1(crlf,f.run));
                assertTrue(ScenarioRunner.n1Positive(reconstructed.admission(),reconstructed.intent(),reconstructed.close(),start+4));
                var finalRow=readN1Record(records.get(2),"CLOSE_FINAL");
                assertTrue(finalRow.get("priorRowsOutputChecked").getAsBoolean());
                assertFalse(finalRow.has("finalOutputChecked"),"Previous rows never attest to the final flush");
            }
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1SourceReconstructionRejectsMissingRepeatedReorderedMalformedAndIncompatiblePhysicalRecords(String selected) {
        n1(selected,f -> {
            var wire=n1Wire(f,9_007_199_254_740_993L); var records=n1Records(wire);
            var badSets=new ArrayList<List<byte[]>>();
            badSets.add(records.subList(0,2)); badSets.add(List.of(records.get(0),records.get(1),records.get(2),records.get(2)));
            badSets.add(List.of(records.get(0),records.get(0),records.get(2))); badSets.add(List.of(records.get(1),records.get(0),records.get(2)));
            var source=new String(records.getFirst(),java.nio.charset.StandardCharsets.UTF_8);
            for(var changed:List.of(
                    source.replace("\"schema\":","\"schema\":\"duplicate\",\"schema\":"),
                    source.replace("\"schema\":","\"unknown\":null,\"schema\":"),
                    source.replace("\"seq\":1,",""),source.replace("\"seq\":1","\"seq\":\"1\""),source.replace("\"seq\":1","\"seq\":1.0"),
                    source.replace("\"ordinal\":3","\"ordinal\":2147483648"),source.replace("\"diagnosticsComplete\":true","\"diagnosticsComplete\":\"true\""),
                    source.replace("\"causeMessage\":", "\"causeMessage\":false,\"unused\":"),
                    source.replace("\"9007199254740993\"","9007199254740993"),source.replace("\"9007199254740993\"","\"9223372036854775808\""),
                    source.replace("\"9007199254740993\"","\"+9007199254740993\""),source.replace("\"9007199254740993\"","\"09007199254740993\""),
                    source.replace("\"9007199254740993\"","\"-0\""),source.replace("\"reservedOrdinal\":3","\"reservedOrdinal\":4"),
                    source.replace("\"attempts\":1","\"attempts\":2"),source.replace("\"headerWriteEntered\":true","\"headerWriteEntered\":false"),
                    source.replace("\"closedFilesVerified\":true","\"closedFilesVerified\":false"),source.replace("\"failure\":{","\"failure\":null,\"extra\":{"),
                    source.replace("\"case\":\""+selected+"\"","\"case\":\"baseline\""),source.replace("\"operationId\":\""+wire.admission().operationId(),"\"operationId\":\""+UUID.randomUUID()),
                    source.replace("\"sessionId\":\""+f.session,"\"sessionId\":\""+UUID.randomUUID()),source.replace("\"command\":\"RESET\"","\"command\":\"RUN\""),
                    source.replace("MAPART_N1_V1 ","[CHAT] MAPART_N1_V1 "),source.replace(SYNTHETIC_N1_PREFIX,"[unverified/INFO]: "),
                    source.replace("MAPART_N1_V1 ","\"MAPART_N1_V1 "),source.replace("MAPART_N1_V1 ","\uFEFFMAPART_N1_V1 "),
                    source.substring(0,source.length()-2)+"\n",source.substring(0,source.length()-1),source.replace("{", "{\n"),source+source,
                    source.replace(SYNTHETIC_N1_PREFIX,"x".repeat(1025)),source+"x".repeat(10241))) {
                badSets.add(List.of(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8),records.get(1),records.get(2)));
            }
            byte[] invalid=records.getFirst().clone(); invalid[invalid.length-5]=(byte)0xc3; invalid[invalid.length-4]=(byte)0x28;
            badSets.add(List.of(invalid,records.get(1),records.get(2)));
            int split=records.getFirst().length/2;
            badSets.add(List.of(Arrays.copyOfRange(records.getFirst(),0,split),Arrays.copyOfRange(records.getFirst(),split,records.getFirst().length),records.get(1),records.get(2)));
            for(var bad:badSets) assertThrows(AssertionError.class,() -> reconstructN1(bad,f.run));
            var finalSource=new String(records.get(2),java.nio.charset.StandardCharsets.UTF_8);
            for(var changed:List.of(finalSource.replace("\"diagnosticsComplete\":true","\"diagnosticsComplete\":false"),
                    finalSource.replace("\"afterKeys\":0","\"afterKeys\":null"),
                    finalSource.replace("\"cleanupIssuedNanos\":\"9007199254740995\"","\"cleanupIssuedNanos\":null"),
                    finalSource.replace("\"error\":null","\"error\":{\"className\":\"java.lang.Error\",\"message\":null,\"causeClassName\":null,\"causeMessage\":null}"))) {
                assertNotEquals(finalSource,changed);
                assertThrows(AssertionError.class,() -> reconstructN1(List.of(records.get(0),records.get(1),changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),f.run));
            }
            for(var target:List.of("\"guard\":\"PASSED\"","\"returned\":true","\"afterKeys\":0","\"ownedAfter\":false","\"priorRowsOutputChecked\":true")) {
                var end=new String(records.get(2),java.nio.charset.StandardCharsets.UTF_8);
                var changed=end.replace(target,target.contains("PASSED")?"\"guard\":\"REFUSED\"":target.contains("afterKeys")?"\"afterKeys\":1":target.replace("true","false").replace("ownedAfter\":false","ownedAfter\":true"));
                assertNotEquals(end,changed); assertThrows(AssertionError.class,() -> reconstructN1(List.of(records.get(0),records.get(1),changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),f.run));
            }
        });
    }

    private static List<byte[]> n1Records(N1Wire w) {
        var output=new java.io.ByteArrayOutputStream(); var errors=new java.io.ByteArrayOutputStream();
        assertTrue(ScenarioRunner.emitN1Rows(w.admission(),w.intent(),w.close(),new java.io.PrintStream(output),new java.io.PrintStream(errors),() -> w.close().occurredNanos()+1));
        assertEquals(0,errors.size());
        return output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().map(row -> (SYNTHETIC_N1_PREFIX+row+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toList();
    }

    /** Exactly the published three schemas in a synthetic known envelope; never a runtime log reader. */
    private static JsonObject readN1Record(byte[] physical,String event) {
        try {
            assertTrue(physical.length<=10240); assertTrue(SYNTHETIC_N1_PREFIX.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1024);
            var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var text=decoder.decode(java.nio.ByteBuffer.wrap(physical)).toString();
            assertTrue(text.startsWith(SYNTHETIC_N1_PREFIX+"MAPART_N1_V1 ")); assertTrue(text.endsWith("\n"));
            var row=text.substring(SYNTHETIC_N1_PREFIX.length()); if(row.endsWith("\r\n")) row=row.substring(0,row.length()-2)+"\n"; assertTrue(row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=8192);
            assertEquals(1,row.chars().filter(c -> c=='\n').count()); assertFalse(row.contains("\r"));
            var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(row.substring("MAPART_N1_V1 ".length(),row.length()-1)));
            reader.setStrictness(com.google.gson.Strictness.STRICT); var result=readN1Object(reader,event); assertEquals(com.google.gson.stream.JsonToken.END_DOCUMENT,reader.peek());
            assertEquals("mapartrunner-n1-native-proof-v1",result.get("schema").getAsString()); assertEquals(event,result.get("event").getAsString());
            assertEquals(3,result.get("ordinal").getAsInt()); assertEquals("RESET",result.get("command").getAsString());
            assertTrue(Set.of("n1-header","n1-post-link").contains(result.get("case").getAsString()));
            return result;
        } catch(java.io.IOException | IllegalArgumentException | IllegalStateException failure) { throw new AssertionError("Invalid synthetic N1 record",failure); }
    }

    private static JsonObject readN1Object(com.google.gson.stream.JsonReader reader,String shape) throws java.io.IOException {
        reader.beginObject(); var result=new JsonObject();
        for(var declaration:N1_SHAPES.get(shape).split(",")) {
            var pair=declaration.split(":"); String key=pair[0],type=pair[1]; assertTrue(reader.hasNext()); assertEquals(key,reader.nextName());
            boolean nullable=type.startsWith("?"); if(nullable) type=type.substring(1);
            var token=reader.peek();
            if(nullable && token==com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); result.add(key,com.google.gson.JsonNull.INSTANCE); }
            else if(N1_SHAPES.containsKey(type)) result.add(key,readN1Object(reader,type));
            else if(type.equals("b")) { assertEquals(com.google.gson.stream.JsonToken.BOOLEAN,token); result.addProperty(key,reader.nextBoolean()); }
            else if(type.equals("i") || type.equals("j")) {
                assertEquals(com.google.gson.stream.JsonToken.NUMBER,token); var text=reader.nextString(); assertTrue(text.matches("-?(0|[1-9][0-9]*)") && !text.equals("-0"));
                int value=Integer.parseInt(text); if(type.equals("i")) assertTrue(value>=0); result.addProperty(key,value);
            } else {
                assertEquals(com.google.gson.stream.JsonToken.STRING,token); var text=reader.nextString();
                ScenarioRunner.n1Text(text,type.equals("p")?1024:type.equals("c")?256:type.equals("m")?512:type.equals("u")?36:64);
                if(type.equals("u")) assertEquals(UUID.fromString(text).toString(),text);
                if(type.equals("t")) assertEquals(Long.toString(Long.parseLong(text)),text);
                if(type.equals("h")) assertTrue(text.matches("[0-9a-fA-F]{64}"));
                if(key.equals("beforeOwner") || key.equals("afterOwner")) ControlOwner.valueOf(text);
                if(type.equals("g")) assertTrue(Set.of("UNOBSERVED","PASSED","REFUSED").contains(text));
                result.addProperty(key,text);
            }
        }
        assertFalse(reader.hasNext(),"Unknown, repeated or reordered key"); reader.endObject(); return result;
    }

    private static N1Wire reconstructN1(List<byte[]> records,PublishedCompletion prior) {
        assertEquals(3,records.size());
        var a=readN1Record(records.get(0),"ADMISSION_FAILED"); var i=readN1Record(records.get(1),"EXIT_REQUESTED"); var c=readN1Record(records.get(2),"CLOSE_FINAL");
        var rows=List.of(a,i,c); var gson=new com.google.gson.Gson();
        for(int n=0;n<3;n++) {
            var row=rows.get(n); assertEquals(n+1,row.get("seq").getAsInt()); assertTrue(row.get("diagnosticsComplete").getAsBoolean());
            for(var key:List.of("case","sessionId","operationId","ordinal","command")) assertEquals(a.get(key),row.get(key));
        }
        var admission=new N1Admission(a.get("case").getAsString(),UUID.fromString(a.get("sessionId").getAsString()),UUID.fromString(a.get("operationId").getAsString()),a.get("operationDirectory").getAsString(),time(a,"occurredNanos"),
                a.get("reservedOrdinal").getAsInt(),a.get("resetOrdinal").getAsInt(),a.get("countedStarts").getAsInt(),gson.fromJson(a.get("trigger"),N1Trigger.class),gson.fromJson(a.get("host"),N1Host.class),gson.fromJson(a.get("prior"),N1Prior.class),gson.fromJson(a.get("failure"),N1Failure.class),a.get("primaryIdentityRetained").getAsBoolean(),true);
        var intent=new N1Intent(admission.selected(),admission.sessionId(),admission.operationId(),time(i,"occurredNanos"),time(i,"exitDeadlineNanos"),i.get("workerIdleAtIntent").getAsBoolean());
        var shutdown=c.getAsJsonObject("shutdown");
        var issue=new N1Issue(shutdown.get("operationId").isJsonNull()?null:UUID.fromString(shutdown.get("operationId").getAsString()),shutdown.get("kind").isJsonNull()?null:shutdown.get("kind").getAsString(),shutdown.get("issues").getAsInt(),nullableTime(c,"cleanupIssuedNanos"),nullableTime(c,"cleanupDeadlineNanos"));
        var close=new N1Final(time(c,"occurredNanos"),issue,c.get("reservedOrdinal").getAsInt(),c.get("resetOrdinal").getAsInt(),c.get("countedStarts").getAsInt(),c.get("retainedLastSame").getAsBoolean(),c.get("primaryIdentityRetained").getAsBoolean(),gson.fromJson(c.get("release"),N1Release.class),gson.fromJson(c.get("freeze"),N1Freeze.class),gson.fromJson(c.get("options"),N1Options.class),gson.fromJson(c.get("close"),N1Close.class),gson.fromJson(c.get("failure"),N1Failure.class),true);
        assertEquals(prior.sessionId(),admission.sessionId()); assertNotEquals(prior.operationId(),admission.operationId());
        assertEquals(prior.operationId(),admission.prior().operationId()); assertEquals(prior.runId().orElseThrow(),admission.prior().runId());
        assertEquals(intent.intentNanos(),time(c,"intentNanos")); assertEquals(intent.deadlineNanos(),time(c,"exitDeadlineNanos"));
        assertTrue(i.get("primaryIdentityRetained").getAsBoolean()); assertTrue(c.get("priorRowsOutputChecked").getAsBoolean());
        for(int n=0;n<3;n++) {
            assertTrue(ScenarioRunner.n1Positive(admission,intent,close,time(rows.get(n),"emittedNanos")));
            var expected=(SYNTHETIC_N1_PREFIX+new String(ScenarioRunner.encodeN1Row(admission,intent,close,n+1,time(rows.get(n),"emittedNanos"),n==2),java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var original=records.get(n);
            if(original.length>=2 && original[original.length-2]=='\r') expected=new String(expected,java.nio.charset.StandardCharsets.UTF_8).replace("\n","\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertArrayEquals(expected,original,"Deterministic re-encoding rejects noncanonical or lost wire values");
        }
        return new N1Wire(admission,intent,close);
    }
    private static long time(JsonObject row,String key) { return Long.parseLong(row.get(key).getAsString()); }
    private static Long nullableTime(JsonObject row,String key) { return row.get(key).isJsonNull()?null:time(row,key); }

    private record N1Wire(N1Admission admission,N1Intent intent,N1Final close) { }
    private static N1Wire n1Wire(N1Fixture f,long start) {
        var a=f.admission();
        a=new N1Admission(a.selected(),a.sessionId(),a.operationId(),a.operationDirectory(),start,3,1,1,a.trigger(),a.host(),a.prior(),a.failure(),true,true);
        return new N1Wire(a,new N1Intent(a.selected(),a.sessionId(),a.operationId(),start+1,start+10_000_000_001L,true),
                new N1Final(start+3,new N1Issue(a.operationId(),"CLEANUP",1,start+2,start+10_000_000_002L),3,1,1,true,true,
                new N1Release(1,N1Guard.PASSED,0,0,"NONE","NONE",null),new N1Freeze(1,N1Guard.PASSED,true,null),
                new N1Options(1,1,0,0,N1Guard.PASSED,true,false,true,12,false,true,12,12,null),
                new N1Close(1,N1Guard.PASSED,N1Guard.PASSED,true,true,null),a.failure(),true));
    }

    private static N1Observation corruptN1(N1Observation o,String field,N1Fixture f) {
        if(field.equals("absent")) return null;
        var a=o.admission(); var t=a.trigger(); var h=a.host(); var p=a.prior(); var e=a.failure();
        var admission=new N1Admission(field.equals("case")?"baseline":a.selected(),field.equals("session")?UUID.randomUUID():a.sessionId(),
                field.equals("operation")?null:field.equals("reusedOperation")?f.run.operationId():field.equals("newOperation")?UUID.randomUUID():a.operationId(),
                field.equals("directory")?" ":field.equals("reusedDirectory")?f.run.evidenceDirectory():field.equals("longDirectory")?"x".repeat(1025):field.equals("newDirectory")?"new3":a.operationDirectory(),
                a.occurredNanos()+(field.equals("timeChanged")?1:0),field.equals("reserved")?4:a.reservedOrdinal(),field.equals("reset")?2:a.resetOrdinal(),field.equals("starts")?2:a.countedStarts(),
                field.equals("trigger")?null:new N1Trigger(field.equals("attempts")?2:t.attempts(),field.equals("collision")?!t.collisionCreated():t.collisionCreated(),!field.equals("header"),field.equals("link")?!t.afterLinkReached():t.afterLinkReached()),
                field.equals("host")?null:new N1Host(field.equals("scope"),!field.equals("absentScope"),!field.equals("cancelled"),field.equals("hostOrdinal")?3:2),
                field.equals("prior")?null:new N1Prior(field.equals("priorOperation")?UUID.randomUUID():p.operationId(),field.equals("priorRun")?UUID.randomUUID():p.runId(),
                field.equals("hash")?"bad":field.equals("hashChanged")?"b".repeat(64):p.summarySha256(),!field.equals("files"),!field.equals("lastSame"),!field.equals("watch"),field.equals("samples")?0:1,field.equals("witnesses")?0:1),
                field.equals("failure")?null:new N1Failure(field.equals("class")?"java.lang.Error":e.className(),field.equals("messageChanged")?"changed":e.message(),
                field.equals("cause")?f.selected.equals("n1-header")?null:"java.lang.Error":e.causeClassName(),field.equals("causeMessageChanged")?"changed":e.causeMessage()),!field.equals("primary"),!field.equals("diagnostics"));
        return new N1Observation(field.equals("state")?"CLOSED":o.state(),field.equals("admission")?null:admission,
                field.equals("intent")?new N1Intent(a.selected(),a.sessionId(),a.operationId(),1,10_000_000_001L,true):null,
                field.equals("close")?n1Wire(f,1).close():null,field.equals("outputAttempted"),field.equals("outputSucceeded")?true:null);
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1SeparatePublicationsAndNaturalClosureNeverSubstituteForWorkerSettlement(String selected) {
        for(boolean screenFirst:List.of(false,true)) n1(selected,f -> {
            f.keepScreen=true; f.start(); f.projection=new N1Observation("ADMISSION_FAILED",f.admission(),null,null,false,null);
            f.observation=new InspectObservation(true,true,false,Optional.of(f.run)); f.tick(3);
            assertEquals(3,f.input.commands.size()); assertEquals(0,f.input.stops);
            f.observation=new InspectObservation(false,true,true,Optional.of(f.run));
            if(screenFirst) f.input.screen=null;
            f.tick(3); assertEquals(3,f.input.commands.size()); assertEquals(0,f.input.stops);
            f.observation=new InspectObservation(false,false,true,Optional.of(f.run)); f.tick();
            assertEquals(3,f.input.commands.size()); assertEquals(0,f.input.stops);
            f.input.screen=null; f.keepScreen=false; f.until(() -> f.input.commands.size()==7); f.tick(2);
            assertEquals(1,f.input.stops); f.healthy();
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1OriginalReset3AndEachDistinctChatDeadlineCannotRenew(String selected) {
        for(var point:List.of("resetOpen","resetType","resetHandoff","resetBusy","laterOpen","laterType","laterHandoff")) n1(selected,f -> {
            f.until(() -> f.input.commands.size()==1); f.observation=idle(f.reset); f.tick();
            f.until(() -> f.input.commands.size()==2); f.observation=idle(f.run); f.tick();
            if(point.equals("resetOpen")) { f.now.set(11); f.tick(); f.input.screen=null; f.now.set(30_000_000_011L); }
            else if(point.equals("resetType")) { f.now.set(11); f.tick(); f.onCharacter=() -> f.now.set(30_000_000_011L); }
            else {
                f.now.set(11); f.keepScreen=point.equals("resetHandoff"); f.until(() -> f.input.commands.size()==3); f.settle();
                if(point.equals("resetBusy")) f.observation=new InspectObservation(false,true,true,Optional.of(f.run));
                if(point.startsWith("reset")) { f.tick(); f.now.set(30_000_000_011L); }
                else {
                    f.tick(); f.now.set(101); f.tick();
                    if(point.equals("laterOpen")) f.input.screen=null;
                    else if(point.equals("laterType")) f.onCharacter=() -> f.now.set(30_000_000_101L);
                    else { f.keepScreen=true; f.tick(); }
                    if(!point.equals("laterType")) f.now.set(30_000_000_101L);
                }
            }
            f.tick(3); f.failed(); assertTrue(f.logs.stream().anyMatch(s -> s.contains("deadline expired")),point+f.logs);
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1PositiveRun2KeepsTheExistingWorkWaitAndReset3GetsItsOwnInputOrigin(String selected) {
        n1(selected,f -> {
            f.until(() -> f.input.commands.size()==1); f.observation=idle(f.reset); f.tick();
            f.until(() -> f.input.commands.size()==2); f.observation=new InspectObservation(true,true,false,Optional.of(f.reset)); f.tick();
            f.now.set(700_000_000_000L); f.tick(3); f.healthy(); assertEquals(2,f.input.commands.size());
            f.observation=idle(f.run); f.tick(); f.until(() -> f.input.commands.size()==3); f.settle();
            f.until(() -> f.input.commands.size()==7); f.tick(2); f.healthy(); assertEquals(1,f.input.stops);
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1InputBoundariesRecheckGatesTerminalIdentityAndNativeGuards(String selected) {
        for(var point:List.of("open","character","enter","handoff","stop")) for(var field:List.of("gate","case","busy","ready","restart","last","binding","entry","screen","draft","neutral")) n1(selected,f -> {
            f.start(); f.settle(); f.tick();
            if(point.equals("stop")) { f.until(() -> f.input.commands.size()==7); f.tick(); }
            else if(!point.equals("open")) { f.tick(); if(point.equals("handoff")) f.tick(); }
            Runnable change=() -> {
                switch(field) {
                    case "gate" -> f.enabled=false;
                    case "case" -> System.setProperty("mapart.test.inspect.acceptance.case","baseline");
                    case "busy" -> f.observation=new InspectObservation(false,true,true,Optional.of(f.run));
                    case "ready" -> f.observation=new InspectObservation(true,false,true,Optional.of(f.run));
                    case "restart" -> f.observation=new InspectObservation(false,false,false,Optional.of(f.run));
                    case "last" -> f.observation=new InspectObservation(false,false,true,Optional.of(f.reset));
                    case "binding" -> f.input.refusal="window/focus/pause/world binding refused";
                    case "entry" -> f.input.entryRefusal="wrong client/thread";
                    case "screen" -> f.input.screen=new Object();
                    case "draft" -> { f.input.screen=f.input.chat; f.input.draft="foreign draft"; }
                    case "neutral" -> f.input.neutral=false;
                }
            };
            // Entry checks are callback boundaries; neutral checks belong to null-screen boundaries.
            if((point.equals("character") || point.equals("enter")) && (field.equals("entry") || field.equals("neutral"))) return;
            if(point.equals("character")) f.onCharacter=change;
            else if(point.equals("enter")) f.onCharacter=() -> { if(f.input.draft.equals("/maparttest reset grounded-two-lane")) change.run(); };
            else change.run();
            int sent=f.input.commands.size(); f.tick(3); f.failed(); assertEquals(sent,f.input.commands.size(),point+"/"+field);
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1CallbacksConsumeKeySendAndStopDebtBeforeReentrancyOrApplyThenThrow(String selected) {
        for(var fault:List.of("char","257+","257-","84+","84-","stop","reenter","stopping")) n1(selected,f -> {
            f.start(); f.settle(); f.tick();
            f.input.reenter=() -> f.driver.tick(null);
            if(fault.equals("stopping")) { f.driver.releaseOnStopping(null); f.tick(5); assertEquals(3,f.input.commands.size()); assertEquals(0,f.input.stops); return; }
            if(fault.equals("stop")) f.stopThrows=true;
            else if(!fault.equals("reenter")) f.input.throwAt=fault;
            f.tick(30);
            assertEquals(fault.startsWith("84") || fault.equals("char")?3:fault.startsWith("257")?4:7,f.input.commands.size(),fault);
            assertEquals(1,f.input.stops,fault);
            assertEquals(f.input.events.stream().filter(s -> s.equals("257+")).count(),f.input.events.stream().filter(s -> s.equals("257-")).count());
            assertEquals(f.input.events.stream().filter(s -> s.equals("84+")).count(),f.input.events.stream().filter(s -> s.equals("84-")).count());
            if(fault.equals("reenter")) f.healthy(); else f.failed();
        });
    }

    @ParameterizedTest @ValueSource(strings={"n1-header","n1-post-link"})
    void n1PreludeRejectsFailureAndPrematureTerminalSnapshotsAndRetainsPositiveBusyResults(String selected) {
        for(int ordinal:List.of(1,2)) for(var change:List.of("fail","terminal","busy")) n1(selected,f -> {
            f.until(() -> f.input.commands.size()==1);
            if(ordinal==2) { f.observation=idle(f.reset); f.tick(); f.until(() -> f.input.commands.size()==2); }
            var c=ordinal==1?f.reset:f.run;
            if(change.equals("fail")) c=new PublishedCompletion(c.sessionId(),c.operationId(),c.ordinal(),c.command(),c.runId(),"FAIL",true,"RELEASED",true,c.evidenceDirectory(),c.summaryPath(),Optional.of(new FailureObservation("java.lang.AssertionError","Run requires this invocation's successful reset")));
            f.projection=new N1Observation("ADMISSION_FAILED",f.admission(),null,null,false,null);
            f.observation=new InspectObservation(!change.equals("terminal"),change.equals("busy"),change.equals("terminal"),Optional.of(c));
            f.tick(3); assertEquals(ordinal,f.input.commands.size());
            if(change.equals("busy")) { f.observation=idle(c); f.until(() -> f.input.commands.size()==ordinal+1); f.healthy(); }
            else f.failed();
        });
    }

    private static void n1(String selected,java.util.function.Consumer<N1Fixture> action) {
        var old=System.getProperty("mapart.test.inspect.acceptance.case");
        try { System.setProperty("mapart.test.inspect.acceptance.case",selected); action.accept(new N1Fixture(selected)); }
        finally { if(old==null) System.clearProperty("mapart.test.inspect.acceptance.case"); else System.setProperty("mapart.test.inspect.acceptance.case",old); }
    }

    /** Detached synthetic source values only; no native owner or acceptance evidence is created. */
    private static final class N1Fixture {
        final String selected;
        final AtomicLong now=new AtomicLong(1);
        final UUID session=UUID.randomUUID(),operation=UUID.randomUUID();
        final PublishedCompletion reset=new PublishedCompletion(session,UUID.randomUUID(),1,Command.RESET,Optional.empty(),"PASS",true,"RELEASED",true,"reset1","reset1/summary",Optional.empty());
        final PublishedCompletion run=new PublishedCompletion(session,UUID.randomUUID(),2,Command.RUN,Optional.of(UUID.randomUUID()),"PASS",true,"RELEASED",true,"run2","run2/summary",Optional.empty());
        InspectObservation observation=idle(null); N1Observation projection;
        final List<String> logs=new ArrayList<>(); boolean enabled=true,keepScreen,stopThrows; Runnable onInput,onCharacter;
        final SimulatedChat input=new SimulatedChat() {
            @Override public void key(int code,boolean press,Object expected) {
                super.key(code,press,expected);
                if(code==84 && press) { screen=chat; draft=""; }
                if(code==257 && press && keepScreen && commands.size()>=3) screen=chat;
                if(onInput!=null) onInput.run();
            }
            @Override public void character(int c) { super.character(c); if(onCharacter!=null) onCharacter.run(); }
            @Override public void stop() { super.stop(); if(stopThrows) throw new IllegalStateException("stop applied then threw"); }
        };
        final InspectAcceptanceDriver driver=new InspectAcceptanceDriver(() -> observation,input,now::get,() -> enabled,logs::add,
                ignored -> { throw new AssertionError("N1 must not request acceptance idle authority"); },ignored -> { throw new AssertionError("No Watch seam"); },() -> projection);
        N1Fixture(String selected) { this.selected=selected; input.now=now; }
        void tick() { driver.tick(null); } void tick(int n) { for(int i=0;i<n;i++) tick(); }
        void until(java.util.function.BooleanSupplier done) { for(int i=0;i<40 && !done.getAsBoolean();i++) tick(); assertTrue(done.getAsBoolean(),logs.toString()); }
        void start() {
            until(() -> input.commands.size()==1); observation=idle(reset); tick();
            until(() -> input.commands.size()==2); observation=idle(run); tick();
            until(() -> input.commands.size()==3);
        }
        N1Admission admission() {
            boolean header=selected.equals("n1-header");
            return new N1Admission(selected,session,operation,"reset3",now.get(),3,1,1,new N1Trigger(1,header,true,!header),new N1Host(false,true,true,2),
                    new N1Prior(run.operationId(),run.runId().orElseThrow(),"a".repeat(64),true,true,true,1,1),
                    header?new N1Failure("java.io.UncheckedIOException","platform header text","java.nio.file.FileSystemException","platform cause text")
                    :new N1Failure("com.mapartrunner.fabric.scenario.ScenarioLaunchReceipt$SimulatedN1PostLinkFailure","SIMULATED_N1_AFTER_BEGIN_LINK_BEFORE_RETURN",null,null),true,true);
        }
        void settle() { projection=new N1Observation("ADMISSION_FAILED",admission(),null,null,false,null); observation=new InspectObservation(false,false,true,Optional.of(run)); }
        void healthy() { assertTrue(logs.stream().noneMatch(s -> s.contains("DRIVER_FAIL")),logs.toString()); }
        void failed() { assertEquals(1,logs.stream().filter(s -> s.contains("DRIVER_FAIL")).count(),logs.toString()); }
    }

    private static InspectObservation idle(PublishedCompletion completion) { return new InspectObservation(true, false, false, Optional.ofNullable(completion)); }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(); final SimulatedChat input = new SimulatedChat();
        final List<String> logs = new ArrayList<>(); final UUID session = UUID.randomUUID();
        final UUID[] operations = new UUID[8], runs = new UUID[8];
        InspectObservation observation = idle(null); boolean enabled = true, displaced, resetArchive; String guardFailure;
        String boundaryFailure; int idleReads;
        ScenarioRunner.InspectIdleObservation retainedObserver = new ScenarioRunner.InspectIdleObservation(new ScenarioObserver.Watch().retained());
        final InspectAcceptanceDriver driver = new InspectAcceptanceDriver(() -> observation, input, now::get, () -> enabled, logs::add,
                expected -> { if (guardFailure != null) throw new IllegalStateException(guardFailure); assertEquals(observation.last().orElseThrow(), expected); assertFalse(observation.busy()); },
                expected -> { idleReads++; if (boundaryFailure!=null) throw new IllegalStateException(boundaryFailure); return retainedObserver; });
        Fixture() { Arrays.setAll(operations, i -> UUID.randomUUID()); Arrays.setAll(runs, i -> UUID.randomUUID()); input.now=now; }
        void tick() { driver.tick(null); } void tick(int count) { for (int i = 0; i < count; i++) tick(); }
        void openChat() { input.screen = input.chat; input.draft = ""; }
        void send() { tick(); if (input.screen == null) openChat(); tick(); }
        void sendCompletion(int ordinal) { send(); observation = idle(completion(ordinal)); tick(); }
        void beginForward() { sendCompletion(1); sendCompletion(2); assertFalse(input.pressed); tick(); }
        void beginFailedIdle() { beginForward(); input.x=0.5; tick(); sendCompletion(3); assertEquals(1,idleReads); }
        void finishIdle() { now.addAndGet(600_000_000_001L); tick(6002); }
        List<JsonObject> idleMarkers() { return logs.stream().filter(s -> s.startsWith("INSPECT_ACCEPTANCE_IDLE "))
                .map(s -> JsonParser.parseString(s.substring("INSPECT_ACCEPTANCE_IDLE ".length())).getAsJsonObject()).toList(); }
        void assertFailed(String reason) {
            assertEquals(1, input.stops, logs.toString());
            assertEquals(1, logs.stream().filter(s -> s.contains("DRIVER_FAIL")).count(), logs.toString());
            assertTrue(logs.stream().anyMatch(s -> s.contains(reason)), logs.toString());
            assertTrue(logs.stream().allMatch(s -> s.length() <= 4096));
        }
        PublishedCompletion completion(int ordinal) {
            if(resetArchive) return new PublishedCompletion(session,operations[ordinal-1],ordinal,ordinal%2==1?Command.RESET:Command.RUN,
                    ordinal==4 || ordinal==8?Optional.of(runs[ordinal-1]):Optional.empty(),Set.of(1,2,5,6).contains(ordinal)?"FAIL":"PASS",
                    true,"RELEASED",true,"operation-"+ordinal,"operation-"+ordinal+"/summary.json",
                    ordinal==1 || ordinal==5 ? Optional.of(new FailureObservation("com.mapartrunner.fabric.scenario.ScenarioRunner$SimulatedInspectResetFailure",
                    ordinal==1?"SIMULATED_INSPECT_RESET_AFTER_RESTORE_BEFORE_READINESS":"SIMULATED_INSPECT_ARCHIVE_BEFORE_COMMITTED"))
                    : ordinal==2 || ordinal==6 ? Optional.of(new FailureObservation("java.lang.AssertionError","Run requires this invocation's successful reset")):Optional.empty());
            return new PublishedCompletion(session, operations[ordinal - 1], ordinal,
                    ordinal == 2 || displaced && ordinal == 4 ? Command.RESET : displaced || ordinal < 4 ? Command.RUN : Command.RERUN,
                    (displaced ? ordinal == 5 : ordinal > 2) ? Optional.of(runs[ordinal - 1]) : Optional.empty(), ordinal == 1 || displaced && ordinal == 3 ? "FAIL" : "PASS",
                    true, "RELEASED", true, "operation-" + ordinal, "operation-" + ordinal + "/summary.json",
                    ordinal == 1 || displaced && ordinal == 3 ? Optional.of(new FailureObservation("java.lang.AssertionError", ordinal == 1 ? "Run requires this invocation's successful reset" : "Start state changed without physics")) : Optional.empty());
        }
        PublishedCompletion changed(PublishedCompletion c, String field) {
            return new PublishedCompletion(field.equals("session") ? UUID.randomUUID() : c.sessionId(),
                    field.equals("operation") ? operations[0] : c.operationId(), field.equals("ordinal") ? 4 : c.ordinal(),
                    field.equals("command") ? Command.RERUN : c.command(), field.equals("run") ? Optional.of(UUID.randomUUID()) : c.runId(),
                    field.equals("verdict") ? c.verdict().equals("FAIL") ? "PASS" : "FAIL" : c.verdict(), !field.equals("complete"),
                    field.equals("cleanup") ? "UNRESOLVED" : c.cleanupStatus(), !field.equals("settled"),
                    field.equals("directory") ? "operation-1" : c.evidenceDirectory(),
                    field.equals("summary") ? "operation-1/summary.json" : c.summaryPath(),
                    field.equals("failure") ? Optional.of(new FailureObservation("x", "x")) : c.failure());
        }
    }

    private static class SimulatedChat implements InspectAcceptanceDriver.ChatBoundary {
        final Object chat = new Object(); Object screen; String draft = "", refusal, clientRefusal, entryRefusal, throwAt; boolean replaceOnChar;
        int stops; Runnable reenter; final List<String> events = new ArrayList<>(), commands = new ArrayList<>();
        public void requireClient(MinecraftClient current) {
            if (entryRefusal != null) throw new IllegalStateException(entryRefusal);
            requireCapturedClient();
        }
        private void requireCapturedClient() { if (clientRefusal != null) throw new IllegalStateException(clientRefusal); }
        public void captureBinding() { requireBinding(); }
        public void requireBinding() { if (refusal != null) throw new IllegalStateException(refusal); }
        public Object screen() { return screen; }
        public void requireChat(Object expected, String text) {
            if (screen != chat || screen != expected) throw new IllegalStateException("screen");
            if (!draft.equals(text)) throw new IllegalStateException("draft");
        }
        public int chatKey() { return 84; }
        public void key(int code, boolean press, Object expected) {
            events.add(code + (press ? "+" : "-"));
            if (code == 257 && press) { commands.add(draft); screen = null; }
            callback(code + (press ? "+" : "-"));
        }
        public void character(int codepoint) { draft += Character.toString(codepoint); if (replaceOnChar) screen = new Object(); callback("char"); }
        private void callback(String event) {
            if (reenter != null) reenter.run();
            if (event.equals(throwAt)) throw new IllegalStateException("callback " + event);
        }
        public void stop() { stops++; }
        final Object forward = new Object(); boolean pressed, clearFails; double x, z;
        public Object forwardBinding() { requireBinding(); return forward; }
        public InspectAcceptanceDriver.Pose pose() { requireBinding(); return new InspectAcceptanceDriver.Pose(x, z); }
        public boolean forwardPressed(Object binding) { requireCapturedClient(); assertSame(forward, binding); return pressed; }
        public void forward(Object binding, boolean press) {
            requireBinding(); assertSame(forward, binding); pressed = press;
            events.add("87" + (press ? "+" : "-")); callback("87" + (press ? "+" : "-"));
        }
        public void clearForward(Object binding) {
            requireCapturedClient(); assertSame(forward, binding); if (clearFails) throw new IllegalStateException("clear unavailable");
            pressed = false; events.add("captured-false");
        }
        boolean neutral=true,reenterNeutral; AtomicLong now;
        public void requireNeutral() {
            requireBinding(); if (screen!=null) throw new IllegalStateException("idle screen");
            if (!neutral || pressed) throw new IllegalStateException("idle neutral");
            if (reenterNeutral && reenter!=null) reenter.run();
        }
        public Instant utc() { return Instant.parse("2026-11-12T01:00:00Z").plusNanos(now.get()); }
        public InspectAcceptanceDriver.IdleIdentity idleIdentity() { return new InspectAcceptanceDriver.IdleIdentity(42,
                Instant.parse("2026-11-12T00:00:00Z"),UUID.fromString("00000000-0000-0000-0000-000000000001")); }
    }

    private static ScenarioRunner.InspectIdleObservation boundObservation() {
        return boundObservation("");
    }
    private static ScenarioRunner.InspectIdleObservation boundObservation(String missing) {
        var context = new com.mapartrunner.core.run.RunLifecycle.Context(UUID.randomUUID(),"connection","world","minecraft:overworld",
                "plan",new com.mapartrunner.core.model.BuildPlan.GridPos(0,65,0),"settings");
        var player=UUID.randomUUID();
        var binding = new ScenarioObserver.Binding(UUID.randomUUID(),context,
                new ScenarioObserver.ClientBinding(new Object(),new Object(),new Object(),new Object(),player,context.dimension(),true,true,true),
                new ScenarioObserver.ServerBinding(new Object(),new Object(),new Object(),player,context.dimension(),true,true),null,null);
        var sample = new ScenarioObserver.Sample(1,"END",12,true,null,List.of(),false,null,List.of());
        var witness = new ScenarioObserver.ServerWitness(new ScenarioObserver.EndPublication(binding,sample,4.5),1,
                new ScenarioObserver.ServerReading(binding.server(),0,66,0,4.5,1,1,1));
        return new ScenarioRunner.InspectIdleObservation(new ScenarioObserver.Watch.Retained(missing.equals("samples")?List.of():List.of(sample),null,
                missing.equals("binding")?null:binding,missing.equals("witnesses")?List.of():List.of(witness)));
    }
}
