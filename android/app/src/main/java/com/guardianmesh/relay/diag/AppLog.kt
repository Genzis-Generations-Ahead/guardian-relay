package com.guardianmesh.relay.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One log bus for the whole app. Every module writes here; the debug screen
 * (MainActivity) renders it on-screen, and every line is mirrored to Logcat
 * under the tag "Guardian/<tag>" so `adb logcat -s Guardian/BLE` etc. works too.
 *
 * Thread-safe: call from any thread. Listeners are invoked on the calling
 * thread — UI code must hop to the main thread itself (MainActivity does).
 */
object AppLog {

    enum class Level(val symbol: String) { DEBUG("·"), INFO("i"), OK("✓"), WARN("!"), ERROR("✗") }

    data class Line(val timeMs: Long, val level: Level, val tag: String, val message: String) {
        fun format(): String = "${TIME.get()!!.format(Date(timeMs))} ${level.symbol} [$tag] $message"
    }

    private const val MAX_LINES = 400
    private val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    private val lines = ArrayDeque<Line>()
    private val listeners = CopyOnWriteArrayList<(Line) -> Unit>()

    /** Swappable so pure-JVM unit tests don't touch android.util.Log. */
    @Volatile
    var logcatSink: ((Level, String, String) -> Unit)? = { level, tag, msg ->
        val t = "Guardian/$tag"
        when (level) {
            Level.ERROR -> android.util.Log.e(t, msg)
            Level.WARN -> android.util.Log.w(t, msg)
            Level.DEBUG -> android.util.Log.d(t, msg)
            else -> android.util.Log.i(t, msg)
        }
    }

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun ok(tag: String, msg: String) = add(Level.OK, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add(Level.ERROR, tag, if (t == null) msg else "$msg — ${t.javaClass.simpleName}: ${t.message}")

    fun add(level: Level, tag: String, message: String) {
        val line = Line(System.currentTimeMillis(), level, tag, message)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        try { logcatSink?.invoke(level, tag, message) } catch (_: Throwable) { /* never let logging crash the SOS path */ }
        listeners.forEach { runCatching { it(line) } }
    }

    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    /** Plain-text dump, newest last — what the "Copy log" button puts on the clipboard. */
    fun dump(): String = snapshot().joinToString("\n") { it.format() }

    fun clear() = synchronized(lines) { lines.clear() }

    fun addListener(l: (Line) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Line) -> Unit) { listeners.remove(l) }
}
