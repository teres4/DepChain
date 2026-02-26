package pt.tecnico.sec.utils;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public interface Crypto {

    // TODO fetch asymmetric keys functions

    public static SecretKey generateSecretKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        return keyGenerator.generateKey();
    }

    public static String createHmac(String message, SecretKey hmacKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        byte[] hmacBytes = mac.doFinal(message.getBytes());
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    public static byte[] generateHMAC(SecretKey key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(data);
    }

    public static boolean verifyHmac(String message, String receivedHmac, SecretKey hmacKey) {
        try {
            String calculatedHmac = createHmac(message, hmacKey);
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
}