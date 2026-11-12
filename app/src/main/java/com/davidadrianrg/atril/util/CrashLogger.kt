package com.davidadrianrg.atril.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Guarda la última excepción no capturada en `files/crash_last.txt` y deja que
 * el handler del sistema cierre la app. En el siguiente arranque la app ofrece
 * el texto para copiarlo, útil cuando el fallo solo ocurre en un dispositivo.
 */
object CrashLogger {

    private const val FILE_NAME = "crash_last.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(appContext.filesDir, FILE_NAME).writeText(
                    "Thread: ${thread.name}\nApp: ${appContext.packageName}\n\n$stack",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
