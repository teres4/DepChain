package pt.tecnico.sec.account;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ContractAccount extends Account {

    private final byte[] runtimecode;

    private final Map<String, String> storage;

    public ContractAccount(String address, long balance, byte[] runtimecode) {
        this(address, balance, runtimecode, new HashMap<>(), 0L);
    }

    public ContractAccount(String address, long balance, byte[] runtimecode,
            Map<String, String> storage, long nonce) {
        super(address, balance, nonce);
        if (runtimecode == null)
            throw new IllegalArgumentException("Contract runtime code cannot be null");

        this.runtimecode = Arrays.copyOf(runtimecode, runtimecode.length);
        this.storage = new HashMap<>(storage);
    }

    // new ContractAccount(
    // contractAddr.toHexString(),
    // 1000000, // DepCoin balance
    // sender.getNonce(), // nonce at time of deploy
    // runtimeCode,
    // initialStorage);

    public ContractAccount(String address, long balance, long nonce, byte[] runtimecode,
            Map<String, String> storage) {
        super(address, balance, nonce);
        if (runtimecode == null)
            throw new IllegalArgumentException("Contract runtime code cannot be null");

        this.runtimecode = Arrays.copyOf(runtimecode, runtimecode.length);
        this.storage = new HashMap<>(storage);
    }

    public byte[] getRuntimeCode() {
        return Arrays.copyOf(runtimecode, runtimecode.length);
    }

    public Map<String, String> getStorage() {
        return new HashMap<>(storage);
    }

    @Override
    public boolean isContract() {
        return true;
    }

    public void updateStorage(Map<String, String> newStorage) {
        storage.clear();
        storage.putAll(newStorage);
    }
}
