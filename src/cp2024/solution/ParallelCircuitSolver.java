package cp2024.solution;

import cp2024.circuit.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class ParallelCircuitSolver implements CircuitSolver {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public CircuitValue solve(Circuit c) {
        try {
            Future<Boolean> future = executor.submit(() -> evaluate(c.getRoot()));
            return new ParallelCircuitValue(future);
        } catch (RejectedExecutionException e) {
            return new BrokenCircuitValue();
        }
    }

    private boolean evaluate(CircuitNode node) throws InterruptedException, ExecutionException {
        switch (node.getType()) {
            case LEAF -> {
                LeafNode leaf = (LeafNode) node;
                return leaf.getValue();
            }
            case NOT -> {
                return !evaluate(node.getArgs()[0]);
            }
            case AND -> {
                CircuitNode[] args = node.getArgs();
                return evaluateWithEarlyExit(
                        args,
                        (trueCount, falseCount) -> falseCount > 0,
                        trueCount -> trueCount == args.length
                );
            }
            case OR -> {
                CircuitNode[] args = node.getArgs();
                return evaluateWithEarlyExit(
                        args,
                        (trueCount, falseCount) -> trueCount > 0,
                        trueCount -> trueCount > 0
                );
            }
            case GT -> {
                ThresholdNode tNode = (ThresholdNode) node;
                CircuitNode[] args = tNode.getArgs();
                int threshold = tNode.getThreshold();
                return evaluateWithEarlyExit(
                        args,
                        (trueCount, falseCount) -> (trueCount > threshold || args.length - falseCount <= threshold),
                        trueCount -> trueCount > threshold
                );
            }
            case LT -> {
                ThresholdNode tNode = (ThresholdNode) node;
                CircuitNode[] args = tNode.getArgs();
                int threshold = tNode.getThreshold();
                return evaluateWithEarlyExit(
                        args,
                        (trueCount, falseCount) -> (trueCount >= threshold || args.length - falseCount < threshold),
                        trueCount -> trueCount < threshold
                );
            }
            case IF -> {
                CircuitNode[] args = node.getArgs();
                return evaluateIfNode(args);
            }
            default -> throw new IllegalStateException("Unexpected node type: " + node.getType());
        }

    }

    private void cancelFutures(List<Future<Boolean>> futures) {
        for (Future<Boolean> future : futures) {
            future.cancel(true);
        }
    }

    private boolean evaluateWithEarlyExit(
            CircuitNode[] args,
            BiPredicate<Integer, Integer> earlyExitCondition,
            Predicate<Integer> finalResultCondition) throws InterruptedException, ExecutionException {

        if (earlyExitCondition.test(0, 0)) {
            return finalResultCondition.test(0);
        }

        List<Future<Boolean>> childrenFutures = new ArrayList<>();

        try {

            ExecutorCompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);
            // Submit tasks for all children nodes
            for (CircuitNode arg : args) {
                Future<Boolean> future = completionService.submit(() -> evaluate(arg));
                childrenFutures.add(future);
            }

            int trueCount = 0;
            int falseCount = 0;

            for (int i = 0; i < args.length; i++) {
                boolean taskResult = completionService.take().get();
                if (taskResult) {
                    trueCount++;
                } else {
                    falseCount++;
                }

                if (earlyExitCondition.test(trueCount, falseCount)) {
                    // early exit condition
                    // cancelling children
                    cancelFutures(childrenFutures);
                    return finalResultCondition.test(trueCount);
                }
            }

            return finalResultCondition.test(trueCount);
        } catch (InterruptedException e) {
            // stop() was called or someone cancelled this thread's task
            // cancelling children
            cancelFutures(childrenFutures);
            throw e;
        }
    }

    private boolean evaluateIfNode(CircuitNode[] args) throws InterruptedException, ExecutionException {

        ExecutorCompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);
        Future<Boolean> conditionFuture = completionService.submit(() -> evaluate(args[0]));
        Future<Boolean> thenFuture = completionService.submit(() -> evaluate(args[1]));
        Future<Boolean> elseFuture = completionService.submit(() -> evaluate(args[2]));

        try {
            boolean ret = false;

            for (int i = 0; i < args.length; i++) {
                completionService.take();
                if (conditionFuture.isDone()) {
                    if (conditionFuture.get()) {
                        elseFuture.cancel(true);
                        if (thenFuture.isDone()) {
                            ret = thenFuture.get();
                            break;
                        }
                    } else {
                        thenFuture.cancel(true);
                        if (elseFuture.isDone()) {
                            ret = elseFuture.get();
                            break;
                        }
                    }
                } else if (thenFuture.isDone() && elseFuture.isDone() && thenFuture.get() == elseFuture.get()) {
                    conditionFuture.cancel(true);
                    ret = thenFuture.get();
                    break;
                }
            }
            return ret;
        } catch (InterruptedException e) {
            // stop() was called or someone cancelled this thread's task
            // cancelling children
            cancelFutures(List.of(conditionFuture, thenFuture, elseFuture));
            throw e;
        }
    }

    @Override
    public void stop() {
        executor.shutdownNow();
    }
}