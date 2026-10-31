package com.nikolay.myvpn

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val forceUpdate: Boolean
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null
    private var progressDialog: AlertDialog? = null
    private var updateInfoDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var downloadCancelled = false

    // Файл ожидающей установки для возобновления после выдачи разрешения неизвестных источников
    private var pendingInstallFile: File? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    fun checkUpdate(activity: Activity, silent: Boolean = true) {
        val db = FirebaseFirestore.getInstance()
        db.collection("app_update").document("android")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null && snapshot.exists()) {
                    val code = snapshot.getLong("latestVersionCode")?.toInt()
                        ?: snapshot.getLong("versionCode")?.toInt() ?: 0
                    val name = snapshot.getString("latestVersionName")
                        ?: snapshot.getString("versionName") ?: "2.1"
                    val url = snapshot.getString("apkUrl")
                        ?: "https://sub.pulsevpn.shop/downloads/PulseVPN-Android.apk"
                    val changelog = snapshot.getString("changelog") ?: "Доступна новая версия Pulse VPN."
                    val force = snapshot.getBoolean("forceUpdate") ?: false
                    handleUpdateInfo(activity, UpdateInfo(code, name, url, changelog, force), silent)
                } else {
                    checkFallbackApi(activity, silent)
                }
            }
            .addOnFailureListener {
                checkFallbackApi(activity, silent)
            }
    }

    /**
     * Вызывается из MainActivity.onResume() для продолжения установки,
     * если пользователь вернулся из настроек "Установка неизвестных приложений".
     */
    fun checkPendingInstall(activity: Activity) {
        val file = pendingInstallFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (activity.packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = null
                installApk(activity, file)
            }
        } else {
            pendingInstallFile = null
            installApk(activity, file)
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun checkFallbackApi(activity: Activity, silent: Boolean) {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://sub.pulsevpn.shop/api/update.json")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val info = UpdateInfo(
                            versionCode = if (json.has("latestVersionCode")) json.optInt("latestVersionCode", 0) else json.optInt("versionCode", 0),
                            versionName = if (json.has("latestVersionName")) json.optString("latestVersionName", "2.1") else json.optString("versionName", "2.1"),
                            apkUrl = json.optString(
                                "apkUrl",
                                "https://pulsevpn.shop/downloads/PulseVPN-Android.apk"
                            ),
                            changelog = json.optString(
                                "changelog",
                                "• Повышена стабильность соединения\n• Оптимизация работы приложения"
                            ),
                            forceUpdate = json.optBoolean("forceUpdate", false)
                        )
                        activity.runOnUiThread { handleUpdateInfo(activity, info, silent) }
                        return@Thread
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback update check failed: ${e.message}")
            }
            if (!silent) {
                activity.runOnUiThread {
                    ToastHelper.show(
                        activity,
                        "У вас установлена актуальная версия (${BuildConfig.VERSION_NAME})",
                        ToastHelper.Type.SUCCESS
                    )
                }
            }
        }.start()
    }

    private fun handleUpdateInfo(activity: Activity, info: UpdateInfo, silent: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return

        val currentCode = BuildConfig.VERSION_CODE
        if (info.versionCode > currentCode && info.apkUrl.isNotBlank()) {
            showUpdateInfoDialog(activity, info)
        } else if (!silent) {
            ToastHelper.show(
                activity,
                "У вас установлена последняя версия Pulse VPN (${BuildConfig.VERSION_NAME})",
                ToastHelper.Type.SUCCESS
            )
        }
    }

    // ─── Beautiful Update Info Dialog ─────────────────────────────────────────

    private fun showUpdateInfoDialog(activity: Activity, info: UpdateInfo) {
        if (updateInfoDialog?.isShowing == true) return

        val view = LayoutInflater.from(activity).inflate(R.layout.layout_dialog_update_info, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvUpdateDialogTitle)
        val tvNewVersion = view.findViewById<TextView>(R.id.tvNewVersionPill)
        val tvCurrentVersion = view.findViewById<TextView>(R.id.tvCurrentVersionText)
        val tvChangelog = view.findViewById<TextView>(R.id.tvChangelogContent)
        val btnDoUpdate = view.findViewById<Button>(R.id.btnDoUpdate)
        val btnLater = view.findViewById<TextView>(R.id.btnUpdateLater)

        tvTitle.text = if (info.forceUpdate) "Важное обновление" else "Доступно обновление"
        tvNewVersion.text = "v${info.versionName}"
        tvCurrentVersion.text = "Текущая: v${BuildConfig.VERSION_NAME}"
        tvChangelog.text = info.changelog.replace("\\n", "\n")

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(!info.forceUpdate)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDoUpdate.setOnClickListener {
            dialog.dismiss()
            startDownloadAndInstall(activity, info)
        }

        if (info.forceUpdate) {
            btnLater.visibility = View.GONE
        } else {
            btnLater.visibility = View.VISIBLE
            btnLater.setOnClickListener {
                dialog.dismiss()
            }
        }

        updateInfoDialog = dialog
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    // ─── High-Speed Streaming Download ────────────────────────────────────────

    fun startDownloadAndInstall(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        downloadCancelled = false

        val targetDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.cacheDir
        val targetFile = File(targetDir, "PulseVPN-v${info.versionName}.apk")

        showProgressDialog(activity, info.versionName) {
            downloadCancelled = true
            activeCall?.cancel()
            activeCall = null
            if (targetFile.exists()) targetFile.delete()
            ToastHelper.show(activity, "Загрузка отменена", ToastHelper.Type.INFO)
        }

        Thread {
            var output: FileOutputStream? = null
            try {
                if (targetFile.exists()) targetFile.delete()

                val request = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", "PulseVPN-Android/${BuildConfig.VERSION_NAME}")
                    .build()

                val call = httpClient.newCall(request)
                activeCall = call

                val response = call.execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty body")
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                output = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloadedBytes = 0L

                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L
                var currentSpeedStr = ""

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (downloadCancelled) {
                        try { output.close() } catch (e: Exception) {}
                        if (targetFile.exists()) targetFile.delete()
                        return@Thread
                    }

                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    bytesSinceLastCalc += bytesRead

                    val now = System.currentTimeMillis()
                    val delta = now - lastSpeedCalcTime
                    if (delta >= 300) {
                        val speedBps = (bytesSinceLastCalc * 1000.0) / delta
                        currentSpeedStr = formatSpeed(speedBps)
                        bytesSinceLastCalc = 0
                        lastSpeedCalcTime = now

                        val curDownloaded = downloadedBytes
                        activity.runOnUiThread {
                            updateProgressUI(curDownloaded, totalBytes, currentSpeedStr)
                        }
                    }
                }
                output.flush()

                if (!downloadCancelled) {
                    activity.runOnUiThread {
                        onDownloadFinished(activity, targetFile)
                    }
                }
            } catch (e: Exception) {
                if (!downloadCancelled) {
                    Log.e(TAG, "Download failed: ${e.message}", e)
                    activity.runOnUiThread {
                        progressDialog?.dismiss()
                        progressDialog = null
                        ToastHelper.show(
                            activity,
                            "Ошибка загрузки обновления: ${e.message}",
                            ToastHelper.Type.ERROR
                        )
                    }
                }
            } finally {
                try { output?.close() } catch (e: Exception) {}
                activeCall = null
            }
        }.start()
    }

    // ─── Progress Dialog UI ───────────────────────────────────────────────────

    private fun showProgressDialog(
        activity: Activity,
        targetVersion: String,
        onCancel: () -> Unit
    ) {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.layout_update_progress, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvStatus = view.findViewById<TextView>(R.id.tvUpdateStatus)
        val btnCancel = view.findViewById<TextView>(R.id.btnCancelUpdate)

        tvTitle.text = "Pulse VPN v$targetVersion"
        tvStatus.text = "Подключение..."
        btnCancel.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener {
            dialog.dismiss()
            progressDialog = null
            onCancel()
        }

        progressDialog = dialog
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    private fun updateProgressUI(downloaded: Long, total: Long, speedStr: String) {
        val dialog = progressDialog ?: return
        val root = dialog.window?.decorView ?: return

        val tvStatus = root.findViewById<TextView>(R.id.tvUpdateStatus) ?: return
        val tvPercent = root.findViewById<TextView>(R.id.tvProgressPercent) ?: return
        val tvSizeInfo = root.findViewById<TextView>(R.id.tvUpdateSizeInfo) ?: return
        val progressContainer = root.findViewById<View>(R.id.flProgressContainer) ?: return
        val progressFill = root.findViewById<View>(R.id.viewProgressFill) ?: return

        tvStatus.text = "Загрузка обновления..."

        if (total > 0) {
            val pct = ((downloaded.toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
            tvPercent.text = "$pct%"

            val sizeStr = "${formatSize(downloaded)} / ${formatSize(total)}"
            tvSizeInfo.text = if (speedStr.isNotBlank()) "$sizeStr · $speedStr" else sizeStr

            progressContainer.post {
                val containerW = progressContainer.width
                if (containerW > 0) {
                    val targetW = (containerW * pct) / 100
                    val currentW = progressFill.layoutParams.width.coerceAtLeast(0)
                    val delta = targetW - currentW
                    if (delta > 0) {
                        ValueAnimator.ofInt(currentW, targetW).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                val lp = progressFill.layoutParams
                                lp.width = anim.animatedValue as Int
                                progressFill.layoutParams = lp
                            }
                            start()
                        }
                    }
                }
            }
        } else {
            tvPercent.text = ""
            tvSizeInfo.text = formatSize(downloaded)
        }
    }

    private fun onDownloadFinished(activity: Activity, targetFile: File) {
        val dialog = progressDialog
        if (dialog != null) {
            val root = dialog.window?.decorView
            root?.let { v ->
                v.findViewById<TextView>(R.id.tvUpdateStatus)?.text = "Подготовка к установке..."
                v.findViewById<TextView>(R.id.tvProgressPercent)?.text = "100%"
                val container = v.findViewById<View>(R.id.flProgressContainer)
                val fill = v.findViewById<View>(R.id.viewProgressFill)
                if (container != null && fill != null) {
                    container.post {
                        val lp = fill.layoutParams
                        lp.width = container.width
                        fill.layoutParams = lp
                    }
                }
            }
        }

        handler.postDelayed({
            progressDialog?.dismiss()
            progressDialog = null
            installApk(activity, targetFile)
        }, 500)
    }

    // ─── Install APK ──────────────────────────────────────────────────────────

    fun installApk(activity: Activity, file: File) {
        if (!file.exists()) {
            ToastHelper.show(activity, "Файл обновления не найден", ToastHelper.Type.ERROR)
            return
        }

        // Проверка разрешения на установку из неизвестных источников для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = file
                GlassDialog.show(
                    context = activity,
                    title = "Разрешение на установку",
                    message = "Для установки обновления требуется разрешить установку приложений из этого источника.",
                    iconRes = R.drawable.ic_bolt,
                    positiveText = "Настройки",
                    negativeText = "Отмена",
                    onPositiveClick = {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${activity.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open unknown sources settings: ${e.message}")
                        }
                    }
                )
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install launch failed: ${e.message}", e)
            ToastHelper.show(
                activity,
                "Не удалось запустить установку: ${e.message}",
                ToastHelper.Type.ERROR
            )
        }
    }

    // ─── Format Utils ─────────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec < 1024 -> String.format("%.0f B/s", bytesPerSec)
            bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }
}
