package com.example.myapplication

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

class MyApplication: Application() {

    private val TAG = "zdy debug"//MyApplication::class.java.name

    companion object {
        @JvmStatic
        @Volatile
        lateinit var mApplicationContext: Context
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Log.e(TAG, "attachBaseContext${android.os.Process.myPid()}")
        if (base != null) {
            mApplicationContext = base
        }
//        if (Utils.isMainProcess(base)) {
//            clearWebViewLockIfNeed(base)
//        }
    }

    private fun clearWebViewLockIfNeed(context: Context?) {
        val lockFile = File(
            context!!.getDir(
                "webview", MODE_PRIVATE
            ).path, "webview_data.lock"
        )
        if (lockFile.exists()) {
            try {
                RandomAccessFile(lockFile, "rw").use { lockAccessFile ->
                    lockAccessFile.getChannel().tryLock().use { ignored ->
                        Log.d(
                            TAG,
                            "fileRelease"
                        )
                    }
                }
            } catch (e: OverlappingFileLockException) {
                e.printStackTrace()
                val delete: Boolean = lockFile.delete()
                Log.d(TAG, "filedelete$delete")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val isMainProcess: Boolean =
            Utils.isMainProcess(mApplicationContext)
        if (isMainProcess) {
            Log.e(TAG, "is main process..")
        } else {
            Log.e(TAG, "is not main process..")
            WebViewUtil.handleWebViewDir(mApplicationContext)
        }
    }

}