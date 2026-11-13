package dev.horizon.android.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Singleton JNI bridge to the pure Rust backend (libhorizon_android.so).
 */
object BlazeNativeBridge {
    init {
        try {
            System.loadLibrary("horizon_android")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("BlazeNativeBridge: failed to load horizon_android: ${e.message}")
        }
    }

    private var initialized = false

    fun initIfNeeded(context: Context) {
        ensureEngineData(context)
        if (!initialized) {
            try {
                nativeInit(context.filesDir.absolutePath)
                initialized = true
            } catch (e: Throwable) {
                System.err.println("BlazeNativeBridge init error: ${e.message}")
            }
        }
    }

    /**
     * Unpack engine-data.zip from assets into internal filesDir if not already present.
     * Required by official_install.rs for multi-threaded asset stream extraction.
     */
    fun ensureEngineData(context: Context) {
        try {
            val dest = java.io.File(context.filesDir, "engine-data.zip")
            if (!dest.exists() || dest.length() == 0L) {
                val partial = java.io.File(context.filesDir, "engine-data.partial")
                context.assets.open("engine-data.zip").use { input ->
                    java.io.FileOutputStream(partial).use { output ->
                        input.copyTo(output)
                    }
                }
                partial.renameTo(dest)
            }
        } catch (e: Throwable) {
            System.err.println("BlazeNativeBridge: engine-data.zip unpack notice: ${e.message}")
        }
    }

    /**
     * Compute deterministic offline UUID matching target model parity (Steve = 0, Alex = 1).
     * Parity matches vanilla Java Edition offline player rendering logic.
     */
    fun getLocalUUIDWithSkinModel(userName: String, isSlim: Boolean): String {
        val targetParity = if (isSlim) 1 else 0
        val lenHex = userName.length.toString(16).padStart(16, '0')
        val hash = (userName.hashCode().toLong() and 0xFFFFFFFFL).toString(16).padStart(16, '0')
        val baseUuid = "${lenHex.take(12)}3${lenHex.substring(13, 16)}9${hash.take(15)}"
        val prefix = baseUuid.take(27)
        val a = baseUuid[7].digitToInt(16)
        val b = baseUuid[15].digitToInt(16)
        val c = baseUuid[23].digitToInt(16)
        var suffix = baseUuid.substring(27).toLong(16)
        val maxSuffix = 0xFFFFFL
        for (i in 0..maxSuffix) {
            val currentD = (suffix and 0xFL).toInt()
            if ((a xor b xor c xor currentD) % 2 == targetParity) {
                return prefix + suffix.toString(16).padStart(5, '0').uppercase()
            }
            suffix = if (suffix == maxSuffix) 0L else suffix + 1L
        }
        return prefix + suffix.toString(16).padStart(5, '0').uppercase()
    }

    // Native JNI functions implemented in crates/horizon-client/src/android/jni_bridge.rs
    private external fun nativeInit(dataDir: String)
    external fun nativeAreAssetsReady(): Boolean
    external fun nativeGetConfigJson(): String
    external fun nativeSaveConfigJson(json: String): Boolean
    external fun nativeLaunchGame(profileId: Int): Boolean
    external fun nativeStartMultiThreadDownload(threads: Int): Boolean
    external fun nativeGetDownloadProgressJson(): String
    external fun nativeSetKeepAliveEnabled(enabled: Boolean): Boolean
    external fun nativeSetComplexVoiceChatConfig(enabled: Boolean, rnnoise: Boolean): Boolean
    external fun nativeFetchCatalogMods(source: String, category: String, query: String): String
    external fun nativeGetControlLayoutJson(): String
    external fun nativeSaveControlLayoutJson(json: String): Boolean
    external fun nativeExportWorlds(): Boolean
    external fun nativeGetDiagnosticsJson(): String
    external fun nativeGetVersion(): String
    external fun nativeResetSettings(category: String): Boolean
    external fun nativeSignalEngineExit(): Boolean

    fun signalEngineExit(): Boolean {
        return try {
            nativeSignalEngineExit()
        } catch (_: Throwable) {
            false
        }
    }

    // Account Management JNI
    external fun nativeGetAccountsJson(): String
    external fun nativeSelectAccount(provider: String, idOrName: String): Boolean
    external fun nativeAddOfflineAccount(name: String): Boolean
    external fun nativeAddElyByAccount(username: String, password: String, otp: String): String
    external fun nativeStartMicrosoftDeviceLogin(): String
    external fun nativePollMicrosoftDeviceLogin(): String
    external fun nativeDeleteAccount(provider: String, idOrName: String): Boolean
    external fun nativeGetActiveAccessToken(): String

    // Safe helper accessors
    fun getActiveAccessToken(): String {
        return try {
            nativeGetActiveAccessToken()
        } catch (_: Throwable) {
            ""
        }
    }

    fun getActiveAccountUUID(): String {
        return try {
            val json = JSONObject(nativeGetAccountsJson())
            json.optString("active_uuid", "")
        } catch (_: Throwable) {
            ""
        }
    }

    fun getAccounts(): List<BlazeAccount> {
        return try {
            val json = JSONObject(nativeGetAccountsJson())
            val arr = json.optJSONArray("accounts") ?: return emptyList()
            val list = mutableListOf<BlazeAccount>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(
                    BlazeAccount(
                        provider = item.optString("provider", "offline"),
                        uuid = item.optString("uuid", ""),
                        name = item.optString("name", "Player"),
                        key = item.optString("key", ""),
                        isActive = item.optBoolean("is_active", false),
                        skinUrl = item.optString("skin_url", null),
                        skinModel = item.optString("skin_model", null),
                        capeUrl = item.optString("cape_url", null)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getOfflineAccounts(): List<String> {
        return try {
            val json = JSONObject(nativeGetAccountsJson())
            val arr = json.optJSONArray("offline") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getActiveAccountName(): String {
        return try {
            val json = JSONObject(nativeGetAccountsJson())
            json.optString("active_name", "Player")
        } catch (_: Throwable) {
            "Player"
        }
    }

    fun getActiveAccountType(): String {
        return try {
            val json = JSONObject(nativeGetAccountsJson())
            json.optString("active_type", "offline")
        } catch (_: Throwable) {
            "offline"
        }
    }
    fun areAssetsReady(): Boolean {
        return try {
            nativeAreAssetsReady()
        } catch (_: Throwable) {
            false
        }
    }

    fun getVersion(): String {
        return try {
            nativeGetVersion().ifEmpty { "26.3 • Blaze Engine" }
        } catch (_: Throwable) {
            "26.3 • Blaze Engine"
        }
    }

    fun isKeepAliveEnabled(): Boolean {
        return try {
            val json = JSONObject(nativeGetConfigJson())
            json.optBoolean("keepalive_enabled", true)
        } catch (_: Throwable) {
            true
        }
    }

    fun isComplexVoiceChatEnabled(): Boolean {
        return try {
            val json = JSONObject(nativeGetConfigJson())
            json.optBoolean("svc_enabled", true)
        } catch (_: Throwable) {
            true
        }
    }

    fun isRNNoiseEnabled(): Boolean {
        return try {
            val json = JSONObject(nativeGetConfigJson())
            json.optBoolean("svc_rnnoise", true)
        } catch (_: Throwable) {
            true
        }
    }
}

data class BlazeAccount(
    val provider: String,
    val uuid: String,
    val name: String,
    val key: String,
    val isActive: Boolean,
    val skinUrl: String? = null,
    val skinModel: String? = null,
    val capeUrl: String? = null
)

data class AssetComponent(
    val name: String,
    val desc: String,
    val isReady: Boolean,
    val status: String
)

data class DownloadThread(
    val id: Int,
    val name: String,
    val progress: Float,
    val speedMibS: Float,
    val status: String
)

data class ControlButton(
    val id: String,
    val label: String,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var opacity: Float,
    var scale: Float,
    var cameraPassthrough: Boolean
)

data class CatalogItem(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val downloads: Long,
    val installed: Boolean,
    val unsupported: Boolean,
    val iconUrl: String? = null
)
