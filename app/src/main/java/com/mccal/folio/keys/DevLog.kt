package com.mccal.folio.keys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What Keyd writes down about itself, so a bug can be found without asking someone to describe it.
 *
 * **Never what is typed.** A keyboard sees everything, so the log's shape makes leaking it impossible rather than
 * unlikely: an event is a fixed name and numbers, and an error keeps the exception's class and where it happened but
 * not its message, because a message can quote its input ("For input string: …"). Nothing here takes a String from a
 * text field, and a reviewer only has to check that event names are literals.
 *
 * Every build keeps errors and crashes, since a crash in the Keyd people actually use is the one worth fixing. The
 * detailed log is off unless someone turns it on, and outside Keyd Dev it turns itself off again after a day, so a
 * switch flipped for one bug report is not left recording for months.
 * It all stays in two small files on the phone. A report is built from them only when someone asks, shown to them
 * line by line, and handed to Android's share sheet: only a person can send it.
 */
internal object DevLog {
    const val MAX_LINES = 500
    const val MAX_ERRORS = 20

    /** A suggestion slower than this is written down as a problem, not just an event. */
    const val SLOW_MS = 150L

    const val LOGGING_KEY = "devLogging"
    const val LOGGING_SINCE_KEY = "devLoggingSince"
    const val CRASHED_KEY = "crashedSinceLooked"

    /** How long the detailed log stays on outside Keyd Dev before it turns itself off. */
    const val LOGGING_FOR_MS = 24L * 60 * 60 * 1000

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

    /**
     * Whether the detailed log is recording. Outside Keyd Dev it stops [LOGGING_FOR_MS] after it was switched on, and
     * this is where that happens: the first check after the day is up turns the switch off for good.
     */
    fun loggingOn(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(LOGGING_KEY, false)) return false
        val on = stillLogging(isDevBuild(context.packageName), prefs.getLong(LOGGING_SINCE_KEY, 0L), now)
        if (!on) prefs.edit().putBoolean(LOGGING_KEY, false).remove(LOGGING_SINCE_KEY).apply()
        return on
    }

    /**
     * Keyd Dev logs for as long as its switch is on; every other build for a day at most. A switch with no start
     * time was never turned on by this version, so it counts as expired rather than as on forever.
     */
    internal fun stillLogging(dev: Boolean, since: Long, now: Long): Boolean =
        dev || (since in 1..now && now - since < LOGGING_FOR_MS)

    fun setLogging(context: Context, on: Boolean, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().apply {
            putBoolean(LOGGING_KEY, on)
            if (on) putLong(LOGGING_SINCE_KEY, now) else remove(LOGGING_SINCE_KEY)
        }.apply()
    }

    /** Set when a crash is written down, so Settings can offer a report the next time it opens. */
    fun crashedSinceLooked(context: Context): Boolean = prefs(context).getBoolean(CRASHED_KEY, false)

    /** Cleared when someone dismisses the card or sends a report: either way they have seen it. */
    fun markLooked(context: Context) {
        prefs(context).edit().remove(CRASHED_KEY).apply()
    }

    /** Something the keyboard did. [name] must be a literal; the values are numbers, so text can't get in. */
    fun event(context: Context, name: String, vararg numbers: Pair<String, Number>) {
        if (!loggingOn(context)) return
        val entry = line(LocalDateTime.now(), name, numbers.toList())
        // Held in memory and written in batches: an event per keystroke used to read and rewrite the whole file each
        // time. The batch goes to disk when the keyboard hides, when Android asks for memory, before anything reads
        // the log, and whenever it reaches [BATCH].
        val full = synchronized(this) { pending += entry; pending.size >= BATCH }
        if (full) flush(context)
    }

    /** Writes the events held in memory to the log file. Cheap when there are none. */
    fun flush(context: Context) {
        val batch = synchronized(this) { pending.toList().also { pending.clear() } }
        if (batch.isNotEmpty()) addAll(context, LOG_FILE, batch, MAX_LINES)
    }

    /** A problem that isn't an exception, like a slow suggestion. Kept with the errors, whether or not logging is on. */
    fun problem(context: Context, name: String, vararg numbers: Pair<String, Number>) {
        add(context, ERROR_FILE, line(LocalDateTime.now(), name, numbers.toList()), MAX_ERRORS)
    }

    fun error(context: Context, where: String, error: Throwable) {
        add(context, ERROR_FILE, describe(LocalDateTime.now(), where, error), MAX_ERRORS)
    }

    /**
     * An error in code that runs on every keystroke, written down the first time it happens at [where] in this
     * process. The strip carries on without an answer, as it always did; this is so a report says why it was empty,
     * without one broken word list filling the error file with the same line.
     */
    fun errorOnce(context: Context, where: String, error: Throwable) {
        if (synchronized(this) { reported.add(where) }) error(context, where, error)
    }

    /**
     * Writes a crash down before Android ends the process, then lets the crash carry on as it would have. Installed
     * once per process; later calls do nothing.
     */
    fun catchCrashes(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashed(app, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * What the crash handler does, apart so it can be tested without crashing anything. The mark is committed, not
     * applied: the process is about to end, and an apply would still be waiting in memory when it does.
     */
    @SuppressLint("ApplySharedPref") // on purpose, as above
    internal fun crashed(context: Context, thread: String, error: Throwable) {
        runCatching { flush(context) }
        error(context, "crash on $thread", error)
        prefs(context).edit().putBoolean(CRASHED_KEY, true).commit()
    }

    fun errors(context: Context): List<String> = entries(read(context, ERROR_FILE)).reversed()

    fun lines(context: Context): List<String> {
        flush(context)
        return entries(read(context, LOG_FILE))
    }

    /** The three questions the report form asks. Typed by the person reporting, about the problem, not by the keyboard. */
    data class Answers(val app: String = "", val did: String = "", val saw: String = "") {
        val isEmpty: Boolean get() = app.isBlank() && did.isBlank() && saw.isBlank()
    }

    /** Which optional sections go into a report. The same four switches the report form shows. */
    data class Include(
        val device: Boolean = true,
        val settings: Boolean = true,
        val errors: Boolean = true,
        val log: Boolean = false,
    )

    /** The phone a report came from. Read from [Build] on a phone; given directly in tests. */
    data class Device(val android: String, val model: String, val windowDp: String)

    fun device(context: Context): Device {
        val config = context.resources.configuration
        return Device(
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            windowDp = "${config.screenWidthDp} x ${config.screenHeightDp} dp",
        )
    }

    /**
     * A report as plain text, gathered from this phone. [build] is the line Settings shows: package, version (code),
     * commit. There is deliberately no way to hand this anything from a text field: the only words in it are the
     * person's own answers to the form.
     */
    fun report(context: Context, build: String, answers: Answers, include: Include): String = compose(
        build = build,
        device = device(context),
        answers = answers,
        include = include,
        settings = Settings.load(prefs(context)),
        errors = errors(context),
        log = lines(context),
    )

    /** Keyd Dev's Share row: everything, with no questions asked. */
    fun report(context: Context, build: String): String =
        report(context, build, Answers(), Include(device = true, settings = true, errors = true, log = true))

    /** The report itself, from values rather than a phone, so every section can be checked in a test. */
    internal fun compose(
        build: String,
        device: Device,
        answers: Answers,
        include: Include,
        settings: Settings,
        errors: List<String>,
        log: List<String>,
    ): String = buildString {
        append("Keyd report\n")
        if (include.device) {
            append(build).append('\n')
            append("Android ").append(device.android).append('\n')
            append(device.model).append('\n')
            append("Window ").append(device.windowDp).append('\n')
        }
        if (!answers.isEmpty) {
            append("\nWhat happened\n")
            append("App: ").append(answers.app.trim().ifEmpty { "-" }).append('\n')
            append("Did: ").append(answers.did.trim().ifEmpty { "-" }).append('\n')
            append("Saw: ").append(answers.saw.trim().ifEmpty { "-" }).append('\n')
        }
        if (include.settings) {
            append("\nSettings\n")
            switches(settings).forEach { append(it).append('\n') }
        }
        if (include.errors) {
            append("\nRecent errors\n")
            errors.ifEmpty { listOf("None") }.forEach { append(it).append('\n') }
        }
        if (include.log) {
            append("\nLog\n")
            log.ifEmpty { listOf("Nothing logged. Turn on the diagnostic log to record events.") }
                .forEach { append(it).append('\n') }
        }
    }

    /**
     * Every setting as `name=value`, read from the data class's own description so a setting added later is
     * included without anyone remembering to. Only a switch or a named choice gets through: a value that is not
     * true, false or an enum's name is left out, so if Settings ever holds words they still cannot reach a report.
     */
    internal fun switches(settings: Settings): List<String> =
        // The period's symbols are typed by the person, so they are left out before anything else: a row of capital
        // letters would pass for a choice's name, and a bracket would upset the list below.
        settings.copy(periodSymbols = "").toString().substringAfter('(').substringBeforeLast(')')
            // A list of choices, like the toolbar's buttons, goes in as its names joined by +, or NONE when empty.
            .replace(LIST) { match -> match.groupValues[1].split(", ").filter { it.isNotEmpty() }.joinToString("+").ifEmpty { "NONE" } }
            .split(", ")
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                val value = pair.substringAfter('=', "")
                pair.takeIf { key.isNotEmpty() && SWITCH.matches(value) }
            }

    @Synchronized
    fun clear(context: Context) {
        pending.clear()
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
    private fun addAll(context: Context, name: String, entries: List<String>, limit: Int) {
        runCatching {
            val file = File(context.filesDir, name)
            var text = if (file.isFile) file.readText() else ""
            for (entry in entries) text = appendKeeping(text, entry, limit)
            file.writeText(text)
        }
    }

    @Synchronized
    private fun read(context: Context, name: String): String =
        runCatching { File(context.filesDir, name).takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Volatile private var installed = false
    private val pending = ArrayList<String>()
    private val reported = HashSet<String>()
    private const val BATCH = 50
    // A single digit is a small fixed choice kept as a number, like the emoji skin tone; nothing typed is one digit long.
    private val SWITCH = Regex("true|false|[0-9]|[A-Z][A-Z0-9_]*(\\+[A-Z][A-Z0-9_]*)*")
    private val LIST = Regex("\\[([^\\]]*)]")
    private const val LOG_FILE = "dev-log.txt"
    private const val ERROR_FILE = "dev-errors.txt"
    private const val SEPARATOR = "\n\n"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
