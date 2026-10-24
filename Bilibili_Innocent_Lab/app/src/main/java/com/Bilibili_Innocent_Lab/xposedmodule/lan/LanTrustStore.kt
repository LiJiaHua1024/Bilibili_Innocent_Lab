package com.Bilibili_Innocent_Lab.xposedmodule.lan

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
import org.json.JSONArray
import org.json.JSONObject

internal data class TrustedLanDevice(val id: String, val name: String, val key: ByteArray, val pairedAt: Long)

/** Keystore-wrapped, app-private and excluded from backup. Corruption fails closed. */
internal class LanTrustStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "lan-trust-v1.bin"))
    private val vault: SecretKey by lazy {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    private var local = LanCrypto.randomId()
    private val peers = linkedMapOf<String, TrustedLanDevice>()
    init {
        if (file.baseFile.exists()) {
            val bytes = file.openRead().use { it.readBytesBounded(65_536) }
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, vault, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val root = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
            local = root.getString("id").also { require(it.matches(Regex("[A-Za-z0-9_-]{22}"))) }
            val values = root.getJSONArray("peers")
            require(values.length() <= 32)
            repeat(values.length()) {
                val peer = values.getJSONObject(it)
                val id = peer.getString("id").also { value -> require(value.matches(Regex("[A-Za-z0-9_-]{22}"))) }
                val key = LanCrypto.decode(peer.getString("key")).also { value -> require(value.size == 32) }
                peers[id] = TrustedLanDevice(id, peer.getString("name").take(80), key, peer.getLong("at"))
            }
        } else save()
    }
    val id get() = local
    @Synchronized fun list(): List<TrustedLanDevice> = peers.values.toList()
    @Synchronized fun get(id: String): TrustedLanDevice? = peers[id]
    @Synchronized fun put(device: TrustedLanDevice) {
        require(device.id != local && (peers.size < 32 || device.id in peers))
        val previous = peers.put(device.id, device)
        try { save() } catch (error: Exception) { if (previous == null) peers.remove(device.id) else peers[device.id] = previous; throw error }
    }
    @Synchronized fun remove(id: String) {
        val previous = peers.remove(id) ?: return
        try { save() } catch (error: Exception) { peers[id] = previous; throw error }
    }
    private fun save() {
        val root = JSONObject().put("id", local).put("peers", JSONArray().apply {
            peers.values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("key", LanCrypto.encode(it.key)).put("at", it.pairedAt)) }
        })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vault)
        val bytes = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private const val ALIAS = "bil-lan-trust-v1"
        fun hasSavedBindings(context: Context): Boolean {
            if (!File(context.noBackupFilesDir, "lan-trust-v1.bin").isFile) return false
            return runCatching { LanTrustStore(context).list().isNotEmpty() }.getOrDefault(false)
        }
    }
}
