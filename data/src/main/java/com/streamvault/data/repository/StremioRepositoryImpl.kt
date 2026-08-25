package com.streamvault.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.remote.stremio.StremioProvider
import com.streamvault.domain.model.Result
import com.streamvault.domain.stremio.StremioManifest
import com.streamvault.domain.stremio.StremioRepository
import com.streamvault.domain.stremio.StremioStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.stremioDataStore by preferencesDataStore(name = "stremio_preferences")

data class InstalledStremioAddon(
    val manifestUrl: String,
    val manifest: StremioManifest
)

@Singleton
class StremioRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stremioProvider: StremioProvider,
    private val gson: Gson
) : StremioRepository {

    private companion object {
        val KEY_ADDONS_JSON = stringPreferencesKey("installed_addons_json")
    }

    private val addonsListType = object : TypeToken<List<InstalledStremioAddon>>() {}.type

    override fun getInstalledAddons(): Flow<List<StremioManifest>> {
        return context.stremioDataStore.data.map { prefs ->
            val json = prefs[KEY_ADDONS_JSON]
            if (json.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    val list = gson.fromJson<List<InstalledStremioAddon>>(json, addonsListType) ?: emptyList()
                    list.map { it.manifest }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    override suspend fun installAddon(manifestUrl: String): Result<StremioManifest> = withContext(Dispatchers.IO) {
        val trimmed = manifestUrl.trim()
        val url = if (trimmed.endsWith("/manifest.json")) trimmed else "${trimmed.trimEnd('/')}/manifest.json"

        when (val manifestResult = stremioProvider.fetchManifest(url)) {
            is Result.Success -> {
                val manifest = manifestResult.data
                val installed = loadInstalledAddons().toMutableList()
                installed.removeAll { it.manifest.id == manifest.id }
                installed.add(InstalledStremioAddon(manifestUrl = url, manifest = manifest))

                context.stremioDataStore.edit { prefs ->
                    prefs[KEY_ADDONS_JSON] = gson.toJson(installed)
                }
                Result.success(manifest)
            }
            is Result.Error -> Result.error(manifestResult.message, manifestResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun uninstallAddon(addonId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val installed = loadInstalledAddons().toMutableList()
            installed.removeAll { it.manifest.id == addonId }
            context.stremioDataStore.edit { prefs ->
                prefs[KEY_ADDONS_JSON] = gson.toJson(installed)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to uninstall add-on: ${e.message}", e)
        }
    }

    override suspend fun getStreamCandidates(type: String, id: String): Result<List<StremioStream>> = withContext(Dispatchers.IO) {
        val installed = loadInstalledAddons()
        val allStreams = mutableListOf<StremioStream>()

        for (addon in installed) {
            if (addon.manifest.resources.contains("stream") || addon.manifest.resources.isEmpty()) {
                val result = stremioProvider.fetchStreams(addon.manifestUrl, type, id)
                if (result is Result.Success) {
                    allStreams.addAll(result.data)
                }
            }
        }

        Result.success(allStreams)
    }

    private suspend fun loadInstalledAddons(): List<InstalledStremioAddon> {
        val prefs = context.stremioDataStore.data.first()
        val json = prefs[KEY_ADDONS_JSON]
        return if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                gson.fromJson<List<InstalledStremioAddon>>(json, addonsListType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
