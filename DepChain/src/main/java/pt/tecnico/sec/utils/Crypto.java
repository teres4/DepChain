package pt.tecnico.sec.utils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pt.tecnico.sec.utils.threshsig.GroupKey;
import pt.tecnico.sec.utils.threshsig.KeyShare;
import pt.tecnico.sec.utils.threshsig.SigShare;
import pt.tecnico.sec.utils.threshsig.ThresholdSigException;

public interface Crypto {

    enum KeyType {
        REPLICA("config/keys/replicas"),
        CLIENT("config/keys/clients");

        private final String directory;

        KeyType(String directory) {
            this.directory = directory;
        }

        public String getDirectory() {
            return directory;
        }
    }

    public static PublicKey loadPublicKey(long id, KeyType keyType) throws Exception {
        String keyFilePath = keyType.getDirectory() + "/public_key" + id + ".pem";
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

    public static PrivateKey loadPrivateKey(long id, KeyType keyType) throws Exception {
        String keyFilePath = keyType.getDirectory() + "/private_key" + id + ".pem";
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

    public static PublicKey getReplicaPublicKey(long replicaId) throws Exception {
        return loadPublicKey(replicaId, KeyType.REPLICA);
    }

    public static PrivateKey getReplicaPrivateKey(long replicaId) throws Exception {
        return loadPrivateKey(replicaId, KeyType.REPLICA);
    }

    public static PublicKey getClientPublicKey(long clientId) throws Exception {
        return loadPublicKey(clientId, KeyType.CLIENT);
    }

    public static PrivateKey getClientPrivateKey(long clientId) throws Exception {
        return loadPrivateKey(clientId, KeyType.CLIENT);
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

    public static String getClientAddress(long clientId) throws Exception {
        PublicKey pubKey = getClientPublicKey(clientId);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(pubKey.getEncoded());
        // take last 20 bytes like Ethereum, prefix with 0x
        byte[] addressBytes = Arrays.copyOfRange(hash, hash.length - 20, hash.length);
        String ret = "0x" + HexFormat.of().formatHex(addressBytes);
        System.out.println("Generated address for client " + clientId + ": " + ret);
        return ret;
    }

    // Threshold Signature Methods

    public static GroupKey loadThresholdPublicKey(String path) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(path))) {
            return (GroupKey) ois.readObject();
        }
    }

    public static KeyShare loadThresholdPrivateKeyShare(String path) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(path))) {
            return (KeyShare) ois.readObject();
        }
    }

    public static SigShare thresholdSign(KeyShare keyShare, byte[] message) {
        return keyShare.sign(message);
    }

    public static BigInteger combineShares(SigShare[] sigShares, int k, int l,
            GroupKey groupKey, byte[] message)
            throws ThresholdSigException {
        return SigShare.combine(
                message,
                sigShares,
                k,
                l,
                groupKey.getModulus(),
                groupKey.getExponent());
    }

    public static boolean verifyThresholdSignature(byte[] message, BigInteger w,
            int l, GroupKey groupKey) {
        return SigShare.verifyW(
                message,
                w,
                l,
                groupKey.getModulus(),
                groupKey.getExponent());
    }

    public static Long getIdFromAddress(String address) {
        try {
            String configFile = KeyType.CLIENT.getDirectory() + "/config.json";
            String configContent = new String(Files.readAllBytes(Paths.get(configFile)));

            JsonObject root = JsonParser.parseString(configContent).getAsJsonObject();
            JsonArray members = root.getAsJsonArray("members");
            if (members == null) {
                return null;
            }

            for (JsonElement memberElement : members) {
                JsonObject member = memberElement.getAsJsonObject();
                String memberAddress = member.get("address").getAsString();
                if (memberAddress.equalsIgnoreCase(address)) {
                    return Long.parseLong(member.get("id").getAsString());
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getAddressFromId(long clientId) throws Exception {
        return getClientAddress(clientId);
    }

    public static boolean verifySignatureEOA(byte[] data, String base64Signature, String address) throws Exception {
        Long senderId = getIdFromAddress(address);
        if (senderId == null) {
            return false;
        }
        PublicKey pubKey = getClientPublicKey(senderId);
        return verifySignature(data, base64Signature, pubKey);
    }
}