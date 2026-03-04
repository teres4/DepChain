package pt.tecnico.sec.utils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class DHKeyAgreement {
    // DH parameters 
    private static BigInteger P;
    private static BigInteger G;
    private static boolean initialized = false;
    
    private KeyPair keyPair;
    private KeyAgreement keyAgreement;

    private static String dhConfigFile = "config/dhparams.json";

    public DHKeyAgreement() {}

    public static void generateDhParams(int keySize) {
        // Initialize DH parameters 
        // Try to load from config, if not found, generate new ones
        try {
            File dhConfig = new File(dhConfigFile);
            if (!dhConfig.exists()) {
                // Generate new parameters (first time setup)
                System.out.println("No DH parameters found. Generating new parameters...");
                initializeWithGeneratedParameters(keySize);
                saveParametersToConfig(dhConfigFile);
            }
        } catch (Exception e) {
            System.err.println("Failed to initialize DH parameters: " + e.getMessage());
        }
    }
    
    public static void initializeWithGeneratedParameters(int keySize) {
        try {
            AlgorithmParameterGenerator paramGen = AlgorithmParameterGenerator.getInstance("DH");
            paramGen.init(keySize);
            AlgorithmParameters params = paramGen.generateParameters();
            DHParameterSpec dhSpec = params.getParameterSpec(DHParameterSpec.class);

            P = dhSpec.getP();
            G = dhSpec.getG();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate DH parameters", e);
        }
    }

    public static void saveParametersToConfig(String filename) throws Exception {
        JsonObject config = new JsonObject();
        config.addProperty("prime", P.toString(16).toUpperCase());
        config.addProperty("generator", G.toString());
        
        try (FileWriter writer = new FileWriter(new File(filename))) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(config, writer);
        }
    }
    
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
        
        // Initialize KeyAgreement with private key
        this.keyAgreement = KeyAgreement.getInstance("DH");
        this.keyAgreement.init(keyPair.getPrivate());
    }

    public SecretKey computeSharedSecret(byte[] otherPublicKey) throws Exception {
        if (this.keyAgreement == null) {
            throw new IllegalStateException("Must call initialize() first");
        }
        
        // Parse the other party's public key
        KeyFactory kf = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(otherPublicKey);
        PublicKey publicKey = kf.generatePublic(x509KeySpec);
        
        // Perform key agreement
        keyAgreement.doPhase(publicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();
        
        // TODO CHECK IF THE HASH IS REALLY SMTH RELEVANT TO DO
        // Hash the shared secret with SHA-256 before using as AES key
        // MessageDigest hash = MessageDigest.getInstance("SHA-256");
        // byte[] hashedSecret = hash.digest(sharedSecret);
        
        // // Use first 16 bytes for AES-128
        // return new SecretKeySpec(hashedSecret, 0, 16, "AES");
        return new SecretKeySpec(sharedSecret, 0, 16, "AES");
    }

    public byte[] getEncodedPublicKey() {
        if (this.keyPair == null) {
            throw new IllegalStateException("Must call initialize() first");
        }
        return keyPair.getPublic().getEncoded();
    }
}