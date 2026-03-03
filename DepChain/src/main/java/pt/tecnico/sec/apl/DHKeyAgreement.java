package pt.tecnico.sec.apl;

import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.crypto.interfaces.DHPublicKey;

public class DHKeyAgreement {
    private int KEY_SIZE = 2048;
    
    private KeyPair keyPair;
    private KeyAgreement keyAgreement;

    public DHKeyAgreement() {}

    // generate new DH parameters and key pair
    public void initialize() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(this.KEY_SIZE);
        this.keyPair = kpg.generateKeyPair();
        
        // Initialize KeyAgreement with private key
        this.keyAgreement = KeyAgreement.getInstance("DH");
        this.keyAgreement.init(keyPair.getPrivate());
    }

    public void initializeWithReceivedPublicKey(byte[] receivedPublicKey) throws Exception {
        // Parse the received public key to extract DH parameters
        KeyFactory kf = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(receivedPublicKey);
        PublicKey publicKey = kf.generatePublic(x509KeySpec);
        DHParameterSpec dhParams = ((DHPublicKey) publicKey).getParams();
        
        // Generate own key pair using the same parameters
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(dhParams);
        this.keyPair = kpg.generateKeyPair();
        
        // Initialize KeyAgreement with private key
        this.keyAgreement = KeyAgreement.getInstance("DH");
        this.keyAgreement.init(keyPair.getPrivate());
    }

    public SecretKey computeSharedSecret(byte[] otherPublicKey) throws Exception {
        if (this.keyAgreement == null) {
            throw new IllegalStateException("Must call initialize() or initializeWithReceivedPublicKey() first");
        }
        
        // Parse the other party's public key
        KeyFactory kf = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(otherPublicKey);
        PublicKey publicKey = kf.generatePublic(x509KeySpec);
        
        // Perform key agreement
        keyAgreement.doPhase(publicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();
        
        return new SecretKeySpec(sharedSecret, 0, 16, "AES");
    }

    public byte[] getEncodedPublicKey() {
        if (this.keyPair == null) {
            throw new IllegalStateException("Must call initialize() or initializeWithReceivedPublicKey() first");
        }
        return keyPair.getPublic().getEncoded();
    }
}