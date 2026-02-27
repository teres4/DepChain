package pt.tecnico.sec.utils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Test utility class for cryptographic operations.
 * This class provides helper methods for testing that are not in the main
 * Crypto interface.
 */
public class TestCryptoUtils {

    /**
     * Generates an RSA key pair for testing purposes.
     * 
     * @return A new KeyPair containing public and private keys
     * @throws Exception if key generation fails
     */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }
}
