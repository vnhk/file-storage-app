package com.bervan.filestorage.service;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.HexFormat;

@Service
public class FileEncryptionService {
    private static final String CIPHER = "AES/CTR/NoPadding";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 16;

    /** Encrypts file in-place. Returns {ivHex, saltHex, verifierHex} */
    public String[] encryptFile(Path file, String password) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] key = deriveKey(password, salt);

        byte[] plaintext = Files.readAllBytes(file);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, key, iv);
        Files.write(file, ciphertext);

        String ivHex = HexFormat.of().formatHex(iv);
        String saltHex = HexFormat.of().formatHex(salt);
        String verifierHex = computeVerifier(key, ivHex);
        return new String[]{ivHex, saltHex, verifierHex};
    }

    public byte[] deriveKey(String password, String saltHex) throws Exception {
        return deriveKey(password, HexFormat.of().parseHex(saltHex));
    }

    public boolean verifyPassword(byte[] key, String ivHex, String storedVerifierHex) throws Exception {
        String computed = computeVerifier(key, ivHex);
        return MessageDigest.isEqual(
            HexFormat.of().parseHex(computed),
            HexFormat.of().parseHex(storedVerifierHex)
        );
    }

    /**
     * Returns an InputStream that delivers decrypted bytes starting from rangeStart.
     * Caller is responsible for closing.
     */
    public InputStream createDecryptingStream(Path file, byte[] key, String ivHex, long rangeStart) throws Exception {
        byte[] iv = HexFormat.of().parseHex(ivHex);
        long blockNumber = rangeStart / 16;
        int blockOffset = (int) (rangeStart % 16);

        // Seek file to the start of the relevant AES block
        InputStream fileStream = Files.newInputStream(file);
        skipFully(fileStream, blockNumber * 16L);

        // Create cipher starting at the correct counter
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(addToCounter(iv, blockNumber)));

        CipherInputStream cipherStream = new CipherInputStream(fileStream, cipher);

        // Consume partial block offset
        if (blockOffset > 0) {
            byte[] discard = new byte[blockOffset];
            int remaining = blockOffset;
            while (remaining > 0) {
                int r = cipherStream.read(discard, blockOffset - remaining, remaining);
                if (r == -1) break;
                remaining -= r;
            }
        }
        return cipherStream;
    }

    // ---- private helpers ----

    private byte[] deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return skf.generateSecret(spec).getEncoded();
    }

    private byte[] crypt(int mode, byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private String computeVerifier(byte[] key, String ivHex) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update("BERVAN_VERIFY:".getBytes());
        mac.update(ivHex.getBytes());
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private byte[] addToCounter(byte[] iv, long blockNumber) {
        BigInteger counter = new BigInteger(1, iv).add(BigInteger.valueOf(blockNumber));
        byte[] raw = counter.toByteArray();
        byte[] result = new byte[16];
        if (raw.length >= 16) {
            System.arraycopy(raw, raw.length - 16, result, 0, 16);
        } else {
            System.arraycopy(raw, 0, result, 16 - raw.length, raw.length);
        }
        return result;
    }

    private byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) break;
            n -= skipped;
        }
    }
}
