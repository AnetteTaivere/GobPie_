package abstractdebugging;

import api.GoblintService;
import api.messages.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Abstract debugging server.
 * Implements the core logic of abstract debugging with the lsp4j DAP interface.
 *
 * @author Juhan Oskar Hennoste
 */
public class AbstractDebuggingServer implements IDebugProtocolServer {

    private static final int CFG_STEP_OFFSET = 1_000_000;
    private static final int ENTRY_STEP_OFFSET = 2_000_000;

    /**
     * Multiplier for thread id in frame id.
     * Frame id is calculated as threadId * FRAME_ID_THREAD_ID_MULTIPLIER + frameIndex.
     */
    private static final int FRAME_ID_THREAD_ID_MULTIPLIER = 100_000;

    private final GoblintService goblintService;

    private IDebugProtocolClient client;
    private CompletableFuture<Void> configurationDoneFuture = new CompletableFuture<>();

    private final List<GoblintLocation> breakpoints = new ArrayList<>();
    private int activeBreakpoint = -1;
    private final Map<Integer, ThreadState> threads = new LinkedHashMap<>();

    private final Map<String, Scope[]> nodeScopes = new HashMap<>();
    private final Map<Integer, Variable[]> storedVariables = new HashMap<>();
    private final AtomicInteger nextVariablesReference = new AtomicInteger(1);

    private final Logger log = LogManager.getLogger(AbstractDebuggingServer.class);


    public AbstractDebuggingServer(GoblintService goblintService) {
        this.goblintService = goblintService;
    }

    public void connectClient(IDebugProtocolClient client) {
        if (this.client != null) {
            throw new IllegalStateException("Client already connected");
        }
        this.client = client;
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        Capabilities capabilities = new Capabilities();
        capabilities.setSupportsConfigurationDoneRequest(true);
        capabilities.setSupportsStepInTargetsRequest(true);
        capabilities.setSupportsStepBack(true);
        return CompletableFuture.completedFuture(capabilities);
    }

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        // TODO: Handle cases where Goblint expected path is not relative to current working directory
        String sourcePath = Path.of(System.getProperty("user.dir")).relativize(Path.of(args.getSource().getPath())).toString();
        log.info("Setting breakpoints for " + args.getSource().getPath() + " (" + sourcePath + ")");

        List<GoblintLocation> newBreakpoints = Arrays.stream(args.getBreakpoints())
                .map(breakpoint -> new GoblintLocation(
                        sourcePath,
                        breakpoint.getLine(),
                        breakpoint.getColumn() == null ? 0 : breakpoint.getColumn()
                ))
                .toList();

        breakpoints.removeIf(b -> b.getFile().equals(sourcePath));
        breakpoints.addAll(newBreakpoints);

        var response = new SetBreakpointsResponse();
        var setBreakpoints = newBreakpoints.stream()
                .map(location -> {
                    var breakpoint = new Breakpoint();
                    breakpoint.setLine(location.getLine());
                    breakpoint.setColumn(location.getColumn());
                    breakpoint.setSource(args.getSource());
                    breakpoint.setVerified(true);
                    return breakpoint;
                })
                .toArray(Breakpoint[]::new);
        response.setBreakpoints(setBreakpoints);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
        // TODO: This should not be called by the IDE given our reported capabilities, but VSCode calls it anyway. Why?
        var response = new SetExceptionBreakpointsResponse();
        response.setBreakpoints(new Breakpoint[0]);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        log.info("Debug adapter configuration done");
        configurationDoneFuture.complete(null);
        configurationDoneFuture = new CompletableFuture<>();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        // Attach doesn't make sense for abstract debugging, but to avoid issues in case the client requests it anyway we just treat it as a launch request.
        return launch(args);
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        // Start configuration by notifying that client is initialized.
        client.initialized();
        log.info("Debug adapter initialized, waiting for configuration");
        // Wait for configuration to complete, then launch.
        return configurationDoneFuture
                .thenRun(() -> {
                    log.info("Debug adapter launched");
                    activeBreakpoint = -1;
                    runToNextBreakpoint(1);
                });
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        runToNextBreakpoint(1);
        return CompletableFuture.completedFuture(new ContinueResponse());
    }

    @Override
    public CompletableFuture<Void> reverseContinue(ReverseContinueArguments args) {
        runToNextBreakpoint(-1);
        return CompletableFuture.completedFuture(null);
    }

    // TODO: Figure out if entry and return nodes contain any meaningful info and if not then skip them in all step methods

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        var targetThread = threads.get(args.getThreadId());
        var currentNode = targetThread.getCurrentFrame().getNode();
        if (currentNode == null) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step over. Location is unavailable."));
        } else if (currentNode.outgoingCFGEdges().isEmpty()) {
            if (currentNode.outgoingReturnEdges().isEmpty()) {
                return CompletableFuture.failedFuture(userFacingError("Cannot step over. Reached last statement."));
            }
            var stepOutArgs = new StepOutArguments();
            stepOutArgs.setThreadId(args.getThreadId());
            stepOutArgs.setSingleThread(args.getSingleThread());
            stepOutArgs.setGranularity(args.getGranularity());
            return stepOut(stepOutArgs);
        }
        for (var thread : threads.values()) {
            NodeInfo node = thread.getCurrentFrame().getNode();
            if (node != null && node.outgoingCFGEdges().size() > 1 && !node.outgoingEntryEdges().isEmpty()) {
                return CompletableFuture.failedFuture(userFacingError("Ambiguous path through function" + (thread == targetThread ? "" : " for " + thread.getName()) +
                        ". Step into function to choose the desired path."));
            }
        }
        if (currentNode.outgoingCFGEdges().size() > 1) {
            return CompletableFuture.failedFuture(userFacingError("Branching control flow. Use step into target to choose the desired branch."));
        }
        var targetEdge = currentNode.outgoingCFGEdges().get(0);
        return stepAllThreadsAlongMatchingEdge(args.getThreadId(), targetEdge, NodeInfo::outgoingCFGEdges, false);
    }

    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        var currentNode = threads.get(args.getThreadId()).getCurrentFrame().getNode();
        if (currentNode == null) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step in. Location is unavailable."));
        }

        int targetId;
        if (args.getTargetId() != null) {
            targetId = args.getTargetId();
        } else if (currentNode.outgoingEntryEdges().size() == 1) {
            targetId = ENTRY_STEP_OFFSET;
        } else if (currentNode.outgoingEntryEdges().size() > 1) {
            return CompletableFuture.failedFuture(userFacingError("Ambiguous function call. Use step into target to choose the desired call"));
        } else {
            var nextArgs = new NextArguments();
            nextArgs.setThreadId(args.getThreadId());
            nextArgs.setSingleThread(args.getSingleThread());
            nextArgs.setGranularity(args.getGranularity());
            return next(nextArgs);
        }

        if (targetId >= ENTRY_STEP_OFFSET) {
            int targetIndex = targetId - ENTRY_STEP_OFFSET;
            var targetEdge = currentNode.outgoingEntryEdges().get(targetIndex);
            return stepAllThreadsAlongMatchingEdge(args.getThreadId(), targetEdge, NodeInfo::outgoingEntryEdges, true);
        } else if (targetId >= CFG_STEP_OFFSET) {
            int targetIndex = targetId - CFG_STEP_OFFSET;
            var targetEdge = currentNode.outgoingCFGEdges().get(targetIndex);
            return stepAllThreadsAlongMatchingEdge(args.getThreadId(), targetEdge, NodeInfo::outgoingCFGEdges, false);
        } else {
            return CompletableFuture.failedFuture(new IllegalStateException("Unknown step in target: " + targetId));
        }
    }

    @Override
    public CompletableFuture<StepInTargetsResponse> stepInTargets(StepInTargetsArguments args) {
        NodeInfo currentNode = getThreadByFrameId(args.getFrameId()).getCurrentFrame().getNode();

        List<StepInTarget> targets = new ArrayList<>();
        if (currentNode != null) {
            var entryEdges = currentNode.outgoingEntryEdges();
            for (int i = 0; i < entryEdges.size(); i++) {
                var edge = entryEdges.get(i);

                var target = new StepInTarget();
                target.setId(ENTRY_STEP_OFFSET + i);
                target.setLabel((edge.createsNewThread() ? "thread: " : "call: ") + edge.function() + "(" + String.join(", ", edge.args()) + ")");
                target.setLine(currentNode.location().getLine());
                target.setColumn(currentNode.location().getColumn());
                target.setEndLine(currentNode.location().getEndLine());
                target.setEndColumn(currentNode.location().getEndColumn());
                targets.add(target);
            }

            // Only show CFG edges as step in targets if there is no stepping over function calls and there is branching
            if (currentNode.outgoingEntryEdges().isEmpty() && currentNode.outgoingCFGEdges().size() > 1) {
                var cfgEdges = currentNode.outgoingCFGEdges();
                for (int i = 0; i < cfgEdges.size(); i++) {
                    var edge = cfgEdges.get(i);
                    var node = lookupNode(edge.nodeId());

                    var target = new StepInTarget();
                    target.setId(CFG_STEP_OFFSET + i);
                    target.setLabel("branch: " + edge.statementDisplayString());
                    target.setLine(node.location().getLine());
                    target.setColumn(node.location().getColumn());
                    target.setEndLine(node.location().getEndLine());
                    target.setEndColumn(node.location().getEndColumn());
                    targets.add(target);
                }
            }

            // Sort targets by the order they appear in code
            targets.sort(Comparator.comparing(StepInTarget::getLine).thenComparing(StepInTarget::getColumn));
        }

        var response = new StepInTargetsResponse();
        response.setTargets(targets.toArray(StepInTarget[]::new));
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        ThreadState targetThread = threads.get(args.getThreadId());
        if (targetThread.getCurrentFrame().getNode() == null) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step out. Location is unavailable."));
        } else if (!targetThread.hasPreviousFrame()) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step out. Reached top of call stack.")); // TODO: Improve wording
        } else if (targetThread.getPreviousFrame().isAmbiguousFrame()) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step out. Call stack is ambiguous."));
        }

        NodeInfo targetCallNode = targetThread.getPreviousFrame().getNode();
        assert targetCallNode != null;
        if (targetCallNode.outgoingCFGEdges().isEmpty()) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step out. Function never returns."));
        }

        Map<Integer, NodeInfo> targetNodes = new HashMap<>();
        for (var threadEntry : threads.entrySet()) {
            int threadId = threadEntry.getKey();
            ThreadState thread = threadEntry.getValue();

            // Skip all threads that have no known previous frame or whose previous frame has a different location compared to the target thread.
            if (!thread.hasPreviousFrame() || thread.getPreviousFrame().isAmbiguousFrame()
                    || (thread.getPreviousFrame().getNode() != null && !Objects.equals(thread.getPreviousFrame().getNode().cfgNodeId(), targetCallNode.cfgNodeId()))) {
                continue;
            }

            NodeInfo currentNode = thread.getCurrentFrame().getNode();
            NodeInfo targetNode;
            if (currentNode == null) {
                targetNode = null;
            } else {
                Predicate<String> filter;
                if (thread.getCurrentFrame().getLocalThreadIndex() != thread.getPreviousFrame().getLocalThreadIndex()) {
                    // If thread exit then control flow will not return to parent frame. No information to filter with so simply allow all possible nodes.
                    filter = _id -> true;
                } else {
                    // If not thread exit then filter possible nodes after function call in parent frame to those that are also possible return targets of current frame.
                    Set<String> returnNodeIds = findMatchingNodes(currentNode, NodeInfo::outgoingCFGEdges, e -> !e.outgoingReturnEdges().isEmpty()).stream()
                            .flatMap(n -> n.outgoingReturnEdges().stream())
                            .map(EdgeInfo::nodeId)
                            .collect(Collectors.toSet());
                    filter = returnNodeIds::contains;
                }

                NodeInfo currentCallNode = thread.getPreviousFrame().getNode();
                List<String> candidateTargetNodeIds = currentCallNode.outgoingCFGEdges().stream()
                        .map(EdgeInfo::nodeId)
                        .filter(filter)
                        .toList();

                if (candidateTargetNodeIds.isEmpty()) {
                    targetNode = null;
                } else if (candidateTargetNodeIds.size() == 1) {
                    targetNode = lookupNode(candidateTargetNodeIds.get(0));
                } else {
                    return CompletableFuture.failedFuture(userFacingError("Ambiguous return path" + (thread == targetThread ? "" : " for " + thread.getName()) +
                            ". Step to return manually to choose the desired path."));
                }
            }

            targetNodes.put(threadId, targetNode);
        }

        // Remove all threads that have no target node (note that threads with an unavailable (null) target node are kept).
        threads.keySet().removeIf(k -> !targetNodes.containsKey(k));
        // Remove topmost stack frame and step to target node
        for (var threadEntry : threads.entrySet()) {
            int threadId = threadEntry.getKey();
            ThreadState thread = threadEntry.getValue();

            thread.popFrame();
            thread.getCurrentFrame().setNode(targetNodes.get(threadId));
        }

        onThreadsStopped("step", args.getThreadId());

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stepBack(StepBackArguments args) {
        var targetThread = threads.get(args.getThreadId());
        var currentNode = targetThread.getCurrentFrame().getNode();
        if (currentNode == null) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step back. Location is unavailable."));
        } else if (currentNode.incomingCFGEdges().isEmpty()) {
            // TODO: Support stepping back out of function if caller is unambiguous and has the same CFG location for all threads
            return CompletableFuture.failedFuture(userFacingError("Cannot step back. Reached start of function."));
        } else if (currentNode.incomingCFGEdges().size() > 1) {
            return CompletableFuture.failedFuture(userFacingError("Cannot step back. Previous location is ambiguous."));
        }

        var targetCFGNodeId = currentNode.incomingCFGEdges().get(0).cfgNodeId();

        List<Pair<ThreadState, NodeInfo>> steps = new ArrayList<>();
        for (var thread : threads.values()) {
            var currentFrame = thread.getCurrentFrame();

            NodeInfo targetNode;
            if (currentFrame.getNode() != null) {
                List<CFGEdgeInfo> targetEdges = currentFrame.getNode().incomingCFGEdges().stream()
                        .filter(e -> e.cfgNodeId().equals(targetCFGNodeId))
                        .toList();
                if (targetEdges.isEmpty()) {
                    return CompletableFuture.failedFuture(userFacingError("Cannot step back. No matching path from " + thread.getName()));
                } else if (targetEdges.size() > 1) {
                    return CompletableFuture.failedFuture(userFacingError("Cannot step back. Path is ambiguous from " + thread.getName()));
                }
                targetNode = lookupNode(targetEdges.get(0).nodeId());
            } else if (currentFrame.getLastReachableNode() != null && currentFrame.getLastReachableNode().cfgNodeId().equals(targetCFGNodeId)) {
                targetNode = currentFrame.getLastReachableNode();
            } else {
                continue;
            }

            steps.add(Pair.of(thread, targetNode));
        }

        for (var step : steps) {
            ThreadState thread = step.getLeft();
            NodeInfo targetNode = step.getRight();
            thread.getCurrentFrame().setNode(targetNode);
        }

        onThreadsStopped("step", args.getThreadId());

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> goto_(GotoArguments args) {
        // TODO
        // TODO: Recover unreachable branches when jumping backwards to / over node where branches diverged.
        return IDebugProtocolServer.super.goto_(args);
    }

    @Override
    public CompletableFuture<GotoTargetsResponse> gotoTargets(GotoTargetsArguments args) {
        // TODO
        return IDebugProtocolServer.super.gotoTargets(args);
    }

    /**
     * Runs to next breakpoint in given direction.
     *
     * @param direction 1 to run to next breakpoint, -1 to run to previous breakpoint.
     */
    private void runToNextBreakpoint(int direction) {
        // Note: We treat breaking on entry as the only breakpoint if no breakpoints are set.
        // TODO: Changing breakpoints when the debugger is active can cause breakpoints to be skipped or visited twice.
        while (activeBreakpoint + direction < Math.max(1, breakpoints.size()) && activeBreakpoint + direction >= 0) {
            activeBreakpoint += direction;

            String stopReason;
            GoblintLocation targetLocation;
            List<NodeInfo> targetNodes;
            if (breakpoints.size() == 0) {
                stopReason = "entry";
                targetLocation = null;
                targetNodes = lookupNodes(new LookupParams());
            } else {
                stopReason = "breakpoint";
                targetLocation = breakpoints.get(activeBreakpoint);
                targetNodes = lookupNodes(new LookupParams(targetLocation)).stream()
                        .filter(n -> n.location().getLine() <= targetLocation.getLine() && targetLocation.getLine() <= n.location().getEndLine())
                        .toList();
                if (!targetNodes.isEmpty()) {
                    // TODO: Instead we should get the first matching CFG node and then request corresponding ARG nodes for that.
                    String cfgNodeId = targetNodes.get(0).cfgNodeId();
                    targetNodes = targetNodes.stream().filter(n -> n.cfgNodeId().equals(cfgNodeId)).toList();
                }
            }

            if (!targetNodes.isEmpty()) {
                setThreads(
                        targetNodes.stream()
                                .map(node -> new ThreadState("breakpoint " + node.nodeId(), assembleStackTrace(node)))
                                .toList()
                );

                onThreadsStopped(stopReason, threads.keySet().stream().findFirst().orElseThrow());

                log.info("Stopped on breakpoint " + activeBreakpoint + " (" + targetLocation + ")");
                return;
            }

            // TODO: Should somehow notify the client that the breakpoint is unreachable?
            log.info("Skipped unreachable breakpoint " + activeBreakpoint + " (" + targetLocation + ")");
        }

        log.info("All breakpoints visited. Terminating debugger.");
        var event = new TerminatedEventArguments();
        client.terminated(event);
    }

    /**
     * Steps all threads along an edge matching primaryTargetEdge.
     * Edges are matched by ARG node. If no edge with matching ARG node is found then edges are matched by CFG node.
     * If no edge with matching CFG node is found then thread becomes unavailable.
     *
     * @throws ResponseErrorException if the target node is ambiguous ie there are multiple candidate edges that have the target CFG node.
     */
    private CompletableFuture<Void> stepAllThreadsAlongMatchingEdge(int primaryThreadId, EdgeInfo primaryTargetEdge, Function<NodeInfo, List<? extends EdgeInfo>> getCandidateEdges, boolean addFrame) {
        // Note: It is important that all threads, including threads with unavailable location, are stepped, because otherwise the number of added stack frames could get out of sync.
        List<Pair<ThreadState, EdgeInfo>> steps = new ArrayList<>();
        for (var thread : threads.values()) {
            // This is will throw if there are multiple distinct target edges with the same target CFG node.
            // TODO: Somehow ensure this can never happen.
            //  Options:
            //  * Throw error (current approach) (problem: might make it impossible to step at all in some cases. it is difficult to provide meaningful error messages for all cases)
            //  * Split thread into multiple threads. (problem: complicates 'step back' and maintaining thread ordering)
            //  * Identify true source of branching and use it to disambiguate (problem: there might not be a source of branching in all cases. complicates stepping logic)
            //  * Make ambiguous threads unavailable (problem: complicates mental model of when threads become unavailable.)
            EdgeInfo targetEdge;
            if (thread.getCurrentFrame().getNode() == null) {
                targetEdge = null;
            } else {
                List<? extends EdgeInfo> candidateEdges = getCandidateEdges.apply(thread.getCurrentFrame().getNode());
                EdgeInfo targetEdgeByARGNode = candidateEdges.stream()
                        .filter(e -> e.nodeId().equals(primaryTargetEdge.nodeId()))
                        .findAny().orElse(null);
                if (targetEdgeByARGNode != null) {
                    targetEdge = targetEdgeByARGNode;
                } else {
                    List<? extends EdgeInfo> targetEdgesByCFGNode = candidateEdges.stream()
                            .filter(e -> e.cfgNodeId().equals(primaryTargetEdge.cfgNodeId()))
                            .toList();
                    if (targetEdgesByCFGNode.size() > 1) {
                        // Log error because if 'Step into target' menu is open then errors returned by this function are not shown in VSCode.
                        // TODO: Open issue about this in VSCode issue tracker.
                        log.error("Cannot step. Path is ambiguous for " + thread.getName() + ".");
                        return CompletableFuture.failedFuture(userFacingError("Cannot step. Path is ambiguous for " + thread.getName() + "."));
                    }
                    targetEdge = targetEdgesByCFGNode.size() == 1 ? targetEdgesByCFGNode.get(0) : null;
                }
            }

            steps.add(Pair.of(thread, targetEdge));
        }
        for (var step : steps) {
            ThreadState thread = step.getLeft();
            EdgeInfo targetEdge = step.getRight();
            NodeInfo targetNode = targetEdge == null ? null : lookupNode(targetEdge.nodeId());
            if (addFrame) {
                boolean isNewThread = targetEdge instanceof FunctionCallEdgeInfo fce && fce.createsNewThread();
                thread.pushFrame(new StackFrameState(targetNode, false, thread.getCurrentFrame().getLocalThreadIndex() - (isNewThread ? 1 : 0)));
            } else {
                thread.getCurrentFrame().setNode(targetNode);
            }
        }

        onThreadsStopped("step", primaryThreadId);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        var response = new ThreadsResponse();
        Thread[] responseThreads = threads.entrySet().stream()
                .map(entry -> {
                    Thread thread = new Thread();
                    thread.setId(entry.getKey());
                    thread.setName(entry.getValue().getName());
                    return thread;
                })
                .toArray(Thread[]::new);
        response.setThreads(responseThreads);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        var thread = threads.get(args.getThreadId());
        if (thread.getCurrentFrame().getNode() == null) {
            return CompletableFuture.failedFuture(userFacingError("No matching path"));
        }

        final int currentThreadId = thread.getCurrentFrame().getLocalThreadIndex();
        StackFrame[] stackFrames = new StackFrame[thread.getFrames().size()];
        for (int i = 0; i < thread.getFrames().size(); i++) {
            var frame = thread.getFrames().get(i);
            assert frame.getNode() != null;

            var stackFrame = new StackFrame();
            stackFrame.setId(getFrameId(args.getThreadId(), i));
            // TODO: Notation for ambiguous frames and parent threads could be clearer.
            stackFrame.setName((frame.isAmbiguousFrame() ? "? " : "") + (frame.getLocalThreadIndex() != currentThreadId ? "^" : "") + frame.getNode().function() + " " + frame.getNode().nodeId());
            var location = frame.getNode().location();
            stackFrame.setLine(location.getLine());
            stackFrame.setColumn(location.getColumn());
            stackFrame.setEndLine(location.getEndLine());
            stackFrame.setEndColumn(location.getEndColumn());
            var source = new Source();
            source.setName(location.getFile());
            source.setPath(new File(location.getFile()).getAbsolutePath());
            stackFrame.setSource(source);

            stackFrames[i] = stackFrame;
        }

        var response = new StackTraceResponse();
        response.setStackFrames(stackFrames);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        var frame = getFrame(args.getFrameId());
        if (frame.getNode() == null) {
            throw new IllegalStateException("Attempt to request variables for unavailable frame " + args.getFrameId());
        }

        Scope[] scopes = nodeScopes.computeIfAbsent(frame.getNode().nodeId(), nodeId -> {
            var state = lookupState(nodeId);

            int allScopeReference = storeDomainValuesAsVariables(Stream.concat(
                    Stream.of(Map.entry("<locked>", state.get("mutex"))),
                    state.get("base").getAsJsonObject().get("value domain").getAsJsonObject()
                            .entrySet().stream()
                            // TODO: Temporary values should be shown when they are assigned to.
                            // TODO: If the user creates a variable named tmp then it will be hidden as well.
                            .filter(entry -> !entry.getKey().startsWith("tmp"))
            ));

            var allScope = new Scope();
            allScope.setName("All");
            allScope.setVariablesReference(allScopeReference);

            int rawScopeReference = storeDomainValuesAsVariables(Stream.of(
                    Map.entry("(arg/state)", state)
            ));

            var rawScope = new Scope();
            rawScope.setName("Raw");
            rawScope.setVariablesReference(rawScopeReference);

            return new Scope[]{allScope, rawScope};
        });

        var response = new ScopesResponse();
        response.setScopes(scopes);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        var response = new VariablesResponse();
        response.setVariables(storedVariables.get(args.getVariablesReference()));
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        var frame = getFrame(args.getFrameId());
        if (frame.getNode() == null) {
            throw new IllegalStateException("Attempt to evaluate expression in unavailable frame " + args.getFrameId());
        }

        EvalIntResult result;
        try {
            result = evaluateIntegerExpression(frame.getNode().nodeId(), args.getExpression());
        } catch (RequestFailedException e) {
            return CompletableFuture.failedFuture(userFacingError(e.getMessage()));
        }

        var response = new EvaluateResponse();
        response.setResult(domainValueToString(result.getRaw()));
        return CompletableFuture.completedFuture(response);
    }

    private int storeDomainValuesAsVariables(Stream<Map.Entry<String, JsonElement>> values) {
        var variables = values
                .map(entry -> {
                    String name = entry.getKey();
                    JsonElement value = entry.getValue();

                    var variable = new Variable();
                    variable.setName(entry.getKey());
                    if (value.isJsonObject()) {
                        variable.setValue(
                                "{" + value.getAsJsonObject()
                                        .keySet().stream()
                                        .map(k -> k + ": …")
                                        .collect(Collectors.joining(", ")) + "}"
                        );
                        variable.setVariablesReference(
                                storeDomainValuesAsVariables(value.getAsJsonObject().entrySet().stream())
                        );
                    } else {
                        variable.setValue(domainValueToString(value));
                    }
                    return variable;
                })
                .toArray(Variable[]::new);
        return storeVariables(variables);
    }

    private String domainValueToString(JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        } else if (value.isJsonArray()) {
            return "{" + StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                    .map(this::domainValueToString)
                    .collect(Collectors.joining(", ")) + "}";
        } else if (value.isJsonObject()) {
            return "{" + value.getAsJsonObject().entrySet().stream()
                    .map(e -> e.getKey() + ": " + domainValueToString(e.getValue()))
                    .collect(Collectors.joining(", ")) + "}";
        } else {
            throw new IllegalArgumentException("Unknown domain value type: " + value.getClass());
        }
    }

    // Helper methods:

    private static int getFrameId(int threadId, int frameIndex) {
        return threadId * FRAME_ID_THREAD_ID_MULTIPLIER + frameIndex;
    }

    private StackFrameState getFrame(int frameId) {
        int threadId = frameId / FRAME_ID_THREAD_ID_MULTIPLIER;
        int frameIndex = frameId % FRAME_ID_THREAD_ID_MULTIPLIER;
        return threads.get(threadId).getFrames().get(frameIndex);
    }

    private ThreadState getThreadByFrameId(int frameId) {
        int threadId = frameId / FRAME_ID_THREAD_ID_MULTIPLIER;
        return threads.get(threadId);
    }

    private void setThreads(List<ThreadState> newThreads) {
        threads.clear();
        for (int i = 0; i < newThreads.size(); i++) {
            threads.put(i, newThreads.get(i));
        }
    }

    private int storeVariables(Variable[] variables) {
        int variablesReference = nextVariablesReference.getAndIncrement();
        storedVariables.put(variablesReference, variables);
        return variablesReference;
    }

    /**
     * Logic that should run every time after threads have stopped after a step or breakpoint.
     * Notifies client that threads have stopped and clears caches that should be invalidated whenever thread state changes.)
     */
    private void onThreadsStopped(String stopReason, int primaryThreadId) {
        nextVariablesReference.set(1);
        storedVariables.clear();
        nodeScopes.clear();

        // Sending the stopped event before the response to the step request is a violation of the DAP spec.
        // There is no clean way to do the operations in the correct order with lsp4j (see https://github.com/eclipse/lsp4j/issues/229),
        // multiple debug adapters seem to have the same issue, including the official https://github.com/microsoft/vscode-mock-debug,
        // and this has caused no issues in testing with VSCode.
        // Given all these considerations doing this in the wrong order is considered acceptable for now.
        // TODO: If https://github.com/eclipse/lsp4j/issues/229 ever gets resolved do this in the correct order.
        var event = new StoppedEventArguments();
        event.setReason(stopReason);
        event.setThreadId(primaryThreadId);
        event.setAllThreadsStopped(true);
        client.stopped(event);
    }

    private List<StackFrameState> assembleStackTrace(NodeInfo startNode) {
        int curThreadId = 0;
        List<StackFrameState> stackFrames = new ArrayList<>();
        stackFrames.add(new StackFrameState(startNode, false, curThreadId));
        NodeInfo entryNode;
        do {
            entryNode = getEntryNode(stackFrames.get(stackFrames.size() - 1).getNode());
            boolean ambiguous = entryNode.incomingEntryEdges().size() > 1;
            for (var edge : entryNode.incomingEntryEdges()) {
                if (edge.createsNewThread()) {
                    curThreadId += 1;
                }
                var node = lookupNode(edge.nodeId());
                stackFrames.add(new StackFrameState(node, ambiguous, curThreadId));
            }
        } while (entryNode.incomingEntryEdges().size() == 1);
        return stackFrames;
    }

    private NodeInfo getEntryNode(NodeInfo node) {
        NodeInfo entryNode = _getEntryNode(node, new HashSet<>());
        if (entryNode == null) {
            throw new IllegalStateException("Failed to find entry node for node " + node.nodeId());
        }
        return entryNode;
    }

    private NodeInfo _getEntryNode(NodeInfo node, Set<String> seenNodes) {
        if (node.incomingCFGEdges().isEmpty()) {
            return node;
        }
        if (seenNodes.contains(node.nodeId())) {
            return null;
        }
        seenNodes.add(node.nodeId());
        for (var edge : node.incomingCFGEdges()) {
            NodeInfo entryNode = _getEntryNode(lookupNode(edge.nodeId()), seenNodes);
            if (entryNode != null) {
                return entryNode;
            }
        }
        return null;
    }

    private List<NodeInfo> findMatchingNodes(NodeInfo node, Function<NodeInfo, Collection<? extends EdgeInfo>> candidateEdges, Predicate<NodeInfo> condition) {
        List<NodeInfo> foundNodes = new ArrayList<>();
        _findMatchingNodes(node, candidateEdges, condition, new HashSet<>(), foundNodes);
        return foundNodes;
    }

    private void _findMatchingNodes(NodeInfo node, Function<NodeInfo, Collection<? extends EdgeInfo>> candidateEdges, Predicate<NodeInfo> condition,
                                    Set<String> seenNodes, List<NodeInfo> foundNodes) {
        if (seenNodes.contains(node.nodeId())) {
            return;
        }
        seenNodes.add(node.nodeId());
        if (condition.test(node)) {
            foundNodes.add(node);
        }
        for (var edge : candidateEdges.apply(node)) {
            _findMatchingNodes(lookupNode(edge.nodeId()), candidateEdges, condition, seenNodes, foundNodes);
        }
    }

    /**
     * Returns an exception that will be shown in the IDE as the message with no modifications and no additional context.
     */
    private ResponseErrorException userFacingError(String message) {
        return new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed, message, null));
    }

    // Synchronous convenience methods around GoblintService:

    private List<NodeInfo> lookupNodes(LookupParams params) {
        return goblintService.arg_lookup(params)
                .thenApply(result -> result.stream()
                        .map(lookupResult -> {
                            NodeInfo nodeInfo = lookupResult.toNodeInfo();
                            if (!nodeInfo.outgoingReturnEdges().isEmpty() && nodeInfo.outgoingCFGEdges().isEmpty()) {
                                // Location of return nodes is generally the entire function.
                                // That looks strange, so we patch it to be only the end of the last line of the function.
                                // TODO: Maybe it would be better to adjust location when returning stack so the node info retains the original location
                                return nodeInfo.withLocation(new GoblintLocation(
                                        nodeInfo.location().getFile(),
                                        nodeInfo.location().getEndLine(), nodeInfo.location().getEndColumn(),
                                        nodeInfo.location().getEndLine(), nodeInfo.location().getEndColumn()
                                ));
                            } else {
                                return nodeInfo;
                            }
                        })
                        .toList())
                .join();
    }

    /**
     * @throws RequestFailedException if the node was not found or multiple nodes were found
     */
    private NodeInfo lookupNode(String nodeId) {
        var nodes = lookupNodes(new LookupParams(nodeId));
        return switch (nodes.size()) {
            case 0 -> throw new RequestFailedException("Node with id " + nodeId + " not found");
            case 1 -> nodes.get(0);
            default -> throw new RequestFailedException("Multiple nodes with id " + nodeId + " found");
        };
    }

    private JsonObject lookupState(String nodeId) {
        return goblintService.arg_state(new ARGNodeParams(nodeId)).join();
    }

    /**
     * @throws RequestFailedException if evaluating the expression failed, generally because the expression is syntactically or semantically invalid.
     */
    private EvalIntResult evaluateIntegerExpression(String nodeId, String expression) {
        try {
            return goblintService.arg_eval_int(new ARGExprQueryParams(nodeId, expression)).join();
        } catch (CompletionException e) {
            // Promote request failure to public API error because it is usually caused by the user entering an invalid expression
            // and the error message contains useful info about why the expression was invalid.
            if (e.getCause() instanceof ResponseErrorException re && re.getResponseError().getCode() == ResponseErrorCode.RequestFailed.getValue()) {
                throw new RequestFailedException(re.getMessage());
            }
            throw e;
        }
    }

}
