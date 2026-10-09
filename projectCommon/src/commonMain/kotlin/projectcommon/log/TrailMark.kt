package projectcommon.log

import kmpcommon.log.Crumb
import kmpcommon.log.Logger

/**
 * A trail riding a throw: the logger holds the story but a [Throwable] unwinds the stack without
 * it, so the frame that lets an exception escape stamps its trail on as a suppressed marker —
 * the value channel (code/message/cause) stays untouched, and [Logger.captureError] implementations
 * prefer the stamped trail over the catch site's shallower one. The message renders the path so
 * the marker stays readable wherever suppressed exceptions surface.
 */
class TrailMark internal constructor(val trail: List<Crumb>) :
    Exception(trail.joinToString("/") { it.tag })

/** Stamp [logger]'s trail onto this in-flight throwable. First mark wins — the deepest frame
 *  stamps first as the stack unwinds (the deepest-wins rule, like `Throwable.codedError`). */
fun Throwable.markTrail(logger: Logger?): Throwable {
    if (logger != null && suppressedExceptions.none { it is TrailMark }) {
        addSuppressed(TrailMark(logger.trail))
    }
    return this
}

/** The deepest trail stamped anywhere on this throwable's cause chain, or null. */
fun Throwable.trailMark(): List<Crumb>? {
    var deepest: List<Crumb>? = null
    var t: Throwable? = this
    var hops = 0
    while (t != null && hops++ < 100) {
        for (s in t.suppressedExceptions) if (s is TrailMark) deepest = s.trail
        t = t.cause
    }
    return deepest
}
