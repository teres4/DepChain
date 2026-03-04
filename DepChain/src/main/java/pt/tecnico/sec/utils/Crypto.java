package pt.tecnico.sec.utils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import com.weavechain.sig.*;

public interface Crypto {

    public static PublicKey getPublicKey(String keyFilePath) throws Exception {
        Path path = Paths.get(keyFilePath);
        String keyString = new String(Files.readAllBytes(path));
        keyString = keyString.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                .replaceAll("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyString);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public static PrivateKey getPrivateKey(String keyFilePath) throws Exception {
        Path path = Paths.get(keyFilePath);
        String keyString = new String(Files.readAllBytes(path));
        keyString = keyString.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyString);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static SecretKey generateSecretKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        return keyGenerator.generateKey();
    }

    public static byte[] generateHMAC(SecretKey key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(data);
    }

    public static boolean verifyHmac(String message, String receivedHmac, SecretKey hmacKey) {
        try {
            byte[] calculatedHmacBytes = generateHMAC(hmacKey, message.getBytes());
            String calculatedHmac = Base64.getEncoder().encodeToString(calculatedHmacBytes);
            return calculatedHmac.equals(receivedHmac);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String signMessage(String message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes());
        byte[] signatureBytes = signature.sign();
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    public static boolean verifySignature(byte[] data, String base64Signature, PublicKey publicKey)
            throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(data);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);
        return signature.verify(signatureBytes);
    }

    public static byte[] encryptWithPublicKey(byte[] data, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(data);
    }

    public static byte[] decryptWithPrivateKey(String encryptedData, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(Base64.getDecoder().decode(encryptedData));
    }

    public static SecretKey bytesToSecretKey(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static String hash(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(data.getBytes());
        return Base64.getEncoder().encodeToString(hashBytes);
    }

    // Threshold Signature Methods

    /**
     * Load a threshold signature private key share from a file
     * 
     * @param keyFilePath Path to the key file
     * @return Private key share as byte array
     */
    public static byte[] loadThresholdPrivateKeyShare(String keyFilePath) throws Exception {
        Path path = Paths.get(keyFilePath);
        return Files.readAllBytes(path);
    }

    /**
     * Load a threshold signature public key from a file
     * 
     * @param keyFilePath Path to the public key file
     * @return Public key as byte array
     */
    public static byte[] loadThresholdPublicKey(String keyFilePath) throws Exception {
        Path path = Paths.get(keyFilePath);
        return Files.readAllBytes(path);
    }

    /**
     * Save a threshold signature private key share to a file
     * 
     * @param keyFilePath Path where to save the key file
     * @param keyShare    Key share to save
     */
    public static void saveThresholdPrivateKeyShare(String keyFilePath, byte[] keyShare) throws Exception {
        Path path = Paths.get(keyFilePath);
        Files.write(path, keyShare);
    }

    /**
     * Save a threshold signature public key to a file
     * 
     * @param keyFilePath Path where to save the public key file
     * @param publicKey   Public key to save
     */
    public static void saveThresholdPublicKey(String keyFilePath, byte[] publicKey) throws Exception {
        Path path = Paths.get(keyFilePath);
        Files.write(path, publicKey);
    }

    /**
     * Create a threshold signature using a private key share
     * Usage: Crypto.thresholdSign(thresholdSigInstance, index, privateShare,
     * message)
     * 
     * @param thresholdSig    ThresholdSigEd25519 instance (created with n and
     *                        threshold)
     * @param index           Node index (1-based indexing, e.g., 1, 2, 3...)
     * @param privateKeyShare Private key share bytes
     * @param message         Message bytes to sign
     * @return Signature share as byte array
     */
    public static byte[] thresholdSign(ThresholdSigEd25519 thresholdSig, int index, byte[] privateKeyShare,
            byte[] message) {
        try {
            // Call the sign method using reflection since the API varies by version
            return (byte[]) ThresholdSigEd25519.class
                    .getMethod("sign", int.class, byte[].class, byte[].class)
                    .invoke(thresholdSig, index, privateKeyShare, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign with threshold signature: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a threshold signature
     * Usage: Crypto.verifyThresholdSignature(publicKey, message, signature)
     * 
     * @param publicKey Threshold public key bytes
     * @param message   Original message bytes
     * @param signature Combined signature bytes to verify
     * @return true if signature is valid, false otherwise
     */
    public static boolean verifyThresholdSignature(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            // Call the static verify method
            return (boolean) ThresholdSigEd25519.class
                    .getMethod("verify", byte[].class, byte[].class, byte[].class)
                    .invoke(null, publicKey, message, signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Combine multiple threshold signature shares into a full signature
     * Usage: Crypto.combineThresholdSignatures(thresholdSigInstance, indices,
     * shares)
     * 
     * @param thresholdSig ThresholdSigEd25519 instance (same one used for signing)
     * @param indices      Array of node indices (1-based, e.g., {1, 2, 3})
     * @param shares       Array of signature share bytes from each node
     * @return Combined signature as byte array
     */
    public static byte[] combineThresholdSignatures(ThresholdSigEd25519 thresholdSig, int[] indices, byte[][] shares) {
        try {
            // Call the combine method using reflection
            return (byte[]) ThresholdSigEd25519.class
                    .getMethod("combine", int[].class, byte[][].class)
                    .invoke(thresholdSig, indices, shares);
        } catch (Exception e) {
            throw new RuntimeException("Failed to combine threshold signatures: " + e.getMessage(), e);
        }
    }

    /**
     * Generate threshold keys and save them to files
     * Usage: Crypto.generateAndSaveThresholdKeys(5, 3, "config/threshold-keys")
     * 
     * @param n         Total number of key shares (number of replicas)
     * @param threshold Minimum number of shares needed to create valid signature
     *                  (typically n - f, where f = (n-1)/3)
     * @param outputDir Directory where keys will be saved
     * @throws Exception if key generation or file writing fails
     */
    public static void generateAndSaveThresholdKeys(int n, int threshold, String outputDir) throws Exception {
        // Create output directory if it doesn't exist
        Path dirPath = Paths.get(outputDir);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
            System.out.println("Created directory: " + outputDir);
        }

        // Create ThresholdSigEd25519 instance
        ThresholdSigEd25519 thresholdSig = new ThresholdSigEd25519(n, threshold);

        // Generate keys using reflection to handle different library versions
        Object keyPair = ThresholdSigEd25519.class
                .getMethod("generate")
                .invoke(thresholdSig);

        // Get public key
        byte[] publicKey = (byte[]) keyPair.getClass()
                .getMethod("getPublic")
                .invoke(keyPair);

        // Get private shares
        byte[][] privateShares = (byte[][]) keyPair.getClass()
                .getMethod("getPrivateShares")
                .invoke(keyPair);

        // Save public key
        String publicKeyPath = outputDir + "/public_key.bin";
        saveThresholdPublicKey(publicKeyPath, publicKey);
        System.out.println("Saved public key to: " + publicKeyPath);

        // Save private key shares (1-based indexing for file names)
        for (int i = 0; i < n; i++) {
            String sharePath = outputDir + "/private_share_" + (i + 1) + ".bin";
            saveThresholdPrivateKeyShare(sharePath, privateShares[i]);
            System.out.println("Saved private share " + (i + 1) + " to: " + sharePath);
        }

        System.out.println("Successfully generated " + n + " key shares with threshold " + threshold);
    }

    // Threshold Signature Methods


}