package kmphttp.internal

import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.DateTimeFormatBuilder.WithDateTimeComponents
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.alternativeParsing
import kotlinx.datetime.format.char
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal const val MAX_DATE = 253402300799999L

/**
 * Most websites serve cookies in the blessed format. Eagerly create the parser to ensure such
 * cookies are on the fast path.
 */
private val rfc7231Format = DateTimeComponents.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    chars(", ")
    day(Padding.ZERO)
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    year()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
    chars(" GMT")
}

private fun WithDateTimeComponents.timeWithAlternativeSeparators() {
    hour()
    alternativeParsing({ char('-') }) { char(':') }
    minute()
    alternativeParsing({ char('-') }) { char(':') }
    second()
}

private fun WithDateTimeComponents.dateSeparator() {
    alternativeParsing({ char('-') }, { char('/') }) { char(' ') }
}

private fun WithDateTimeComponents.dayOfWeekFlexible() {
    alternativeParsing({
        dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
    }) {
        dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    }
}

val httpDateFormats: Array<DateTimeFormat<DateTimeComponents>> = arrayOf(
    // RFC 822/1123 with 4-digit year
    DateTimeComponents.Format {
        dayOfWeekFlexible()
        alternativeParsing({ char(',') }, { char(' ') }) { chars(", ")  }
        day(Padding.ZERO)
        dateSeparator()
        alternativeParsing({ monthName(MonthNames.ENGLISH_ABBREVIATED) }) { monthNumber(Padding.ZERO) }
        dateSeparator()
        // 2-digit year resolves into the 1970-2069 window; the 4-digit form is used verbatim.
        alternativeParsing({ yearTwoDigits(1970) }) { year() }
        char(' ')
        timeWithAlternativeSeparators()
        char(' ')
        timeZoneId()
    },

    // ANSI C's asctime() format: "EEE MMM d HH:mm:ss yyyy"
    DateTimeComponents.Format {
        dayOfWeekFlexible()
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        alternativeParsing({ day(Padding.SPACE) }) { day(Padding.NONE) }
        char(' ')
        timeWithAlternativeSeparators()
        char(' ')
        year()
    },

    // Yahoo format: "EEE MMM d yyyy HH:mm:ss z"
    DateTimeComponents.Format {
        dayOfWeekFlexible()
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        alternativeParsing({ day(Padding.SPACE) }) {  day(Padding.NONE) }
        char(' ')
        year()
        char(' ')
        timeWithAlternativeSeparators()
        char(' ')
        timeZoneId()
    },
)

/** Returns the date for this string, or null if the value couldn't be parsed. */
@OptIn(ExperimentalTime::class)
fun String.toHttpDateOrNull(): Instant? {
    if (isEmpty()) return null

    return httpDateFormats.firstNotNullOfOrNull { format ->
        val components = format.parseOrNull(this) ?: return@firstNotNullOfOrNull null
        // parseOrNull is lenient (Feb 31, second=70); toInstantUsingOffset does the range check and
        // throws on an invalid value — map that to null so this stays an ...OrNull.
        try {
            components.toInstantUsingOffset()
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Returns the string for this date. */
@OptIn(ExperimentalTime::class)
fun Instant.toHttpDateString(): String = format(rfc7231Format, UtcOffset.ZERO)
