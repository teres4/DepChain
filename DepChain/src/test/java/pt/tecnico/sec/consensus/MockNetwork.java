package pt.tecnico.sec.consensus;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import pt.tecnico.sec.consensus.message.HotStuffMessage;

public class MockNetwork {
    private final Map<Long, Consensus> replicas = new ConcurrentHashMap<>();

    // interceptor - return false to drop, modify message to corrupt
    private BiFunction<Long, HotStuffMessage, HotStuffMessage> interceptor = (id, msg) -> msg;

    private final Map<Long, Map<Long, Double>> dropRates = new ConcurrentHashMap<>();

    private final Random random = new Random();

    public void registerReplica(long id, Consensus consensus) {
        replicas.put(id, consensus);
    }

    public void setInterceptor(BiFunction<Long, HotStuffMessage, HotStuffMessage> interceptor) {
        this.interceptor = interceptor;
    }

    public void setDropRate(long from, long to, double rate) {
        dropRates.computeIfAbsent(from, k -> new ConcurrentHashMap<>()).put(to, rate);
    }

    public void dropAllFrom(long replicaId) {
        for (long target : replicas.keySet()) {
            setDropRate(replicaId, target, 1.0);
        }
    }

    public void dropAllTo(long replicaId) {
        for (long source : replicas.keySet()) {
            setDropRate(source, replicaId, 1.0);
        }
    }

    public void resetDropRates() {
        dropRates.clear();
    }

    public void send(long from, long to, HotStuffMessage message) {
        // Check drop rate
        double dropRate = dropRates
            .getOrDefault(from, Map.of())
            .getOrDefault(to, 0.0);

        if (random.nextDouble() < dropRate) return; // dropped

        // Apply interceptor — allows modification or dropping
        HotStuffMessage modified = interceptor.apply(to, message);
        if (modified == null) return; // interceptor dropped it

        // Deliver to target replica
        Consensus target = replicas.get(to);
        if (target != null) {
            target.handleMessage(modified);
        }
    }
}