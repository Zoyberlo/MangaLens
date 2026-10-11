package mihon.feature.devlog

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import logcat.LogPriority
import logcat.LogcatLogger
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A log that outlives logcat — dev builds only.
 *
 * Logcat on the owner's phone holds a few minutes. Errors seen while reading —
 * "Translation failed", a misread bubble — were gone by the time the phone was
 * plugged in, so every report had to be reproduced from scratch, and a failure
 * that happens one time in eleven does not reproduce on demand. This keeps
 * everything the app logs, one file per day, and **never deletes anything on
 * its own**: only [clear] does, from a button in Settings → Advanced.
 *
 * It also records how the previous process ended. A native crash — the way
 * ONNX Runtime died under R8 — kills the process before any Kotlin code can
 * write a word, so the only place to catch it is the next start, from the
 * system's own exit record.
 */
object PersistentLog {

    private const val DIR = "devlog"
    private const val PREFS = "devlog"
    private const val LAST_EXIT_SEEN = "last_exit_seen"

    private var writer: DailyLogWriter? = null

    val isInstalled: Boolean
        get() = writer != null

    fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Call once from Application.onCreate, dev builds only. */
    fun install(context: Context, minPriority: LogPriority) {
        if (writer != null) return
        val created = DailyLogWriter(directory(context))
        writer = created
        LogcatLogger.loggers += FileLogcatLogger(created, minPriority)
        created.background { append -> recordPreviousExits(context, append) }
    }

    fun files(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()

    fun totalBytes(context: Context): Long = files(context).sumOf { it.length() }

    /** Blocks until every line logged so far is on disk. Call off the main thread. */
    fun sync() {
        writer?.sync()
    }

    /** Call off the main thread. */
    fun clear(context: Context) {
        val writer = writer
        if (writer != null) {
            writer.clear()
        } else {
            files(context).forEach { it.delete() }
        }
    }

    /**
     * Writes every process exit the system recorded since the last start —
     * crashes, native crashes, ANRs, low-memory kills — with the native crash
     * record or ANR trace where Android keeps one.
     */
    private fun recordPreviousExits(context: Context, append: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(LAST_EXIT_SEEN, 0L)
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
        } catch (_: Exception) {
            return
        }
        if (exits.isEmpty()) return

        for (exit in exits) {
            val out = StringBuilder()
            val reason = reasonName(exit.reason)
            out.append(stamp()).append(" I/PreviousExit: ")
                .append(reason).append(" at ").append(EXIT_TIME.format(Instant.ofEpochMilli(exit.timestamp)))
                .append(", importance ").append(exit.importance)
            if (exit.pss > 0) out.append(", pss ").append(exit.pss / 1024).append(" MB")
            exit.description?.let { out.append(", ").append(it) }
            out.append('\n')
            val trace = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> readTrace(exit) { it.bufferedReader().readLines() }
                // A protobuf tombstone. The parts worth reading — abort message,
                // signal, the backtrace's library and function names — are
                // plain strings inside it, so pull those out rather than ship
                // a proto parser in the app.
                ApplicationExitInfo.REASON_CRASH_NATIVE -> readTrace(exit) { printableRuns(it.readBytes()) }
                else -> null
            }
            trace?.take(MAX_TRACE_LINES)?.forEach { out.append("    ").append(it).append('\n') }
            append(Redactor.redact(out.toString()))
        }
        prefs.edit().putLong(LAST_EXIT_SEEN, exits.maxOf { it.timestamp }).apply()
    }

    private fun readTrace(exit: ApplicationExitInfo, read: (InputStream) -> List<String>): List<String>? =
        try {
            exit.traceInputStream?.use(read)
        } catch (_: Exception) {
            null
        }

    private fun printableRuns(bytes: ByteArray): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                if (current.length >= MIN_PRINTABLE_RUN) runs += current.toString()
                current.setLength(0)
            }
        }
        if (current.length >= MIN_PRINTABLE_RUN) runs += current.toString()
        return runs
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
        ApplicationExitInfo.REASON_SIGNALED -> "signalled"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by system"
        // Added in API 33, so spelled out rather than referenced
        14 -> "frozen"
        15 -> "package state change"
        16 -> "app updated"
        else -> "reason $reason"
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val EXIT_TIME: DateTimeFormatter = TIME.withZone(ZoneId.systemDefault())

    internal fun stamp(): String = LocalDateTime.now().format(TIME)

    private const val MAX_TRACE_LINES = 300
    private const val MIN_PRINTABLE_RUN = 6
}

/**
 * Appends to `mangalens-<date>.log`, on one background thread so lines land in
 * the order they were logged and the reader never waits on the disk.
 *
 * Every line is flushed as it is written: a process can be killed at any
 * moment, and the lines just before a kill are the ones that explain it.
 */
private class DailyLogWriter(private val dir: File) {

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PersistentLog").apply { isDaemon = true }
    }

    // Touched only on the executor thread
    private var out: FileWriter? = null
    private var day: LocalDate? = null
    private var bytes = 0L
    private var full = false

    fun write(text: String) {
        executor.execute { append(text) }
    }

    /**
     * Writes and waits. For the line logged just before an uncaught exception
     * kills the process: queued, it would die in the queue.
     */
    fun writeNow(text: String) {
        await { append(text) }
    }

    fun sync() {
        await {}
    }

    fun clear() {
        await {
            out?.runCatching { close() }
            out = null
            day = null
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { it.delete() }
        }
    }

    /**
     * Runs [block] on the writer's thread, with an `append` that writes in
     * place. Waiting on the queue from inside it would wait on itself.
     */
    fun background(block: (append: (String) -> Unit) -> Unit) {
        executor.execute { runCatching { block(::append) } }
    }

    private fun await(block: () -> Unit) {
        runCatching { executor.submit(block).get(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    private fun append(text: String) {
        try {
            val writer = writerForToday() ?: return
            if (bytes > MAX_BYTES_PER_DAY) {
                if (!full) {
                    full = true
                    writer.append("${PersistentLog.stamp()} W/PersistentLog: ")
                        .append("this file passed ${MAX_BYTES_PER_DAY / MB} MB; nothing more is written to it today\n")
                    writer.flush()
                }
                return
            }
            writer.append(text)
            if (!text.endsWith('\n')) writer.append('\n')
            writer.flush()
            bytes += text.length + 1
        } catch (_: Exception) {
            // A full disk must not take the app down with it
        }
    }

    /** Opens a new file when the date changes, so each day gets its own. */
    private fun writerForToday(): FileWriter? {
        val today = LocalDate.now()
        out?.let { if (day == today) return it }
        out?.runCatching { close() }
        dir.mkdirs()
        val file = File(dir, "mangalens-$today.log")
        return FileWriter(file, true).also {
            out = it
            day = today
            bytes = file.length()
            full = false
        }
    }

    private companion object {
        const val WAIT_SECONDS = 2L
        const val MB = 1024L * 1024L

        /**
         * A runaway loop logging the same error thousands of times a second
         * would otherwise fill the phone. Stops writing, never deletes; a
         * normal day of reading is a few hundred kilobytes.
         */
        const val MAX_BYTES_PER_DAY = 64 * MB
    }
}

/**
 * Receives every `logcat { }` call in the app, alongside the stock Android
 * logger, at the same threshold that logger uses.
 */
private class FileLogcatLogger(
    private val writer: DailyLogWriter,
    private val minPriority: LogPriority,
) : LogcatLogger {

    override fun isLoggable(priority: LogPriority): Boolean = priority >= minPriority

    override fun log(priority: LogPriority, tag: String, message: String) {
        val line = "${PersistentLog.stamp()} ${priority.short}/$tag: ${Redactor.redact(message)}"
        if (priority >= LogPriority.ERROR) writer.writeNow(line) else writer.write(line)
    }

    private val LogPriority.short: Char
        get() = when (this) {
            LogPriority.VERBOSE -> 'V'
            LogPriority.DEBUG -> 'D'
            LogPriority.INFO -> 'I'
            LogPriority.WARN -> 'W'
            LogPriority.ERROR -> 'E'
            LogPriority.ASSERT -> 'A'
        }
}

/**
 * Masks credentials before anything reaches the file, because this file is
 * meant to be shared. Google Vision and Gemini carry their keys in the URL
 * (`?key=`), and an exception that quotes the URL would otherwise put a
 * billable key into an email attachment.
 */
internal object Redactor {

    private val patterns = listOf(
        Regex("""([?&]key=)[^&\s"']+""") to "$1***",
        Regex("""AIza[0-9A-Za-z_\-]{30,}""") to "AIza***",
        Regex("""(DeepL-Auth-Key\s+)\S+""", RegexOption.IGNORE_CASE) to "$1***",
        Regex("""(Ocp-Apim-Subscription-Key["':=\s]+)[0-9A-Za-z]+""", RegexOption.IGNORE_CASE) to "$1***",
        // DeepL keys are bare UUIDs (":fx" on the free plan). Any UUID goes, at
        // the cost of a request id now and then.
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(:fx)?\b""") to "***",
        Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""") to "$1***",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (regex, replacement) ->
        regex.replace(acc, replacement)
    }
}
