//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================
package services

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.{GCMParameterSpec, PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKeyFactory}

/**
 * AES encryption service for storing sensitive credentials.
 * Uses AES-GCM with PBKDF2 key derivation from application secret.
 */
object CredentialEncryption {

  private val ALGORITHM = "AES/GCM/NoPadding"
  private val KEY_ALGORITHM = "AES"
  private val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
  private val GCM_TAG_LENGTH = 128
  private val GCM_IV_LENGTH = 12
  private val SALT_LENGTH = 16
  private val KEY_LENGTH = 256
  private val ITERATIONS = 65536

  // Prefix to identify encrypted values
  private val ENCRYPTED_PREFIX = "ENC:"

  /**
   * Encrypt a plain text value using AES-GCM.
   *
   * @param plainText The value to encrypt
   * @param secret The application secret used as basis for key derivation
   * @return Base64-encoded encrypted value with prefix, or empty string if input is empty
   */
  def encrypt(plainText: String, secret: String): String = {
    if (plainText == null || plainText.isEmpty) return ""

    try {
      // Generate random salt and IV
      val random = new SecureRandom()
      val salt = new Array[Byte](SALT_LENGTH)
      val iv = new Array[Byte](GCM_IV_LENGTH)
      random.nextBytes(salt)
      random.nextBytes(iv)

      // Derive key from secret using PBKDF2
      val key = deriveKey(secret, salt)

      // Initialize cipher
      val cipher = Cipher.getInstance(ALGORITHM)
      val gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv)
      cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

      // Encrypt
      val cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8))

      // Combine salt + iv + ciphertext and encode
      val combined = new Array[Byte](salt.length + iv.length + cipherText.length)
      System.arraycopy(salt, 0, combined, 0, salt.length)
      System.arraycopy(iv, 0, combined, salt.length, iv.length)
      System.arraycopy(cipherText, 0, combined, salt.length + iv.length, cipherText.length)

      ENCRYPTED_PREFIX + Base64.getEncoder.encodeToString(combined)
    } catch {
      case e: Exception =>
        throw new RuntimeException("Encryption failed", e)
    }
  }

  /**
   * Decrypt an encrypted value.
   *
   * @param encryptedText The encrypted value (with ENC: prefix)
   * @param secret The application secret used as basis for key derivation
   * @return The decrypted plain text, or empty string if input is empty
   */
  def decrypt(encryptedText: String, secret: String): String = {
    if (encryptedText == null || encryptedText.isEmpty) return ""

    // Check for prefix
    if (!encryptedText.startsWith(ENCRYPTED_PREFIX)) {
      // Not encrypted, return as-is (backwards compatibility)
      return encryptedText
    }

    try {
      // Remove prefix and decode
      val combined = Base64.getDecoder.decode(encryptedText.substring(ENCRYPTED_PREFIX.length))

      // Extract salt, iv, and ciphertext
      val salt = new Array[Byte](SALT_LENGTH)
      val iv = new Array[Byte](GCM_IV_LENGTH)
      val cipherText = new Array[Byte](combined.length - SALT_LENGTH - GCM_IV_LENGTH)

      System.arraycopy(combined, 0, salt, 0, SALT_LENGTH)
      System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH)
      System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, cipherText, 0, cipherText.length)

      // Derive key from secret using same salt
      val key = deriveKey(secret, salt)

      // Initialize cipher for decryption
      val cipher = Cipher.getInstance(ALGORITHM)
      val gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv)
      cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

      // Decrypt
      val plainText = cipher.doFinal(cipherText)
      new String(plainText, StandardCharsets.UTF_8)
    } catch {
      case e: Exception =>
        throw new RuntimeException("Decryption failed", e)
    }
  }

  /**
   * Check if a value is encrypted (has the ENC: prefix).
   */
  def isEncrypted(value: String): Boolean = {
    value != null && value.startsWith(ENCRYPTED_PREFIX)
  }

  /**
   * Derive an AES key from the secret using PBKDF2.
   */
  private def deriveKey(secret: String, salt: Array[Byte]): SecretKeySpec = {
    val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
    val spec = new PBEKeySpec(secret.toCharArray, salt, ITERATIONS, KEY_LENGTH)
    val tmp = factory.generateSecret(spec)
    new SecretKeySpec(tmp.getEncoded, KEY_ALGORITHM)
  }
}
