package com.telenebula.app.platform

import java.io.File
import java.io.IOException

/** A file that is absent is not one that could not be read; the two call for different words to the user. */
sealed interface FileRead {
    data object Missing : FileRead
    class Failed(val cause: Exception) : FileRead
    class Text(val value: String) : FileRead
}

/** Small file helpers; every write is temp-file + rename so a crash never leaves a torn file. */
object FileIo {
    fun read(file: File): FileRead {
        if (!file.isFile) return FileRead.Missing
        return try {
            FileRead.Text(file.readText())
        } catch (e: IOException) {
            FileRead.Failed(e)
        } catch (e: SecurityException) {
            FileRead.Failed(e)
        }
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.name}" }
        }
    }
}
