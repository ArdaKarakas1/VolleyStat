package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import org.json.JSONObject
import java.io.File

/**
 * device.json (design 10.3): what the phone remembers whoever is signed in. It holds no personal
 * data and no secret, so it is plain JSON.
 *
 * Before its first save the store reads the settings of the single file app 0.7 kept
 * ([legacy]), so an update neither shows the welcome screen again nor forgets the server choice.
 */
class DeviceStore(private val file: File, private val legacy: File) {

    fun load(): Settings = try {
        when {
            file.isFile -> decode(JSONObject(file.readText()))
            legacy.isFile -> JSONObject(legacy.readText()).optJSONObject("settings")?.let { old ->
                Settings(serverTarget = target(old.optString("serverTarget")), welcomeSeen = old.optBoolean("welcomeSeen", false))
            } ?: Settings()
            else -> Settings()
        }
    } catch (error: Exception) {
        Settings()
    }

    @Synchronized
    fun save(settings: Settings) {
        val json = JSONObject().put("version", 1).put("serverTarget", settings.serverTarget.name).put("welcomeSeen", settings.welcomeSeen)
            .put("accountSeen", settings.accountSeen).put("legacyHandled", settings.legacyHandled)
        replaceAtomically(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decode(json: JSONObject) = Settings(
        serverTarget = target(json.optString("serverTarget")),
        welcomeSeen = json.optBoolean("welcomeSeen", false),
        accountSeen = json.optBoolean("accountSeen", false),
        legacyHandled = json.optBoolean("legacyHandled", false),
    )

    private fun target(name: String) = runCatching { ServerTarget.valueOf(name) }.getOrDefault(ServerTarget.EMULATOR)
}
