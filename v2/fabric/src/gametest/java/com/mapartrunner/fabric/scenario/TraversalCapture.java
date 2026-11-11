package com.mapartrunner.fabric.scenario;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Test-only, single-run evidence. Synchronized appends/seal share one bounded writer, no queue. */
public final class TraversalCapture implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int RECORD_BYTES = 2048;
    public enum CheckpointKind { INITIAL, FIRST_VERIFY, TERMINAL, RELEASE_END, FAILURE }
    public enum OutputKind { REGION, SCALAR }
    public record FileReceipt(Path path,OutputKind kind,long bytes,String hash,Path receipt) { }
    public record OutputSnapshot(Path manifest,long manifestBytes,String manifestHash,long regionBytes,long scalarBytes,
                                 List<FileReceipt> receipts,boolean complete,String failure) {
        public OutputSnapshot { receipts=List.copyOf(receipts); }
    }

    /** Invocation/readiness output starts without a run UUID; adoption keeps every spent byte. */
    public static final class OutputBudget implements AutoCloseable {
        private final Path root;
        private final Limits limits;
        private final BoundedOutput manifest;
        private final List<FileReceipt> receipts=new ArrayList<>();
        private final Map<Path,OutputKind> files=new LinkedHashMap<>();
        private long regionBytes,scalarBytes;
        private Throwable failure;
        private boolean adopted;
        private boolean scalarFailed;
        private OutputFile active;
        private OutputSnapshot sealed;

        public OutputBudget(Path directory,Limits limits) throws IOException {
            this.limits=Objects.requireNonNull(limits);
            root=directory.toAbsolutePath().normalize(); Files.createDirectories(root);
            if(!root.toRealPath().equals(root)) throw new IOException("Redirected output root");
            manifest=new BoundedOutput(Files.newOutputStream(root.resolve("output-receipts.jsonl"),StandardOpenOption.CREATE_NEW),1048576);
            files.put(root.resolve("output-receipts.jsonl"),OutputKind.SCALAR);
        }
        public Path root() { return root; }
        public synchronized long regionBytes() { return actualBytes(OutputKind.REGION); }
        public synchronized long scalarBytes() { return actualBytes(OutputKind.SCALAR); }
        public synchronized Set<Path> ownedFiles() { return Set.copyOf(files.keySet()); }
        private long actualBytes(OutputKind kind) {
            long bytes=0;
            for(var file:files.entrySet()) if(file.getValue()==kind) {
                try { bytes=Math.addExact(bytes,Files.size(file.getKey())); } catch(IOException error) { retain(error); }
            }
            return bytes;
        }
        public synchronized List<FileReceipt> receipts() { return List.copyOf(receipts); }
        public synchronized Optional<Throwable> failure() { return Optional.ofNullable(failure); }
        private synchronized void adopt(Limits expected) {
            if(adopted || sealed!=null || failure!=null || active!=null || !limits.equals(expected)) throw new IllegalStateException("Output budget cannot be adopted");
            adopted=true;
        }
        private Path owned(String name) {
            if(name==null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*") || name.equals("output-receipts.jsonl"))
                throw new IllegalArgumentException("Output requires an owned single filename");
            if(!root.toFile().isDirectory()) throw new IllegalStateException("Output root lost");
            try { if(!root.toRealPath().equals(root)) throw new IllegalStateException("Output root redirected"); }
            catch(IOException error) { throw new UncheckedIOException(error); }
            return root.resolve(name);
        }
        public synchronized OutputStream open(OutputKind kind,String name) throws IOException { return openFile(kind,name,false,false); }
        public synchronized OutputStream openPostSealFailure(String name) throws IOException { return openFile(OutputKind.SCALAR,name,true,true); }
        private synchronized OutputStream openSealMetadata() throws IOException { return openFile(OutputKind.SCALAR,"sealed.jsonl",false,true); }
        private OutputStream openFile(OutputKind kind,String name,boolean postSeal,boolean allowFailure) throws IOException {
            Objects.requireNonNull(kind); Path path=owned(name);
            if(active!=null || postSeal!=(sealed!=null) || postSeal && scalarFailed || !allowFailure && failure!=null)
                throw new IllegalStateException("Output budget is unavailable");
            try {
                var output=new BoundedOutput(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW),kind==OutputKind.REGION?limits.regionByteReserve():1048576);
                files.put(path,kind);
                active=new OutputFile(path,kind,output,postSeal,allowFailure); return active;
            } catch(IOException error) { if(kind==OutputKind.SCALAR) scalarFailed=true; retain(error); throw error; }
        }
        private void write(OutputKind kind,OutputStream output,byte[] bytes,int offset,int length) throws IOException {
            long used=kind==OutputKind.REGION?regionBytes:scalarBytes;
            long limit=kind==OutputKind.REGION?limits.regionByteReserve():1048576;
            try {
                if(length>limit-used) throw new IOException("Cumulative "+kind+" UTF-8 output cap exceeded");
                // Reserve before native I/O: a partial failing write cannot renew the allowance.
                if(kind==OutputKind.REGION) regionBytes+=length; else scalarBytes+=length;
                output.write(bytes,offset,length);
            } catch(IOException error) { if(kind==OutputKind.SCALAR) scalarFailed=true; throw error; }
        }
        private OutputStream scalarSink(OutputStream output) {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
                @Override public void write(byte[] bytes,int offset,int length) throws IOException { OutputBudget.this.write(OutputKind.SCALAR,output,bytes,offset,length); }
                @Override public void flush() throws IOException { output.flush(); }
            };
        }
        private synchronized void retain(Throwable error) {
            if(failure==null) failure=error;
            else if(failure!=error && error.getCause()!=failure) {
                for(var old:failure.getSuppressed()) if(old==error) return;
                failure.addSuppressed(error);
            }
        }
        private final class OutputFile extends OutputStream {
            private final Path path;
            private final OutputKind kind;
            private final BoundedOutput output;
            private final boolean postSeal;
            private final boolean allowFailure;
            private boolean closed;
            private OutputFile(Path path,OutputKind kind,BoundedOutput output,boolean postSeal,boolean allowFailure) {
                this.path=path; this.kind=kind; this.output=output; this.postSeal=postSeal; this.allowFailure=allowFailure;
            }
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                synchronized(OutputBudget.this) {
                    if(closed || !allowFailure && failure!=null) throw new IOException("Output is closed/failed");
                    try { OutputBudget.this.write(kind,output,bytes,offset,length); } catch(IOException error) { retain(error); throw error; }
                }
            }
            @Override public void flush() throws IOException { synchronized(OutputBudget.this) { output.flush(); } }
            @Override public void close() throws IOException {
                synchronized(OutputBudget.this) {
                    if(closed) return; closed=true; active=null;
                    IOException cleanup=null;
                    try { output.close(); } catch(IOException error) { retain(error); cleanup=error; }
                    try {
                        Path receiptPath=postSeal?owned(path.getFileName()+".receipt.jsonl"):root.resolve("output-receipts.jsonl");
                        var receipt=new FileReceipt(path,kind,Files.size(path),hash(path),receiptPath);
                        var line=array("OUTPUT1",path.getFileName().toString(),kind,receipt.bytes(),receipt.hash());
                        if(postSeal) {
                            try(var receiptOutput=new BoundedOutput(Files.newOutputStream(receiptPath,StandardOpenOption.CREATE_NEW),1048576)) {
                                files.put(receiptPath,OutputKind.SCALAR);
                                writeLine(scalarSink(receiptOutput),line);
                            }
                        } else writeLine(scalarSink(manifest),line);
                        receipts.add(receipt); // The persisted, charged receipt bounds even zero-byte file metadata.
                    } catch(IOException | RuntimeException error) {
                        scalarFailed=true;
                        retain(error);
                        if(cleanup==null) cleanup=error instanceof IOException io?io:new IOException("Output receipt failed",error);
                    }
                    if(cleanup!=null) throw cleanup;
                }
            }
        }
        private synchronized void prepareSeal() {
            if(active!=null) {
                retain(new IllegalStateException("Required output remained open at seal"));
                try { active.close(); } catch(IOException error) { retain(error); }
            }
        }
        public synchronized OutputSnapshot seal() {
            if(sealed!=null) return sealed;
            prepareSeal();
            try { manifest.flush(); } catch(IOException error) { retain(error); }
            try { manifest.close(); } catch(IOException error) { retain(error); }
            long bytes=manifest.count; String digest=manifest.digest();
            try { bytes=Files.size(root.resolve("output-receipts.jsonl")); digest=hash(root.resolve("output-receipts.jsonl")); }
            catch(IOException error) { retain(error); }
            for(var receipt:receipts) {
                try {
                    if(!Files.isRegularFile(receipt.path(),LinkOption.NOFOLLOW_LINKS) || Files.size(receipt.path())!=receipt.bytes() || !hash(receipt.path()).equals(receipt.hash()))
                        throw new IOException("Closed output receipt changed");
                } catch(IOException error) { retain(error); }
            }
            long actualRegion=actualBytes(OutputKind.REGION),actualScalar=actualBytes(OutputKind.SCALAR);
            sealed=new OutputSnapshot(root.resolve("output-receipts.jsonl"),bytes,digest,actualRegion,actualScalar,receipts,failure==null,failure==null?null:failure.getClass().getName());
            return sealed;
        }
        @Override public synchronized void close() {
            if(active!=null) {
                retain(new IllegalStateException("Required output closed without completion"));
                try { active.close(); } catch(IOException error) { retain(error); }
            }
            seal();
        }
    }

    public record Limits(long eventLimit, long scanByteLimit, long regionByteReserve, long totalByteLimit, int targetCount) {
        public Limits {
            if (eventLimit < 16 || scanByteLimit != Math.addExact(4096,Math.multiplyExact(1024L,targetCount))
                    || targetCount < 0 || regionByteReserve < 16384
                    || totalByteLimit != Math.addExact(Math.addExact(Math.multiplyExact(eventLimit,RECORD_BYTES),
                    Math.multiplyExact(5,scanByteLimit)),Math.addExact(regionByteReserve,1048576)))
                throw new IllegalArgumentException("Unsupported capture limits");
        }
        public static Limits forRun(int runTicks,int releaseTicks,int targetCount,int regionCells) {
            if (runTicks<0 || releaseTicks<0 || targetCount<0 || regionCells<0) throw new IllegalArgumentException("Negative capture bound");
            long events=Math.addExact(Math.multiplyExact(3L,Math.addExact((long)runTicks,releaseTicks)),16);
            long scans=Math.addExact(4096,Math.multiplyExact(1024L,targetCount));
            long region=Math.multiplyExact(4,Math.addExact(4096,Math.multiplyExact(1024L,regionCells)));
            return new Limits(events,scans,region,Math.addExact(Math.addExact(Math.multiplyExact(events,RECORD_BYTES),
                    Math.multiplyExact(5,scans)),Math.addExact(region,1048576)),targetCount);
        }
    }
    public sealed interface Event permits MotionFrame, ServerFrame { long ordinal(); UUID runId(); }
    public record Identities(int originalWorld,int originalPlayer,int originalHandler,int originalConnection,
                             int world,int player,int handler,int connection,
                             boolean worldMatches,boolean playerMatches,boolean handlerMatches,boolean connectionMatches) { }
    public record Physics(boolean onGround,boolean noGravity,boolean noDrag,boolean jumping,boolean vehicle,
                          boolean fluid,boolean water,boolean submerged,boolean lava,boolean climbing,boolean gliding,
                          boolean swimming,boolean sneaking,boolean usingItem,boolean hasEffects,double fallDistance,double gravity) { }
    public record Pose(double x,double y,double z,double vx,double vy,double vz,double eyeX,double eyeY,double eyeZ,
                       int age,long worldTime,float yaw,float headYaw,float bodyYaw,boolean flying,boolean sprinting,
                       int selectedSlot,int reserved,int usable,double interactionReach,Physics physics) { }
    public record Traversal(int physicalIndex,Integer enteredBuildIndex,String pass,String substage,int laneId,
                            int startX,int startY,int startZ,int endX,int endY,int endZ,String direction,String stage,
                            int stageTicks,int entryTicks,int alignedTicks,int supportTicks,int yawTicks,
                            int transitionTicks,int noProgressTicks,boolean constantSprint) { }
    /** Only declared fault targets have scalar state facts; ordinary scale events contain none. */
    public record TargetFact(UUID runId,int targetId,Long actionId,long tick,PlacementObservation.Evidence evidence,BlockSpec state) {
        public static TargetFact from(PlacementObservation value) {
            return new TargetFact(value.runId(),value.targetId(),value.actionId().isPresent()?value.actionId().getAsLong():null,
                    value.tick(),value.evidence(),value.observedState().orElse(null));
        }
        public PlacementObservation observation() {
            return new PlacementObservation(runId,targetId,actionId==null?OptionalLong.empty():OptionalLong.of(actionId),tick,evidence,Optional.ofNullable(state));
        }
    }
    public record MotionFrame(long ordinal,long tick,String phase,UUID runId,Identities identities,Pose pose,int keys,
                              boolean paused,BuildEngine.ControlOwner owner,RunLifecycle.Phase lifecycle,
                              RunLifecycle.Activity activity,RunLifecycle.Outcome outcome,BuildEngine.Reason reason,
                              long eligible,long verify,Traversal traversal,int pending,int unfinished,
                              BuildEngine.PlacementEffectResult effect,Integer predictionSequence,TargetFact fault0,TargetFact fault1) implements Event { }
    public record ServerFrame(long ordinal,UUID runId,int originalServer,int originalPlayer,int server,int player,
                              boolean serverMatches,boolean playerMatches,long serverTick,long worldTime,int age,
                              double x,double y,double z,double vx,double vy,double vz,float yaw,boolean flying,boolean sprinting,
                              int selectedSlot,int reserved,int usable,int controls,long followsClientOrdinal,
                              double interactionReach,Physics physics,TargetFact fault0,TargetFact fault1) implements Event { }
    public record FullScan(UUID runId,long tick,String phase,long eventOrdinal,List<PlacementObservation> observations) {
        public FullScan {
            Objects.requireNonNull(runId); Objects.requireNonNull(phase); observations=List.copyOf(observations);
            if(tick<0 || eventOrdinal<0 || phase.isBlank()) throw new IllegalArgumentException("Invalid full scan provenance");
        }
    }
    /** Incomplete entries own raw prefixes; count retains the original supplied scan cardinality. */
    public record Checkpoint(CheckpointKind kind,Path path,long tick,String phase,long eventOrdinal,int count,long bytes,String hash,
                             UUID runId,boolean complete) { }
    public record Sealed(Path events,long eventCount,long eventBytes,String eventHash,Map<CheckpointKind,Checkpoint> checkpoints,
                         Path metadata,OutputSnapshot outputs,boolean complete,String failure) {
        public Sealed { checkpoints=Map.copyOf(checkpoints); }
        public EventReader openEvents() throws IOException {
            if(!hash(events).equals(eventHash)) throw new IOException("Event hash changed");
            return new EventReader(events,eventCount,eventBytes,complete);
        }
        public FullScan readCheckpoint(CheckpointKind kind) throws IOException {
            var c=Objects.requireNonNull(checkpoints.get(kind),"Missing checkpoint");
            if(!c.complete()) throw new IOException("Checkpoint is an incomplete raw prefix");
            if(Files.size(c.path())!=c.bytes() || !hash(c.path()).equals(c.hash())) throw new IOException("Checkpoint bytes/hash changed");
            try(var input=new BufferedInputStream(Files.newInputStream(c.path()))) {
                var header=readArray(input,c.bytes());
                if(header==null || !string(header,0).equals("SCAN1")) throw new IOException("Invalid checkpoint header");
                var observations=new ArrayList<PlacementObservation>(); JsonArray line;
                while((line=readArray(input,c.bytes()))!=null) {
                    if(observations.size()>=c.count()) throw new IOException("Checkpoint count exceeded");
                    observations.add(fact(line).observation());
                }
                if(observations.size()!=c.count()) throw new IOException("Incomplete checkpoint");
                var scan=new FullScan(uuid(header,1),number(header,2),string(header,3),number(header,4),observations);
                if(!scan.runId().equals(c.runId()) || scan.tick()!=c.tick() || !scan.phase().equals(c.phase()) || scan.eventOrdinal()!=c.eventOrdinal()) throw new IOException("Checkpoint metadata changed");
                return scan;
            }
        }
    }

    private final Path directory;
    private final UUID runId;
    private final Limits limits;
    private final OutputBudget outputs;
    private final Set<Integer> faultTargets;
    private final BoundedOutput events;
    private final EnumMap<CheckpointKind,Checkpoint> checkpoints=new EnumMap<>(CheckpointKind.class);
    private final EnumSet<CheckpointKind> attempted=EnumSet.noneOf(CheckpointKind.class);
    private long count;
    private long latestClientOrdinal=-1;
    private MotionFrame latestMotion;
    private MotionFrame latestEnd;
    private Throwable failure;
    private Sealed sealed;
    private boolean sealing;

    public TraversalCapture(Path directory,UUID runId,Limits limits) throws IOException { this(directory,runId,limits,Set.of()); }
    public TraversalCapture(Path directory,UUID runId,Limits limits,Set<Integer> faultTargets) throws IOException { this(directory,runId,limits,faultTargets,null); }
    public TraversalCapture(Path directory,UUID runId,Limits limits,Set<Integer> faultTargets,OutputBudget outputs) throws IOException {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize(); this.runId=Objects.requireNonNull(runId);
        this.limits=Objects.requireNonNull(limits); this.faultTargets=Set.copyOf(faultTargets);
        if(this.faultTargets.size()>2 || this.faultTargets.stream().anyMatch(id->id<0)) throw new IllegalArgumentException("At most two nonnegative declared fault IDs");
        Files.createDirectories(this.directory);
        if(!this.directory.toRealPath().equals(this.directory)) throw new IOException("Redirected capture root");
        this.outputs=outputs==null?new OutputBudget(this.directory,limits):outputs;
        this.outputs.adopt(limits);
        try {
            events=new BoundedOutput(Files.newOutputStream(this.directory.resolve("events.jsonl"),StandardOpenOption.CREATE_NEW),Math.multiplyExact(limits.eventLimit(),RECORD_BYTES));
        } catch(IOException | RuntimeException error) {
            this.outputs.retain(error); this.outputs.close(); throw error;
        }
    }
    public UUID runId() { return runId; }
    public Set<Integer> faultTargets() { return faultTargets; }
    public synchronized long nextOrdinal() { requireOpen(); return count; }
    public synchronized long latestClientOrdinal() { requireOpen(); return latestClientOrdinal; }
    public synchronized Optional<Throwable> failure() { return outputs.failure(); }
    public synchronized boolean hasCheckpoint(CheckpointKind kind) { return attempted.contains(kind); }
    public synchronized void validatePlan(BuildPlan plan) {
        requireOpen();
        try {
            if(plan.targets().size()!=limits.targetCount() || !plan.targets().stream().map(BuildPlan.Target::id).collect(java.util.stream.Collectors.toSet()).containsAll(faultTargets))
                throw new IllegalStateException("Capture declaration differs from plan");
        } catch(RuntimeException error) { throw fail(error); }
    }
    public synchronized Optional<MotionFrame> latestEnd() { requireOpen(); return Optional.ofNullable(latestEnd); }
    public synchronized boolean isLatestEnd(MotionFrame frame) { requireOpen(); return frame!=null && frame==latestEnd; }
    public synchronized void requireSealedForClear() {
        if(sealed==null || !sealed.complete() || outputs.failure().isPresent()) throw new IllegalStateException("Complete successful seal required before clear/arm");
    }
    public synchronized void append(MotionFrame frame) { appendEvent(frame); }
    public synchronized void append(ServerFrame frame) { appendEvent(frame); }
    private void appendEvent(Event frame) {
        requireOpen();
        try {
            if(frame.ordinal()!=count || !runId.equals(frame.runId()) || count>=limits.eventLimit()) throw new IllegalStateException("Capture event identity/order/cap exceeded");
            if(frame instanceof MotionFrame m) {
                if(m.tick()<0 || m.phase()==null || m.identities()==null || m.pending()<0 || m.unfinished()<0) throw new IllegalStateException("Invalid motion facts");
                checkFaults(m.fault0(),m.fault1());
            } else if(frame instanceof ServerFrame s) {
                if(s.followsClientOrdinal()<0 || s.followsClientOrdinal()>latestClientOrdinal) throw new IllegalStateException("Server record lacks original predecessor client event");
                checkFaults(s.fault0(),s.fault1());
            }
            var buffer=new ByteArrayOutputStream(RECORD_BYTES);
            try(var bounded=new BoundedOutput(buffer,RECORD_BYTES)) { writeLine(bounded,encode(frame)); }
            events.write(buffer.toByteArray()); events.flush(); count++;
            if(frame instanceof MotionFrame m) { latestMotion=m; latestClientOrdinal=m.ordinal(); if(m.phase().equals("END")) latestEnd=m; }
        } catch(IOException | RuntimeException error) { throw fail(error); }
    }
    private void checkFaults(TargetFact first,TargetFact second) {
        if(second!=null && first==null || first!=null && second!=null && first.targetId()==second.targetId()) throw new IllegalStateException("Invalid declared fault facts");
        for(var value:new TargetFact[]{first,second}) if(value!=null) {
            if(!faultTargets.contains(value.targetId()) || !value.runId().equals(runId)) throw new IllegalStateException("Undeclared/foreign fault target");
            value.observation();
        }
    }
    public synchronized void checkpoint(CheckpointKind kind,FullScan scan) {
        requireOpen();
        Path created=null;
        boolean complete=false;
        try {
            if(!attempted.add(Objects.requireNonNull(kind)) || !runId.equals(scan.runId()) || scan.tick()<0 || scan.eventOrdinal()<0
                    || scan.observations().size()>limits.targetCount()) throw new IllegalStateException("Checkpoint identity/count/unique-kind cap exceeded");
            if(latestMotion==null || scan.tick()!=latestMotion.tick() || scan.eventOrdinal()!=latestMotion.ordinal()
                    || !scan.phase().equals(latestMotion.phase())) throw new IllegalStateException("Checkpoint must retain its latest original client event");
            Path path=directory.resolve(kind.name()+".jsonl");
            if(!directory.toRealPath().equals(directory)) throw new IOException("Capture root redirected");
            var file=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW);
            created=path; // Ownership starts only after CREATE_NEW succeeds, never on collision.
            try(var output=new BoundedOutput(file,limits.scanByteLimit())) {
                writeLine(output,array("SCAN1",scan.runId(),scan.tick(),scan.phase(),scan.eventOrdinal()));
                for(var observation:scan.observations()) writeLine(output,encodeFact(TargetFact.from(observation)));
            }
            complete=true;
        } catch(IOException | RuntimeException error) { retain(error); }
        if(created!=null) {
            long bytes=-1; String digest=null;
            try { bytes=Files.size(created); digest=hash(created); }
            catch(IOException | RuntimeException error) { complete=false; retain(error); }
            checkpoints.put(kind,new Checkpoint(kind,created,scan.tick(),scan.phase(),scan.eventOrdinal(),scan.observations().size(),bytes,digest,scan.runId(),complete));
        }
        if(outputs.failure().isPresent()) throw fail(outputs.failure().orElseThrow());
    }
    /** Disarm is the caller's first step; seal preserves the first failure and any valid file prefix. */
    public synchronized Sealed seal() {
        if(sealed!=null) return sealed;
        sealing=true;
        outputs.prepareSeal();
        outputs.failure().ifPresent(this::retain);
        try { events.flush(); } catch(IOException error) { retain(error); }
        try { events.close(); } catch(IOException error) { retain(error); }
        long actualBytes=events.count;
        String actualHash=events.digest();
        // A stream can throw after a partial native write; report the actual surviving prefix.
        try {
            if(!directory.toRealPath().equals(directory)) throw new IOException("Capture root redirected");
            actualBytes=Files.size(directory.resolve("events.jsonl")); actualHash=hash(directory.resolve("events.jsonl"));
        }
        catch(IOException error) { retain(error); }
        Path metadata=outputs.root().resolve("sealed.jsonl");
        try(var out=outputs.openSealMetadata()) {
            writeLine(out,array("CAPTURE1",runId,count,actualBytes,actualHash,failure==null?null:failure.getClass().getName(),failure==null?null:failure.getMessage(),
                    limits.eventLimit(),limits.scanByteLimit(),limits.regionByteReserve(),limits.totalByteLimit()));
            for(var c:checkpoints.values()) writeLine(out,array(c.kind(),c.path().getFileName().toString(),c.tick(),c.phase(),c.eventOrdinal(),c.count(),c.bytes(),c.hash(),c.runId(),c.complete()));
        } catch(IOException | RuntimeException error) { retain(error); }
        var outputSnapshot=outputs.seal(); outputs.failure().ifPresent(this::retain);
        sealed=new Sealed(directory.resolve("events.jsonl"),count,actualBytes,actualHash,checkpoints,metadata,outputSnapshot,failure==null,
                failure==null?null:failure.getClass().getName());
        return sealed;
    }
    @Override public synchronized void close() {
        if(sealed==null) { retain(new IllegalStateException("Capture closed before explicit seal")); seal(); }
        outputs.close();
    }
    public synchronized IllegalStateException fail(Throwable error) {
        retain(error); return failure instanceof IllegalStateException state?state:new IllegalStateException("Traversal capture incomplete",failure);
    }
    private void retain(Throwable error) {
        outputs.retain(error); failure=outputs.failure().orElseThrow();
    }
    private void requireOpen() {
        if(sealing || sealed!=null) throw new IllegalStateException("Capture is sealed");
        if(outputs.failure().isPresent()) throw new IllegalStateException("Capture has failed",outputs.failure().orElseThrow());
    }

    public static void requireFullMatch(BuildPlan plan,FullScan scan,UUID runId,long tick) {
        if(!scan.runId().equals(runId) || scan.tick()!=tick || scan.observations().size()!=plan.targets().size()) throw new IllegalStateException("Full scan run/tick/count mismatch");
        var expected=new HashMap<Integer,BlockSpec>(); for(var target:plan.targets()) expected.put(target.id(),target.block());
        var seen=new HashSet<Integer>();
        for(var value:scan.observations()) if(!value.runId().equals(runId) || value.tick()!=tick || !seen.add(value.targetId())
                || value.evidence()!=PlacementObservation.Evidence.OBSERVED || !Objects.equals(expected.get(value.targetId()),value.observedState().orElse(null)))
            throw new IllegalStateException("Full scan missing/duplicate/stale/predicted/foreign/state mismatch");
        if(!seen.equals(expected.keySet())) throw new IllegalStateException("Full scan target set mismatch");
    }

    /** Pull one bounded original record at a time; never load a whole trace. */
    public static final class EventReader implements AutoCloseable {
        private final InputStream input;
        private final long count;
        private final boolean complete;
        private long next;
        private EventReader(Path path,long count,long bytes,boolean complete) throws IOException {
            if(Files.size(path)!=bytes) throw new IOException("Event bytes changed");
            input=new BufferedInputStream(Files.newInputStream(path)); this.count=count; this.complete=complete;
        }
        public Optional<Event> next() throws IOException {
            if(next==count) {
                if(complete && input.read()!=-1) throw new IOException("Trailing bytes after complete event count");
                return Optional.empty();
            }
            var line=readArray(input,RECORD_BYTES); if(line==null) throw new EOFException("Missing committed event");
            try { var event=decode(line); if(event.ordinal()!=next++) throw new IOException("Event order changed"); return Optional.of(event); }
            catch(RuntimeException error) { throw new IOException("Invalid event record",error); }
        }
        @Override public void close() throws IOException { input.close(); }
    }
    private static final class BoundedOutput extends OutputStream {
        private final OutputStream output;
        private final long limit;
        private final MessageDigest hash=digestInstance();
        private long count;
        private String finalHash;
        BoundedOutput(OutputStream output,long limit) { this.output=output; this.limit=limit; }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            if(length>limit-count) throw new IOException("UTF-8 byte cap exceeded");
            output.write(bytes,offset,length); hash.update(bytes,offset,length); count+=length;
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException { try { output.close(); } finally { finalHash=HexFormat.of().formatHex(hash.digest()); } }
        String digest() { return finalHash; }
    }
    private static void writeLine(OutputStream output,JsonElement value) throws IOException {
        var writer=new OutputStreamWriter(output,StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        var json=new JsonWriter(writer); JSON.toJson(value,json); json.flush(); writer.write('\n'); writer.flush();
    }
    private static JsonArray readArray(InputStream input,long limit) throws IOException {
        var buffer=new ByteArrayOutputStream(); int next;
        while((next=input.read())!=-1) {
            if(buffer.size()>=limit) throw new IOException("Encoded line exceeds byte cap");
            if(next=='\n') return JsonParser.parseString(buffer.toString(StandardCharsets.UTF_8)).getAsJsonArray();
            buffer.write(next);
        }
        if(buffer.size()!=0) throw new EOFException("Incomplete JSON line");
        return null;
    }
    private static MessageDigest digestInstance() { try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException error) { throw new AssertionError(error); } }
    private static String hash(Path path) throws IOException {
        var digest=digestInstance(); try(var in=Files.newInputStream(path)) { var bytes=new byte[8192]; int n; while((n=in.read(bytes))!=-1) digest.update(bytes,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static JsonArray array(Object... values) { var a=new JsonArray(); for(var value:values) a.add(JSON.toJsonTree(value)); return a; }
    private static JsonElement encodeFact(TargetFact f) { return f==null?JsonNull.INSTANCE:array(f.runId(),f.targetId(),f.actionId(),f.tick(),f.evidence(),f.state()); }
    private static TargetFact fact(JsonArray a) { return new TargetFact(uuid(a,0),integer(a,1),a.get(2).isJsonNull()?null:number(a,2),number(a,3),PlacementObservation.Evidence.valueOf(string(a,4)),a.get(5).isJsonNull()?null:JSON.fromJson(a.get(5),BlockSpec.class)); }
    private static TargetFact nullableFact(JsonArray a,int index) { return a.get(index).isJsonNull()?null:fact(a.get(index).getAsJsonArray()); }
    private static JsonArray encode(Event event) {
        if(event instanceof MotionFrame m) {
            var i=m.identities(); var p=m.pose(); var t=m.traversal(); var e=m.effect();
            return array("M1",m.ordinal(),m.tick(),m.phase(),m.runId(),
                    array(i.originalWorld(),i.originalPlayer(),i.originalHandler(),i.originalConnection(),i.world(),i.player(),i.handler(),i.connection(),i.worldMatches(),i.playerMatches(),i.handlerMatches(),i.connectionMatches()),
                    p==null?null:array(p.x(),p.y(),p.z(),p.vx(),p.vy(),p.vz(),p.eyeX(),p.eyeY(),p.eyeZ(),p.age(),p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),p.flying(),p.sprinting(),p.selectedSlot(),p.reserved(),p.usable(),p.interactionReach(),p.physics()),
                    m.keys(),m.paused(),m.owner(),m.lifecycle(),m.activity(),m.outcome(),m.reason(),m.eligible(),m.verify(),
                    t==null?null:array(t.physicalIndex(),t.enteredBuildIndex(),t.pass(),t.substage(),t.laneId(),t.startX(),t.startY(),t.startZ(),t.endX(),t.endY(),t.endZ(),t.direction(),t.stage(),t.stageTicks(),t.entryTicks(),t.alignedTicks(),t.supportTicks(),t.yawTicks(),t.transitionTicks(),t.noProgressTicks(),t.constantSprint()),
                    m.pending(),m.unfinished(),e==null?null:array(e.runId(),e.actionId(),e.targetId(),e.tick(),e.status()),m.predictionSequence(),encodeFact(m.fault0()),encodeFact(m.fault1()));
        }
        var s=(ServerFrame)event;
        return array("S1",s.ordinal(),s.runId(),s.originalServer(),s.originalPlayer(),s.server(),s.player(),s.serverMatches(),s.playerMatches(),s.serverTick(),s.worldTime(),s.age(),s.x(),s.y(),s.z(),s.vx(),s.vy(),s.vz(),s.yaw(),s.flying(),s.sprinting(),s.selectedSlot(),s.reserved(),s.usable(),s.controls(),s.followsClientOrdinal(),s.interactionReach(),s.physics(),encodeFact(s.fault0()),encodeFact(s.fault1()));
    }
    private static Event decode(JsonArray a) {
        if(string(a,0).equals("M1")) {
            var i=a.get(5).getAsJsonArray(); var p=a.get(6).isJsonNull()?null:a.get(6).getAsJsonArray();
            var t=a.get(16).isJsonNull()?null:a.get(16).getAsJsonArray(); var e=a.get(19).isJsonNull()?null:a.get(19).getAsJsonArray();
            return new MotionFrame(number(a,1),number(a,2),string(a,3),uuid(a,4),new Identities(integer(i,0),integer(i,1),integer(i,2),integer(i,3),integer(i,4),integer(i,5),integer(i,6),integer(i,7),bool(i,8),bool(i,9),bool(i,10),bool(i,11)),
                    p==null?null:new Pose(decimal(p,0),decimal(p,1),decimal(p,2),decimal(p,3),decimal(p,4),decimal(p,5),decimal(p,6),decimal(p,7),decimal(p,8),integer(p,9),number(p,10),(float)decimal(p,11),(float)decimal(p,12),(float)decimal(p,13),bool(p,14),bool(p,15),integer(p,16),integer(p,17),integer(p,18),decimal(p,19),JSON.fromJson(p.get(20),Physics.class)),
                    integer(a,7),bool(a,8),enumeration(a,9,BuildEngine.ControlOwner.class),enumeration(a,10,RunLifecycle.Phase.class),enumeration(a,11,RunLifecycle.Activity.class),enumeration(a,12,RunLifecycle.Outcome.class),enumeration(a,13,BuildEngine.Reason.class),number(a,14),number(a,15),
                    t==null?null:new Traversal(integer(t,0),t.get(1).isJsonNull()?null:integer(t,1),string(t,2),string(t,3),integer(t,4),integer(t,5),integer(t,6),integer(t,7),integer(t,8),integer(t,9),integer(t,10),string(t,11),string(t,12),integer(t,13),integer(t,14),integer(t,15),integer(t,16),integer(t,17),integer(t,18),integer(t,19),bool(t,20)),
                    integer(a,17),integer(a,18),e==null?null:new BuildEngine.PlacementEffectResult(uuid(e,0),number(e,1),integer(e,2),number(e,3),BuildEngine.EffectStatus.valueOf(string(e,4))),a.get(20).isJsonNull()?null:integer(a,20),nullableFact(a,21),nullableFact(a,22));
        }
        if(!string(a,0).equals("S1")) throw new IllegalArgumentException("Unknown event schema");
        return new ServerFrame(number(a,1),uuid(a,2),integer(a,3),integer(a,4),integer(a,5),integer(a,6),bool(a,7),bool(a,8),number(a,9),number(a,10),integer(a,11),decimal(a,12),decimal(a,13),decimal(a,14),decimal(a,15),decimal(a,16),decimal(a,17),(float)decimal(a,18),bool(a,19),bool(a,20),integer(a,21),integer(a,22),integer(a,23),integer(a,24),number(a,25),decimal(a,26),JSON.fromJson(a.get(27),Physics.class),nullableFact(a,28),nullableFact(a,29));
    }
    private static String string(JsonArray a,int i) { return a.get(i).getAsString(); }
    private static long number(JsonArray a,int i) { return a.get(i).getAsLong(); }
    private static int integer(JsonArray a,int i) { return a.get(i).getAsInt(); }
    private static double decimal(JsonArray a,int i) { return a.get(i).getAsDouble(); }
    private static boolean bool(JsonArray a,int i) { return a.get(i).getAsBoolean(); }
    private static UUID uuid(JsonArray a,int i) { return UUID.fromString(string(a,i)); }
    private static <E extends Enum<E>> E enumeration(JsonArray a,int i,Class<E> type) { return a.get(i).isJsonNull()?null:Enum.valueOf(type,string(a,i)); }
}
