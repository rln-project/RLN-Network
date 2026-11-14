/**
 * RLN Network updater. APKs come only from this project's GitHub releases.
 * Android's package installer always asks the user for approval.
 */
package com.metrolist.music.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.metrolist.music.BuildConfig
import java.io.File

object RlnUpdateInstaller {
    private const val PREFS_NAME = "rln_update_download"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_PERMISSION_SHOWN = "permission_shown"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val DOWNLOAD_DIRECTORY = "RLNUpdates"
    private val supportedNames = setOf("RLN-Network.apk", "RLN-Network-no-cast.apk")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun manager(context: Context) =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Do not download arbitrary URLs suggested by a release description. */
    internal fun isTrustedAsset(url: String): Boolean {
        val uri = Uri.parse(url)
        val parts = uri.pathSegments
        return uri.scheme == "https" &&
            uri.host == "github.com" &&
            uri.encodedQuery == null &&
            parts.size == 6 &&
            parts[0] == "sinisgallof-pixel" &&
            parts[1] == "RLN-Network" &&
            parts[2] == "releases" &&
            parts[3] == "download" &&
            parts[4].isNotBlank() &&
            parts[5] in supportedNames
    }

    fun startDownload(context: Context, url: String) {
        try {
            require(isTrustedAsset(url)) { "Indirizzo dell'aggiornamento non valido" }
            val appContext = context.applicationContext
            val prefs = preferences(appContext)
            val previousId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
            if (previousId >= 0) {
                when (downloadStatus(appContext, previousId)) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        message(appContext, "Download dell'aggiornamento già in corso")
                        return
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().putBoolean(KEY_PERMISSION_SHOWN, false).apply()
                        checkPendingDownload(appContext)
                        return
                    }
                }
            }

            val apkName = Uri.parse(url).lastPathSegment!!
            val downloadDir = updateDirectory(appContext)
            val target = File(downloadDir, apkName)
            if (target.exists() && !target.delete()) {
                error("Impossibile sostituire il vecchio file APK")
            }

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Aggiornamento RLN Network")
                .setDescription("Download della nuova versione")
                .setMimeType(MIME_APK)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target))

            val id = manager(appContext).enqueue(request)
            prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_APK_NAME, apkName)
                .putBoolean(KEY_PERMISSION_SHOWN, false)
                .apply()
            message(appContext, "Download avviato: resta nell'app per installare appena pronto")
        } catch (e: Exception) {
            message(context, "Aggiornamento non avviato: ${e.localizedMessage ?: "errore"}")
        }
    }

    /** Called when the app is opened or receives the system download-complete event. */
    fun checkPendingDownload(context: Context, completedId: Long? = null) {
        val appContext = context.applicationContext
        val prefs = preferences(appContext)
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0L || (completedId != null && completedId != id)) return
        when (downloadStatus(appContext, id)) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                try {
                    val filename = prefs.getString(KEY_APK_NAME, null)
                    require(filename in supportedNames) { "Nome dell'APK non valido" }
                    val apk = File(updateDirectory(appContext), filename!!)
                    require(apk.isFile && apk.length() > 0L) { "APK scaricato non valido" }

                    val pm = appContext.packageManager
                    @Suppress("DEPRECATION")
                    val packageInfo = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        pm.getPackageArchiveInfo(apk.absolutePath, 0)
                    }) ?: error("APK non leggibile")
                    require(packageInfo.packageName == appContext.packageName) {
                        "Il pacchetto non appartiene a RLN Network"
                    }
                    require(packageInfo.longVersionCode > BuildConfig.VERSION_CODE.toLong()) {
                        "La versione scaricata non è più recente"
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !pm.canRequestPackageInstalls()
                    ) {
                        if (!prefs.getBoolean(KEY_PERMISSION_SHOWN, false)) {
                            prefs.edit().putBoolean(KEY_PERMISSION_SHOWN, true).apply()
                            val settingsIntent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${appContext.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            message(appContext, "Consenti l'installazione da RLN Network e torna nell'app")
                            appContext.startActivity(settingsIntent)
                        }
                        return
                    }

                    // Clear before launching the installer so returning from it does not loop.
                    clearPending(prefs)
                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${BuildConfig.APPLICATION_ID}.FileProvider",
                        apk
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, MIME_APK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(installIntent)
                } catch (e: Exception) {
                    clearPending(prefs)
                    message(appContext, "Installazione non avviata: ${e.localizedMessage ?: "errore"}")
                }
            }
            DownloadManager.STATUS_FAILED -> {
                clearPending(prefs)
                message(appContext, "Download non riuscito. Riprova dalle impostazioni")
            }
        }
    }

    private fun updateDirectory(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Memoria non disponibile")
        val directory = File(external, DOWNLOAD_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) {
            "Impossibile creare la cartella aggiornamenti"
        }
        return directory
    }

    private fun downloadStatus(context: Context, id: Long): Int? {
        manager(context).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    private fun clearPending(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_APK_NAME)
            .remove(KEY_PERMISSION_SHOWN)
            .apply()
    }

    private fun message(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
