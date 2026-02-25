package main.java.pt.tecnico.sec.utils;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Mac;
import java.security.Signature;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.Base64;

public class Crypto {

    public static SecretKey generateHmacKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("HmacSHA256");
        return keyGenerator.generateKey();
    }

    public static String createHmac(String message, SecretKey hmacKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        byte[] hmacBytes = mac.doFinal(message.getBytes());
        return Base64.getEncoder().encodeToString(hmacBytes);
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
    
    public static boolean verifySignature(String message, String base64Signature, PublicKey publicKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(message.getBytes());
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);
        return signature.verify(signatureBytes);
    }

}