package pt.tecnico.sec.account;

import java.security.PublicKey;

public class EOAccount extends Account {

    private final PublicKey publicKey;

    public EOAccount(String address, long balance, PublicKey publicKey) {
        this(address, balance, publicKey, 0L);
    }

    public EOAccount(String address, long balance, PublicKey publicKey, long nonce) {
        super(address, balance, nonce);
        if (publicKey == null)
            throw new IllegalArgumentException("Public key cannot be null");
        this.publicKey = publicKey;
    }

    public PublicKey getPublicKey() { return publicKey; }

    @Override
    public boolean isContract() { return false; }

}