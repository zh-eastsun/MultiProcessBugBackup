package com.example.myapplication

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.io.RandomAccessFile

object WebViewUtil {

    private val TAG = "zdy debug"//WebViewUtil::class.java.simpleName

    @JvmStatic
    fun handleWebViewDir(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        try {
            var suffix = ""
            val processName = ProcessUtils.getCurrentProcessName()
            if (!TextUtils.equals(context.packageName, processName)) { //判断不等于默认进程名称
                suffix = if (TextUtils.isEmpty(processName)) context.packageName else processName
                WebView.setDataDirectorySuffix(suffix)
                suffix = "_$suffix"
            }
            tryLockOrRecreateFile(context, suffix)
        } catch (e: Exception) {
            Log.e(TAG, "handleWebViewDir " + e.message)
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun tryLockOrRecreateFile(context: Context, suffix: String) {
        val sb = context.dataDir.absolutePath + "/app_webview" + suffix + "/webview_data.lock"
        val file = File(sb)
        if (file.exists()) {
            try {
                val tryLock = RandomAccessFile(file, "rw").channel.tryLock()
                if (tryLock != null) {
                    tryLock.close()
                } else {
                    createFile(file, file.delete())
                }
            } catch (e: Exception) {
                Log.e(TAG, "tryLockOrRecreateFile " + e.message)
                var deleted = false
                if (file.exists()) {
                    deleted = file.delete()
                }
                createFile(file, deleted)
            }
        }
    }

    private fun createFile(file: File, deleted: Boolean) {
        try {
            if (deleted && !file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "createFile " + e.message)
        }
    }
}