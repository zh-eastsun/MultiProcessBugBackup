# 多进程调用WebView崩溃问题记录

[TOC]

## 一、backup

### 1.1 异常信息

```SQL
Fatal Exception: java.lang.RuntimeException: Using WebView from more than one process at once with the same data directory is not supported. https://crbug.com/558377 : Current process com.mi.android.globalFileexplorer (pid 13862), lock owner com.mi.android.globalFileexplorer (pid 13559)
       at org.chromium.android_webview.AwDataDirLock.b(AwDataDirLock.java:27)
       at as0.i(as0.java:30)
       at Zr0.run(Zr0.java:2)
       at android.os.Handler.handleCallback(Handler.java:883)
       at android.os.Handler.dispatchMessage(Handler.java:100)
       at android.os.Looper.loop(Looper.java:224)
       at android.app.ActivityThread.main(ActivityThread.java:7520)
       at java.lang.reflect.Method.invoke(Method.java)
       at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:539)
       at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:950)
```

### 1.2 解决方案

#### 1.2.1 官方提供方案

```C++
protected void attachBaseContext(Context base) {
    mApplicationContext = base;
    webViewSetPath(this);
}  


public void webViewSetPath(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        String processName = SpecialUtils.getCurProcessName(context);
        if(!CommonConstant.NEW_PACKAGE_NAME.equals(processName)){
            WebView.setDataDirectorySuffix(getString(processName,"global_file_manager"));
        }
    }
}

public String getString(String processName, String defValue) {
    return TextUtils.isEmpty(processName) ? defValue : processName;
}
```

遇到的坑：实际在项目中运用 application中设置多个存储目录，虽然能减少问题发生的次数，但是我们在firebase上依然能发现很多这个同样的崩溃信息

#### 1.2.2 问题分析

那么这个问题发生的原因究竟是什么？一起来分析下抛出这个异常的逻辑吧

https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwDataDirLock.java#126

```Java
// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package org.chromium.android_webview;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.PathUtils;
import org.chromium.base.StrictModeContext;
import org.chromium.base.metrics.ScopedSysTraceEvent;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Handles locking the WebView's data directory, to prevent concurrent use from
 * more than one process.
 */
abstract class AwDataDirLock {
    private static final String TAG = "AwDataDirLock";
    private static final String EXCLUSIVE_LOCK_FILE = "webview_data.lock";
    // This results in a maximum wait time of 1.5s
    private static final int LOCK_RETRIES = 16;
    private static final int LOCK_SLEEP_MS = 100;
    private static RandomAccessFile sLockFile;
    private static FileLock sExclusiveFileLock;

    static void lock(final Context appContext) {
        try (ScopedSysTraceEvent e1 = ScopedSysTraceEvent.scoped("AwDataDirLock.lock");
             StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
            if (sExclusiveFileLock != null) {
                // We have already called lock() and successfully acquired the lock in this process.
                // This shouldn't happen, but is likely to be the result of an app catching an
                // exception thrown during initialization and discarding it, causing us to later
                // attempt to initialize WebView again. There's no real advantage to failing the
                // locking code when this happens; we may as well count this as the lock being
                // acquired and let init continue (though the app may experience other problems
                // later).
                return;
            }
            // If we already called lock() but didn't succeed in getting the lock, it's possible the
            // app caught the exception and tried again later. As above, there's no real advantage
            // to failing here, so only open the lock file if we didn't already open it before.
            if (sLockFile == null) {
                String dataPath = PathUtils.getDataDirectory();
                File lockFile = new File(dataPath, EXCLUSIVE_LOCK_FILE);
                try {
                    // Note that the file is kept open intentionally.
                    sLockFile = new RandomAccessFile(lockFile, "rw");
                } catch (IOException e) {
                    // Failing to create the lock file is always fatal; even if multiple processes
                    // are using the same data directory we should always be able to access the file
                    // itself.
                    throw new RuntimeException("Failed to create lock file " + lockFile, e);
                }
            }
            // Android versions before 11 have edge cases where a new instance of an app process can
            // be started while an existing one is still in the process of being killed. This can
            // still happen on Android 11+ because the platform has a timeout for waiting, but it's
            // much less likely. Retry the lock a few times to give the old process time to fully go
            // away.
            for (int attempts = 1; attempts <= LOCK_RETRIES; ++attempts) {
                try {
                    sExclusiveFileLock = sLockFile.getChannel().tryLock();
                } catch (IOException e) {
                    // Older versions of Android incorrectly throw IOException when the flock()
                    // call fails with EAGAIN, instead of returning null. Just ignore it.
                }
                if (sExclusiveFileLock != null) {
                    // We got the lock; write out info for debugging.
                    writeCurrentProcessInfo(sLockFile);
                    return;
                }
                // If we're not out of retries, sleep and try again.
                if (attempts == LOCK_RETRIES) break;
                try {
                    Thread.sleep(LOCK_SLEEP_MS);
                } catch (InterruptedException e) {
                }
            }
            // We failed to get the lock even after retrying.
            // Many existing apps rely on this even though it's known to be unsafe.
            // Make it fatal when on P for apps that target P or higher
            String error = getLockFailureReason(sLockFile);
            boolean dieOnFailure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && appContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P;
            if (dieOnFailure) {
                throw new RuntimeException(error);
            } else {
                Log.w(TAG, error);
            }
        }
    }

    private static void writeCurrentProcessInfo(final RandomAccessFile file) {
        try {
            // Truncate the file first to get rid of old data.
            file.setLength(0);
            file.writeInt(Process.myPid());
            file.writeUTF(ContextUtils.getProcessName());
        } catch (IOException e) {
            // Don't crash just because something failed here, as it's only for debugging.
            Log.w(TAG, "Failed to write info to lock file", e);
        }
    }

    private static String getLockFailureReason(final RandomAccessFile file) {
        final StringBuilder error = new StringBuilder("Using WebView from more than one process at "
                + "once with the same data directory is not supported. https://crbug.com/558377 "
                + ": Current process ");
        error.append(ContextUtils.getProcessName());
        error.append(" (pid ").append(Process.myPid()).append("), lock owner ");
        try {
            int pid = file.readInt();
            String processName = file.readUTF();
            error.append(processName).append(" (pid ").append(pid).append(")");
            ...
            ...
        } catch (IOException e) {
           error.append(" unknown");
        }
        return error.toString();
    }
}
```

- 判断原理：进程是否持有WebView数据目录中的webview_data.lock文件的锁，如果子进程也对相同文件尝试加速，则会crash

- lock方法会对webview数据目录中的webview_data.lock文件在for循环中尝试加锁16次

- 如果加锁成功会将该进程id和进程名写入到文件，如果加锁失败则会抛出异常

#### 1.2.3 最佳解决方案 

通过检查目标目录的文件锁，如果能够获得到锁，就表明无异常；如果获取不到文件锁，再次重新设置存储目录。

```TypeScript
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.List;

public class WebViewUtil {

    public static void handleWebViewDir(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            String suffix = "";
            String processName = getCurProcessName(context);
            if (!TextUtils.equals(context.getPackageName(), processName)) {//判断不等于默认进程名称
                suffix = TextUtils.isEmpty(processName) ? context.getPackageName() : processName;
                WebView.setDataDirectorySuffix(suffix);
                suffix = "_" + suffix;
            }
            tryLockOrRecreateFile(context,suffix);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void tryLockOrRecreateFile(Context context, String suffix) {
        String sb = context.getDataDir().getAbsolutePath() +
                "/app_webview"+suffix+"/webview_data.lock";
        File file = new File(sb);
        if (file.exists()) {
            try {
                FileLock tryLock = new RandomAccessFile(file, "rw").getChannel().tryLock();
                if (tryLock != null) {
                    tryLock.close();
                } else {
                    createFile(file, file.delete());
                }
            } catch (Exception e) {
                e.printStackTrace();
                boolean deleted = false;
                if (file.exists()) {
                    deleted = file.delete();
                }
                createFile(file, deleted);
            }
        }
    }

    private static void createFile(File file, boolean deleted){
        try {
            if (deleted && !file.exists()) {
                file.createNewFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getCurProcessName(Context context) {
        int pid = android.os.Process.myPid();
        ActivityManager activityManager = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses = activityManager
                .getRunningAppProcesses();
        if (appProcesses == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess == null) {
                continue;
            }
            if (appProcess.pid == pid) {
                return appProcess.processName;
            }
        }
        return null;
    }

}
```

## 二、所做尝试

### 2.1 复现问题

* 新建两个Activity并加载WebView
* 设置两个Activity处于不同进程
* MainActivity启动TestActivity

结论：可以复现该问题，说明在多进程场景下确实存在这个崩溃。

### 2.2 尝试解决方案

* 使用加锁时文件后缀的方案尝试解决，也就是文档1.2.3中所述解决方案，经测试可以解决该问题。

​		结论：该问题并不是锁屏画报内部代码造成的崩溃，可能是其他地方还有多处WebView的调用。

* 浏览器内核组给出的方案：组件启动模式修改为SingleInstance模式。

  结论：该方案会影响组件调用栈，在某些特定场景的跳转和组件复用中不适用，并且侵入太严重，因此并未采用。

> 原因总结
>
> 两个不同进程的组件同时加载WebView，也有可能是应用被杀死后kill信号延迟导致在应用第二次被拉起时上一个进程并未被杀死引起的。

### 

## 三、解决思路