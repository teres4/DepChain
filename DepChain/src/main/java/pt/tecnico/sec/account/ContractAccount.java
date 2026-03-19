package pt.tecnico.sec.account;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ContractAccount extends Account {

    private final byte[] code;

    private final Map<String, String> storage;

    public ContractAccount(String address, long balance, byte[] code) {
        this(address, balance, code, new HashMap<>(), 0L);
    }

    public ContractAccount(String address, long balance, byte[] code,
                           Map<String, String> storage, long nonce) {
        super(address, balance, nonce);
        if (code == null)
            throw new IllegalArgumentException("Contract code cannot be null");

        this.code = Arrays.copyOf(code, code.length); 
        this.storage = new HashMap<>(storage);
    }

    public byte[] getCode() { 
        return Arrays.copyOf(code, code.length); 
    }
    public Map<String, String> getStorage() {
        return new HashMap<>(storage); 
    }

    @Override
    public boolean isContract() { return true; }

}
