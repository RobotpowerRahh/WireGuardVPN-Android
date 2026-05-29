package com.barsam.wireguardvpn.services

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.barsam.wireguardvpn.models.VPNProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProfileStore private constructor(private val context: Context) {

    var profiles: List<VPNProfile> = emptyList()
        private set

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "vpn_profiles_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        @Volatile
        private var instance: ProfileStore? = null

        fun get(context: Context): ProfileStore {
            return instance ?: synchronized(this) {
                instance ?: ProfileStore(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        load()
    }

    fun add(profile: VPNProfile) {
        profiles = profiles + profile
        save()
    }

    fun update(profile: VPNProfile) {
        profiles = profiles.map { if (it.id == profile.id) profile else it }
        save()
    }

    fun delete(profile: VPNProfile) {
        profiles = profiles.filter { it.id != profile.id }
        save()
    }

    fun getById(id: String): VPNProfile? = profiles.find { it.id == id }

    fun first(): VPNProfile? = profiles.firstOrNull()

    private fun save() {
        prefs.edit().putString("profiles_json", json.encodeToString(profiles)).apply()
    }

    private fun load() {
        // Migrate from old plaintext file if it exists
        val legacyFile = File(context.filesDir, "profiles.json")
        if (legacyFile.exists()) {
            try {
                val legacyData = legacyFile.readText()
                val parsed = json.decodeFromString<List<VPNProfile>>(legacyData)
                profiles = parsed
                save()
                legacyFile.delete()
                return
            } catch (_: Exception) {
                // Fall through to normal load
            }
        }

        val data = prefs.getString("profiles_json", null)
        if (data != null) {
            runCatching {
                profiles = json.decodeFromString<List<VPNProfile>>(data)
            }
        }
    }
}
