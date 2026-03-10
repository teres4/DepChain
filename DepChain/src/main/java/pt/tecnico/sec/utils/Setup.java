package pt.tecnico.sec.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameterGenerator;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import javax.crypto.spec.DHParameterSpec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import pt.tecnico.sec.utils.threshsig.Dealer;
import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;

public class KeySetup {

    private static final String REPLICA_KEYS_DIR = "config/keys/replicas";
    private static final String CLIENT_KEYS_DIR = "config/keys/clients";
    private static final String DH_CONFIG_FILE = "config/dhparams.json";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=\"pt.tecnico.sec.utils.Setup\" -Dexec.args=\"<numReplicas> <threshold>\"");
            System.err.println("Example: mvn exec:java -Dexec.mainClass=\"pt.tecnico.sec.utils.Setup\" -Dexec.args=\"4 3\"");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        int k = Integer.parseInt(args[1]);
        int bits = 2048;

        System.out.println("=== Threshold Key Generation ===");
        System.out.println("Total replicas (n): " + n);
        System.out.println("Threshold     (k): " + k);
        System.out.println("Generating keys...");

        Dealer dealer = new Dealer(bits);
        dealer.generateKeys(k, n);

        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        System.out.println("Keys generated successfully.");
        System.out.println("Saving to files...");

        Path dir = Path.of("config", "keys");
        Files.createDirectories(dir);

        // save group key
        saveObject(groupKey, dir.resolve("groupkey.bin"));
        System.out.println("  Saved groupkey.bin");

        // save private shares
        for (int i = 0; i < n; i++) {
            saveObject(shares[i], dir.resolve("threshold_privateShare_" + (i+1) + ".bin"));
            System.out.println("  Saved threshold_privateShare_" + (i+1) + ".bin");
        }

        System.out.println("=== Generating Replica Keys ===");
        generateKeys(REPLICA_KEYS_DIR, 1, 4);

        System.out.println("=== Generating Client Keys ===");
        generateKeys(CLIENT_KEYS_DIR, 100, 102);

        System.out.println("=== Generating DH Parameters ===");
        generateDhParams(bits);

        System.out.println("=== Done ===");
    }

    private static void generateKeys(String directory, int startId, int endId) throws Exception {
        Path keysDir = Path.of(directory);
        Files.createDirectories(keysDir);

        for (int id = startId; id <= endId; id++) {
            String privateKeyPath = keysDir.resolve("private_key" + id + ".pem").toString();
            String publicKeyPath = keysDir.resolve("public_key" + id + ".pem").toString();

            System.out.println("Generating keys for ID " + id + "...");

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            try (FileWriter privateKeyWriter = new FileWriter(privateKeyPath)) {
                privateKeyWriter.write(new String(java.util.Base64.getEncoder().encode(keyPair.getPrivate().getEncoded())));
            }

            try (FileWriter publicKeyWriter = new FileWriter(publicKeyPath)) {
                publicKeyWriter.write(new String(java.util.Base64.getEncoder().encode(keyPair.getPublic().getEncoded())));
            }

            System.out.println("  Saved private_key" + id + ".pem and public_key" + id + ".pem");
        }
    }

    private static void generateDhParams(int keySize) throws Exception {
        File dhConfig = new File(DH_CONFIG_FILE);
        if (dhConfig.exists()) {
            System.out.println("DH parameters already exist. Skipping generation.");
            return;
        }

        System.out.println("Generating new DH parameters...");

        AlgorithmParameterGenerator paramGen = AlgorithmParameterGenerator.getInstance("DH");
        paramGen.init(keySize);
        AlgorithmParameters params = paramGen.generateParameters();
        DHParameterSpec dhSpec = params.getParameterSpec(DHParameterSpec.class);

        BigInteger P = dhSpec.getP();
        BigInteger G = dhSpec.getG();

        JsonObject config = new JsonObject();
        config.addProperty("prime", P.toString(16).toUpperCase());
        config.addProperty("generator", G.toString());

        try (FileWriter writer = new FileWriter(dhConfig)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(config, writer);
        }

        System.out.println("DH parameters generated and saved to " + DH_CONFIG_FILE);
    }

    private static void saveObject(Object obj, Path path) throws Exception {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(path.toFile()))) {
            oos.writeObject(obj);
        }
    }
}