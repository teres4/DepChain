package pt.tecnico.sec.utils;

import java.io.*;
import java.nio.file.*;

import pt.tecnico.sec.utils.threshsig.Dealer;
import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;

public class KeySetup {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: KeySetup <numReplicas> <threshold>");
            System.err.println("Example: KeySetup 4 3");
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

        System.out.println("=== Done ===");
        System.out.println("Keys saved to: " + dir.toAbsolutePath());
    }

    private static void saveObject(Object obj, Path path) throws Exception {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(path.toFile()))) {
            oos.writeObject(obj);
        }
    }
}