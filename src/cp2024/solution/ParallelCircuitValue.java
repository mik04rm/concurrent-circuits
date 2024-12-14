package cp2024.solution;

import cp2024.circuit.CircuitValue;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

public class ParallelCircuitValue implements CircuitValue {
    private final Future<Boolean> future;

    public ParallelCircuitValue(Future<Boolean> future) {
        this.future = future;
    }

    @Override
    public boolean getValue() throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            // solver.stop() was called before
            throw new InterruptedException();
        }
    }
}
