package pt.tecnico.sec.utils;

import java.io.File;
import java.io.FileReader;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class DHKeyAgreement {
    // DH parameters 
    private static BigInteger P;
    private static BigInteger G;
    private static boolean initialized = false;
    
    private KeyPair keyPair;

    private static String dhConfigFile = "config/dhparams.json";

    public DHKeyAgreement() {}
    
    public static void loadParametersFromConfig() throws Exception {
        if (initialized) {
            return;
        }
        
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(new File(dhConfigFile))) {
            JsonObject config = gson.fromJson(reader, JsonObject.class);
            
            P = new BigInteger(config.get("prime").getAsString(), 16);
            G = new BigInteger(config.get("generator").getAsString());
            
            initialized = true;
        }
    }

    // Generate key pair using global DH parameters
    public void initialize() throws Exception {
        if (!initialized) {
            loadParametersFromConfig();
        }
        
        DHParameterSpec dhParamSpec = new DHParameterSpec(P, G);
        
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DH");
        kpg.initialize(dhParamSpec);
        this.keyPair = kpg.generateKeyPair();
    }

    public SecretKey computeSharedSecret(byte[] otherPublicKey) throws Exception {
        if (this.keyPair == null) {
            throw new IllegalStateException("Must call initialize() first");
        }

        // Create a fresh KeyAgreement per call - the shared instance is NOT thread-safe.
        // KeyAgreement.doPhase() + generateSecret() are stateful: doPhase() sets internal
        // state and generateSecret() consumes and clears it. Concurrent calls on the same
        // instance interleave their doPhase/generateSecret pairs, producing wrong shared
        // secrets (-> HMAC failures) or "Key agreement has not been completed yet" errors.
        KeyAgreement ka = KeyAgreement.getInstance("DH");
        ka.init(keyPair.getPrivate());

        KeyFactory kf = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(otherPublicKey);
        PublicKey publicKey = kf.generatePublic(x509KeySpec);

        ka.doPhase(publicKey, true);
        byte[] sharedSecret = ka.generateSecret();

        return new SecretKeySpec(sharedSecret, 0, 16, "AES");
    }

    public byte[] getEncodedPublicKey() {
        if (this.keyPair == null) {
            throw new IllegalStateException("Must call initialize() first");
        }
        return keyPair.getPublic().getEncoded();
    }
}