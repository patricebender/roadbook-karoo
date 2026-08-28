package io.roadbook.karoo.data

import java.util.Calendar

/**
 * A pragmatic parser for the common subset of OSM `opening_hours`. It is *not* a
 * full implementation of the spec — that grammar is large and we're offline on a
 * bike computer. It handles the shapes that cover the vast majority of POIs:
 *
 *   24/7
 *   Mo-Fr 08:00-18:00
 *   Mo-Fr 08:00-12:00,13:00-18:00
 *   Mo-Fr 08:00-18:00; Sa 09:00-13:00
 *   Mo,We,Fr 10:00-16:00
 *
 * We parse the spec once into a weekly [schedule] (per-day time ranges), then derive
 * both the live [status] (open now / when it next opens) and a table for the detail
 * view from it. Anything we can't confidently parse yields [OpenState.UNKNOWN] and the
 * caller shows "hours unknown" — never a wrong "open"/"closed" claim.
 */
object OpeningHours {

    enum class OpenState { OPEN, CLOSED, UNKNOWN }

    /** A minutes-of-day interval, e.g. 08:00-18:00 → [480, 1080]. */
    data class TimeRange(val startMin: Int, val endMin: Int) {
        fun format(): String = "${hhmm(startMin)}–${hhmm(endMin)}"
    }

    /**
     * Live open/closed with the transition time to display:
     *   OPEN   → [nextChangeMin] = when it closes today (may be null if unknown)
     *   CLOSED → [nextChangeMin]/[nextChangeDay] = when it next opens
     */
    data class Status(
        val state: OpenState,
        val nextChangeMin: Int? = null,
        val nextChangeDay: Int? = null, // day index of next open (for CLOSED), else null
    ) {
        /** "opens 08:00", "opens Mon 08:00", or null when not applicable. */
        fun opensAtLabel(todayIndex: Int): String? {
            if (state != OpenState.CLOSED || nextChangeMin == null) return null
            val time = hhmm(nextChangeMin)
            return if (nextChangeDay == null || nextChangeDay == todayIndex) {
                "opens $time"
            } else {
                "opens ${DAY_LABELS[nextChangeDay]} $time"
            }
        }
    }

    /**
     * A normalized weekly opening-hours schedule, independent of source (OSM or
     * Google). This is the single model the UI renders — the list badge, the detail
     * table, and the live open/closed state all derive from it, so both sources look
     * and behave identically.
     *
     * - [schedule]: day (0=Mon..6=Sun) → time ranges; a missing/empty day is closed.
     * - [is247]: always open; rendered as a single "Open 24/7" line, not seven rows.
     * - [rawFallback]: an OSM spec we couldn't structure — shown verbatim in detail,
     *   with no open/closed claim in the list.
     */
    class Hours private constructor(
        val schedule: Map<Int, List<TimeRange>>,
        val is247: Boolean,
        val rawFallback: String?,
    ) {
        fun status(now: Calendar = Calendar.getInstance()): Status {
            if (is247) return Status(OpenState.OPEN)
            if (rawFallback != null) return Status(OpenState.UNKNOWN)
            return statusOf(schedule, now)
        }

        companion object {
            /** Parse an OSM `opening_hours` spec. Never null — degrades to a raw fallback. */
            fun fromOsm(spec: String): Hours {
                val s = spec.trim()
                if (s == "24/7") return Hours(emptyMap(), is247 = true, rawFallback = null)
                val sched = schedule(s)
                return if (sched != null) {
                    Hours(sched, is247 = false, rawFallback = null)
                } else {
                    Hours(emptyMap(), is247 = false, rawFallback = s)
                }
            }

            /** Wrap an already-structured schedule (e.g. from Google Places). */
            fun fromSchedule(schedule: Map<Int, List<TimeRange>>): Hours {
                val is247 = (0..6).all { day ->
                    schedule[day]?.any { it.startMin == 0 && it.endMin >= 24 * 60 } == true
                }
                return Hours(if (is247) emptyMap() else schedule, is247, rawFallback = null)
            }
        }
    }

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    // Calendar.DAY_OF_WEEK is 1=Sunday..7=Saturday → index into DAYS (0=Mo).
    private fun dayIndex(cal: Calendar): Int =
        when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6 // Sunday
        }

    /**
     * Parse the spec into per-day time ranges (0=Mon … 6=Sun). Returns null if the
     * spec uses features we don't parse (PH, months, comments, offsets, 24/7 is
     * special-cased by callers). Days with no entry are treated as closed.
     */
    fun schedule(spec: String): Map<Int, List<TimeRange>>? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (s == "24/7") return (0..6).associateWith { listOf(TimeRange(0, 24 * 60)) }

        val byDay = HashMap<Int, MutableList<TimeRange>>()
        var sawAny = false
        for (rule in splitRules(s)) {
            val parsed = parseRuleRanges(rule.trim()) ?: continue // skip unparsable rule
            sawAny = true
            for (day in parsed.first) {
                byDay.getOrPut(day) { mutableListOf() }.addAll(parsed.second)
            }
        }
        return if (sawAny) byDay else null
    }

    /** Live status from a normalized [schedule] at [now]. */
    private fun statusOf(schedule: Map<Int, List<TimeRange>>, now: Calendar): Status {
        val today = dayIndex(now)
        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Open now? (handle ranges wrapping past midnight from the previous day too)
        for (r in schedule[today].orEmpty()) {
            if (contains(r, minutesNow)) {
                return Status(OpenState.OPEN, nextChangeMin = r.endMin.coerceAtMost(24 * 60))
            }
        }

        // Closed → find the next opening within the next 7 days.
        for (offset in 0..7) {
            val day = (today + offset) % 7
            val ranges = schedule[day].orEmpty().sortedBy { it.startMin }
            for (r in ranges) {
                if (offset == 0 && r.startMin <= minutesNow) continue // already past today
                return Status(OpenState.CLOSED, nextChangeMin = r.startMin, nextChangeDay = day)
            }
        }
        return Status(OpenState.CLOSED)
    }

    private fun contains(r: TimeRange, minute: Int): Boolean =
        if (r.endMin >= r.startMin) minute in r.startMin until r.endMin
        else minute >= r.startMin || minute < r.endMin // wraps past midnight

    /**
     * Split a spec into atomic rules. Rules are separated by ';' and *also* by a
     * comma that starts a new day-scoped rule. The comma is ambiguous in OSM:
     *   - "We,Th 17:00-22:30"        → comma joins days (one rule)
     *   - "08:00-12:00,13:00-18:00"  → comma joins time ranges (one rule)
     *   - "…22:30, Fr,Sa 17:00-…"    → comma *starts a new rule*
     * The discriminator: a comma begins a new rule only when the chunk built so far
     * already contains a time (a digit) *and* the text after the comma starts with a
     * weekday. Otherwise the comma stays inside the current rule.
     */
    private fun splitRules(spec: String): List<String> {
        val out = mutableListOf<String>()
        for (segment in spec.split(';')) {
            var start = 0
            var i = 0
            while (i < segment.length) {
                if (segment[i] == ',' &&
                    startsWithDay(segment, i + 1) &&
                    segment.substring(start, i).any { it.isDigit() }
                ) {
                    out.add(segment.substring(start, i))
                    start = i + 1
                }
                i++
            }
            out.add(segment.substring(start))
        }
        return out
    }

    /** True if the text at [from] (skipping spaces) begins with a weekday abbrev. */
    private fun startsWithDay(s: String, from: Int): Boolean {
        var i = from
        while (i < s.length && s[i] == ' ') i++
        if (i + 2 > s.length) return false
        return DAYS.contains(s.substring(i, i + 2))
    }

    /**
     * Parse one rule like "Mo-Fr 08:00-18:00,13:00-18:00" into its (days, ranges),
     * or null if unparsable (e.g. contains "off", "PH", a month, or a time offset).
     */
    private fun parseRuleRanges(rule: String): Pair<Set<Int>, List<TimeRange>>? {
        if (rule.isEmpty()) return null

        val timeStart = rule.indexOfFirst { it.isDigit() }
        if (timeStart < 0) return null // e.g. "Su off" — no times, treat as unparsable rule
        val daysPart = rule.substring(0, timeStart).trim().trimEnd(',').trim()
        val timesPart = rule.substring(timeStart).trim()

        val days = if (daysPart.isEmpty()) (0..6).toSet() else parseDays(daysPart) ?: return null

        val ranges = mutableListOf<TimeRange>()
        for (range in timesPart.split(',')) {
            val tr = parseTimeRange(range.trim()) ?: return null
            ranges.add(tr)
        }
        return days to ranges
    }

    /** Parse "Mo-Fr" / "Mo,We,Fr" / "Mo" into a set of day indices, or null. */
    private fun parseDays(part: String): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (token in part.split(',')) {
            val t = token.trim()
            if (t.contains('-')) {
                val (a, b) = t.split('-').map { it.trim() }
                val ia = DAYS.indexOf(a); val ib = DAYS.indexOf(b)
                if (ia < 0 || ib < 0) return null
                var i = ia
                while (true) { out.add(i); if (i == ib) break; i = (i + 1) % 7 }
            } else {
                val i = DAYS.indexOf(t)
                if (i < 0) return null
                out.add(i)
            }
        }
        return out
    }

    /** Parse "08:00-18:00" into a [TimeRange]; null if not parseable. */
    private fun parseTimeRange(range: String): TimeRange? {
        val parts = range.split('-')
        if (parts.size != 2) return null
        val start = parseHhmm(parts[0]) ?: return null
        val end = parseHhmm(parts[1]) ?: return null
        return TimeRange(start, end)
    }

    private fun parseHhmm(v: String): Int? {
        val p = v.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun hhmm(min: Int): String {
        val m = min % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
