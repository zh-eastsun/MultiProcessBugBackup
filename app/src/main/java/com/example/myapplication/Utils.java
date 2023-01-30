package com.example.myapplication;

import android.content.Context;

public class Utils {
    public static boolean isMainProcess(Context context) {
        String mainProcessName = context.getPackageName();
        String currentProcessName = ProcessUtils.getCurrentProcessName();

        if (currentProcessName == null){
            return false;
        }
        return mainProcessName.equals(currentProcessName);
    }
}
