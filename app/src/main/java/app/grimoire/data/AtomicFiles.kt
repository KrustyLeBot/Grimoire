package app.grimoire.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A synced file that cannot be parsed, even when read again: reported, never overwritten. */
class UnreadableFileException(file: File) : IOException("${file.name} est illisible : il n'est pas modifié")

/**
 * Writes of the files Synchrotron syncs (and PCs read on the server): content goes to
 * `.<name>.synctmp-<uuid>` in the same folder, is flushed to disk, then renamed over the target, so a
 * reader never sees half a file. Synchrotron never syncs that pattern; no app file may use it.
 */
object AtomicFiles {
    fun tempFor(target: File) = File(target.parentFile, ".${target.name}.synctmp-${UUID.randomUUID()}")

    fun write(target: File, fill: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = tempFor(target)
        try {
            FileOutputStream(tmp).use { out ->
                fill(out)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    fun write(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    fun write(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    /**
     * Reads and parses [file]; null when it does not exist. A sync may replace the file between two
     * reads: a parse failure is retried once before the file is declared unreadable.
     */
    fun <T> read(file: File, parse: (String) -> T): T? {
        repeat(2) { attempt ->
            if (!file.exists()) return null
            try {
                return parse(file.readText())
            } catch (e: Exception) {
                if (attempt == 0) Thread.sleep(300)
            }
        }
        throw UnreadableFileException(file)
    }
}
