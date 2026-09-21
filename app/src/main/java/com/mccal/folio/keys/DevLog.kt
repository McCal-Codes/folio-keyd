package com.mccal.folio.keys

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What Keyd Dev writes down about itself, so a bug can be found without asking someone to describe it.
 *
 * **Never what is typed.** A keyboard sees everything, so the log's shape makes leaking it impossible rather than
 * unlikely: an event is a fixed name and numbers, and an error keeps the exception's class and where it happened but
 * not its message, because a message can quote its input ("For input string: …"). Nothing here takes a String from a
 * text field, and a reviewer only has to check that event names are literals.
 *
 * Only the dev builds write anything. The Keyd people get from the Market has the same calls, and they do nothing.
 * It all stays in two small files on the phone; Share hands them to Android's share sheet, and only a person can send.
 */
internal object DevLog {
    const val MAX_LINES = 500
    const val MAX_ERRORS = 20

    /** A suggestion slower than this is written down as a problem, not just an event. */
    const val SLOW_MS = 150L

    const val LOGGING_KEY = "devLogging"

    /** Keyd Dev (release-signed, from the source) and Keyd Debug (a local build). The Market's Keyd is neither. */
    fun isDevBuild(packageName: String) = packageName.endsWith(".dev") || packageName.endsWith(".debug")

    /** One line: when, what, and the numbers that go with it, in the order given. */
    internal fun line(time: LocalDateTime, name: String, numbers: List<Pair<String, Number>>): String =
        buildString {
            append(time.format(STAMP)).append(' ').append(name)
            numbers.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        }

    /** An error as it is kept: its class, where it was caught, and the top of the stack. Never the message. */
    internal fun describe(time: LocalDateTime, where: String, error: Throwable): String =
        buildString {
            append(time.format(STAMP)).append(' ').append(error.javaClass.simpleName.ifEmpty { "Throwable" })
            append(" in ").append(where)
            error.stackTrace.take(6).forEach { append("\n    at ").append(it) }
        }

    /** Keeps the newest [limit] entries of a file, appending [entry]. Entries are separated by a blank line. */
    internal fun appendKeeping(existing: String, entry: String, limit: Int): String {
        val entries = existing.split(SEPARATOR).filter { it.isNotBlank() } + entry
        return entries.takeLast(limit).joinToString(SEPARATOR) + SEPARATOR
    }

    internal fun entries(text: String): List<String> = text.split(SEPARATOR).filter { it.isNotBlank() }

    fun loggingOn(context: Context): Boolean =
        isDevBuild(context.packageName) &&
            context.getSharedPreferences("keys", Context.MODE_PRIVATE).getBoolean(LOGGING_KEY, false)

    fun setLogging(context: Context, on: Boolean) {
        context.getSharedPreferences("keys", Context.MODE_PRIVATE).edit().putBoolean(LOGGING_KEY, on).apply()
    }

    /** Something the keyboard did. [name] must be a literal; the values are numbers, so text can't get in. */
    fun event(context: Context, name: String, vararg numbers: Pair<String, Number>) {
        if (!loggingOn(context)) return
        add(context, LOG_FILE, line(LocalDateTime.now(), name, numbers.toList()), MAX_LINES)
    }

    /** A problem that isn't an exception, like a slow suggestion. Kept with the errors, whether or not logging is on. */
    fun problem(context: Context, name: String, vararg numbers: Pair<String, Number>) {
        if (!isDevBuild(context.packageName)) return
        add(context, ERROR_FILE, line(LocalDateTime.now(), name, numbers.toList()), MAX_ERRORS)
    }

    fun error(context: Context, where: String, error: Throwable) {
        if (!isDevBuild(context.packageName)) return
        add(context, ERROR_FILE, describe(LocalDateTime.now(), where, error), MAX_ERRORS)
    }

    /**
     * Writes a crash down before Android ends the process, then lets the crash carry on as it would have. Installed
     * once per process; later calls do nothing.
     */
    fun catchCrashes(context: Context) {
        if (!isDevBuild(context.packageName) || installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { error(app, "crash on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun errors(context: Context): List<String> = entries(read(context, ERROR_FILE)).reversed()

    fun lines(context: Context): List<String> = entries(read(context, LOG_FILE))

    /** One text file for the share sheet: what build this is, then the errors, then the log. */
    fun report(context: Context, build: String): String = buildString {
        append("Keyd developer report\n").append(build).append("\n\nRecent errors\n")
        errors(context).ifEmpty { listOf("None") }.forEach { append(it).append('\n') }
        append("\nLog\n")
        lines(context).ifEmpty { listOf("Nothing logged. Turn on Detailed logging to record events.") }
            .forEach { append(it).append('\n') }
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, LOG_FILE).delete()
        File(context.filesDir, ERROR_FILE).delete()
    }

    @Synchronized
    private fun add(context: Context, name: String, entry: String, limit: Int) {
        runCatching {
            val file = File(context.filesDir, name)
            val existing = if (file.isFile) file.readText() else ""
            file.writeText(appendKeeping(existing, entry, limit))
        }
    }

    @Synchronized
    private fun read(context: Context, name: String): String =
        runCatching { File(context.filesDir, name).takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    @Volatile private var installed = false
    private const val LOG_FILE = "dev-log.txt"
    private const val ERROR_FILE = "dev-errors.txt"
    private const val SEPARATOR = "\n\n"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
