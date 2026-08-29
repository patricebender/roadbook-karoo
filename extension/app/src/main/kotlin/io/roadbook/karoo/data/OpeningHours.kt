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
     * - [rawFallback]: an OSM spec we couldn't structure at all — shown verbatim, with
     *   no open/closed claim.
     * - [seasonal]: we parsed a usable base week, but dropped one or more month-scoped
     *   rules (e.g. "May-Sep …"). The table is the off-season/base hours; the UI notes
     *   that hours vary seasonally so we don't imply the table is the whole story.
     */
    class Hours private constructor(
        val schedule: Map<Int, List<TimeRange>>,
        val is247: Boolean,
        val rawFallback: String?,
        val seasonal: Boolean = false,
        // We have a source that resolved the place but carries no usable hours (e.g. a
        // Google result with an empty schedule). Distinct from a genuine all-closed week:
        // an empty schedule must read as "unknown", never as a false "Closed".
        val unknown: Boolean = false,
    ) {
        fun status(now: Calendar = Calendar.getInstance()): Status {
            if (is247) return Status(OpenState.OPEN)
            if (unknown || rawFallback != null) return Status(OpenState.UNKNOWN)
            return statusOf(schedule, now)
        }

        companion object {
            /** Parse an OSM `opening_hours` spec. Never null — degrades to a raw fallback. */
            fun fromOsm(spec: String): Hours {
                val s = spec.trim()
                if (s == "24/7") return Hours(emptyMap(), is247 = true, rawFallback = null)
                val parsed = parse(s)
                return if (parsed != null) {
                    Hours(parsed.schedule, is247 = false, rawFallback = null, seasonal = parsed.seasonal)
                } else {
                    Hours(emptyMap(), is247 = false, rawFallback = s)
                }
            }

            /**
             * Wrap an already-structured schedule (e.g. from Google Places). An empty
             * schedule means the place resolved but has no hours on record → [unknown],
             * not closed.
             */
            fun fromSchedule(schedule: Map<Int, List<TimeRange>>): Hours {
                if (schedule.isEmpty()) return unknown()
                val is247 = (0..6).all { day ->
                    schedule[day]?.any { it.startMin == 0 && it.endMin >= 24 * 60 } == true
                }
                return Hours(if (is247) emptyMap() else schedule, is247, rawFallback = null)
            }

            /** No usable hours from any source — renders as "unknown", never "closed". */
            fun unknown(): Hours =
                Hours(emptyMap(), is247 = false, rawFallback = null, unknown = true)
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

    /** A parsed weekly schedule, plus whether we dropped seasonal (month-scoped) rules. */
    private data class Parsed(val schedule: Map<Int, List<TimeRange>>, val seasonal: Boolean)

    // Month abbreviations (1-based: Jan=1) — a rule that leads with one is season-scoped
    // (e.g. "May-Sep …", "Mar 01-Sep 30 …").
    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /**
     * Parse the spec into per-day time ranges (0=Mon … 6=Sun). Returns null only when
     * *nothing* usable survives; a spec with a parseable base week plus month-scoped
     * variants yields the base week with [Parsed.seasonal] set. Days with no entry are
     * closed. Public-holiday (PH) and school-holiday (SH) tokens are ignored (they're
     * not weekdays); `off` rules mark days closed by omission; a trailing `+` on a time
     * (open-ended) is treated as a plain closing time.
     *
     * Month-scoped rules are handled two ways: if a plain (non-month) base week exists,
     * the month rules are dropped and only [Parsed.seasonal] is flagged. But if the spec
     * is *entirely* season-scoped (e.g. summer/winter hours, no base week — common for
     * beer gardens and lakeside cafés), we render the season matching *today* as the
     * table rather than falling back to raw, still flagging it seasonal.
     */
    private fun parse(spec: String, now: Calendar = Calendar.getInstance()): Parsed? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (s == "24/7") return Parsed((0..6).associateWith { listOf(TimeRange(0, 24 * 60)) }, false)

        val month = now.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-based
        val byDay = HashMap<Int, MutableList<TimeRange>>()
        // Month rules matching today, held back so a plain base week wins if one exists.
        val inSeasonRules = mutableListOf<String>()
        var sawAny = false
        var seasonal = false
        for (rule in splitRules(s)) {
            val r = rule.trim()
            if (r.isEmpty()) continue
            val monthSpan = leadingMonthSpan(r)
            if (monthSpan != null) {
                seasonal = true
                // Keep the day/time part of rules whose month range covers today; used only
                // if no plain base week is found below.
                if (month in monthSpan.first) inSeasonRules.add(monthSpan.second)
                continue
            }
            val parsed = parseRuleRanges(r) ?: continue // skip unparsable / "off" rules
            if (parsed.second.isEmpty()) continue
            sawAny = true
            for (day in parsed.first) {
                byDay.getOrPut(day) { mutableListOf() }.addAll(parsed.second)
            }
        }
        // No plain base week, but we have this season's rules → build the table from those.
        if (!sawAny) {
            for (r in inSeasonRules) {
                val parsed = parseRuleRanges(r) ?: continue
                if (parsed.second.isEmpty()) continue
                sawAny = true
                for (day in parsed.first) {
                    byDay.getOrPut(day) { mutableListOf() }.addAll(parsed.second)
                }
            }
        }
        return if (sawAny) Parsed(byDay, seasonal) else null
    }

    /**
     * If [rule] begins with a month range (e.g. "May-Sep", "Mar 01-Sep 30", or a single
     * "Dec"), return the covered set of 1-based months paired with the rest of the rule
     * (the day/time part). Otherwise null. Day-of-month parts are ignored — month
     * granularity is enough to pick the right season for the table.
     */
    private fun leadingMonthSpan(rule: String): Pair<Set<Int>, String>? {
        val start = MONTHS.indexOf(rule.take(3)) // 0-based month, or -1
        if (start < 0) return null
        // Consume "Mmm[ dd][-Mmm[ dd]]" from the front; the remainder is the day/time part.
        // Find where the month scope ends: after an optional "-EndMonth[ dd]".
        var i = 3
        fun skipDayNum() {
            while (i < rule.length && rule[i] == ' ') i++
            while (i < rule.length && rule[i].isDigit()) i++
        }
        skipDayNum()
        var end = start
        if (i < rule.length && rule[i] == '-') {
            i++
            while (i < rule.length && rule[i] == ' ') i++
            val endMon = MONTHS.indexOf(rule.substring(i, minOf(i + 3, rule.length)))
            if (endMon < 0) return null // "Mar-<not a month>" — don't treat as month-scoped
            end = endMon
            i += 3
            skipDayNum()
        }
        val rest = rule.substring(i).trim()
        val months = buildSet {
            var m = start
            while (true) { add(m + 1); if (m == end) break; m = (m + 1) % 12 }
        }
        return months to rest
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
        if (days.isEmpty()) return null // e.g. "PH 09:00-13:00" — no weekday to place it on

        val ranges = mutableListOf<TimeRange>()
        for (range in timesPart.split(',')) {
            val tr = parseTimeRange(range.trim()) ?: return null
            ranges.add(tr)
        }
        return days to ranges
    }

    // Non-weekday day selectors we tolerate by ignoring: public/school holidays. A rule
    // scoped only to these yields an empty day set and is skipped, but their presence
    // alongside real weekdays (e.g. "Su,PH off") no longer sinks the whole spec.
    private val IGNORED_DAY_TOKENS = setOf("PH", "SH")

    /** Parse "Mo-Fr" / "Mo,We,Fr" / "Su,PH" into a set of day indices, or null. */
    private fun parseDays(part: String): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (token in part.split(',')) {
            val t = token.trim()
            if (t in IGNORED_DAY_TOKENS) continue
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
        // Tolerate an open-ended "+" suffix (e.g. "17:00+") as a plain time.
        val p = v.trim().trimEnd('+').trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun hhmm(min: Int): String {
        // A close at exactly end-of-day reads better as "24:00" than "00:00".
        if (min == 24 * 60) return "24:00"
        val m = min % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
