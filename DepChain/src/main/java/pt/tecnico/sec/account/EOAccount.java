package pt.tecnico.sec.account;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class EOAccount extends Account {

    private final String publicKeyBase64;

    public EOAccount(String address, long balance, PublicKey publicKey) {
        this(address, balance, publicKey, 0L);
    }

    public EOAccount(String address, long balance, PublicKey publicKey, long nonce) {
        super(address, balance, nonce);
        // PublicKey can be null for simulations or accounts from genesis state
        this.publicKeyBase64 = encodePublicKey(publicKey);
    }

    public PublicKey getPublicKey() {
        if (publicKeyBase64 == null) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode public key", e);
        }
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    private static String encodePublicKey(PublicKey publicKey) {
        if (publicKey == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    @Override
    public boolean isContract() { return false; }

}