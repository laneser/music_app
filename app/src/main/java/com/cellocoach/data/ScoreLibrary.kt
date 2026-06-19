package com.cellocoach.data

import java.io.File

/**
 * Persistent store for user-imported MusicXML scores (from a file or a URL).
 *
 * Imported scores are copied into [dir] (the app's `filesDir/scores`) so they
 * survive restarts and show up in the picker alongside the bundled assets. Pure
 * file I/O — no Android dependency — so it is unit testable with a temp dir.
 */
class ScoreLibrary(private val dir: File) {

    private val allowed = listOf(".musicxml", ".mxl", ".xml")

    private fun ensureDir() { if (!dir.exists()) dir.mkdirs() }

    /** Imported score filenames, sorted. */
    fun list(): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && allowed.any { ext -> it.name.endsWith(ext, true) } }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun exists(name: String): Boolean = File(dir, sanitize(name)).isFile

    fun read(name: String): ByteArray? =
        File(dir, sanitize(name)).takeIf { it.isFile }?.readBytes()

    /**
     * Save [bytes] under a cleaned-up [rawName]. Returns the stored filename
     * (which the caller can then select in the picker).
     */
    fun save(rawName: String, bytes: ByteArray): String {
        ensureDir()
        val name = sanitize(rawName)
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun delete(name: String): Boolean = File(dir, sanitize(name)).delete()

    /**
     * Reduce an arbitrary name (possibly a URL tail or a content-provider
     * display name) to a safe bare filename with a recognised extension.
     */
    fun sanitize(rawName: String): String {
        var n = rawName.substringAfterLast('/').substringAfterLast('\\')
            .substringBefore('?').substringBefore('#')
            .trim()
            .replace(Regex("""[^\w.\-]"""), "_")
        if (n.isEmpty()) n = "imported"
        if (allowed.none { n.endsWith(it, true) }) n += ".musicxml"
        return n
    }
}
