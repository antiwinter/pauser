package com.github.catvod.crawler;

import android.util.Log;

public class SpiderDebug {
    public static void log(String msg) {
        Log.d("SpiderDebug", msg != null ? msg : "null");
    }
    public static void log(Throwable t) {
        Log.d("SpiderDebug", t != null ? t.toString() : "null");
    }
}
