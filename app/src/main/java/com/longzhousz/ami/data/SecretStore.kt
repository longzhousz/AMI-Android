package com.longzhousz.ami.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The ciphertext is excluded from backup; the AES key never leaves Android Keystore. */
class SecretStore(context: Context, name: String = "deepseek") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private val alias = "ami.$name.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(value: String) {
        if (value.isBlank()) { file.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(cipher.doFinal(value.trim().toByteArray()))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        return file.openRead().use {
            val length = it.read()
            require(length in 12..16) { "密钥存储损坏，请重新保存" }
            val iv = ByteArray(length); require(it.read(iv) == length)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); doFinal(it.readBytes()).toString(Charsets.UTF_8)
            }
        }
    }
    fun configured() = file.baseFile.exists()
}
