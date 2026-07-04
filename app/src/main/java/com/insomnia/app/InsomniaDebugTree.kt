package com.insomnia.app

import android.util.Log
import timber.log.Timber

class InsomniaDebugTree : Timber.DebugTree() {

    private val currentPriority = ThreadLocal<Int>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        currentPriority.set(priority)
        try {
            super.log(priority, tag, message, t)
        } finally {
            currentPriority.remove()
        }
    }

    override fun createStackElementTag(element: StackTraceElement): String {
        val pkg = element.className.substringBeforeLast(".")
        val priority = currentPriority.get() ?: Log.DEBUG
        return if (priority == Log.INFO) {
            "$pkg"
        } else {
            "$pkg:${element.methodName}:${element.lineNumber}"
        }
    }
}