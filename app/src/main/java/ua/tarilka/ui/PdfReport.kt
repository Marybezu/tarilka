package ua.tarilka.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import ua.tarilka.R
import ua.tarilka.data.Metric
import ua.tarilka.data.Metric.Key
import ua.tarilka.data.Sex
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What a PDF report can contain. */
enum class ReportPart { SUMMARY, FOOD, REASONS, FLUID, WEIGHT, SLEEP, ACTIVITY, BOWEL, CYCLE, NOTES }

object ReportPresets {
    val DIETITIAN = setOf(ReportPart.SUMMARY, ReportPart.FOOD, ReportPart.REASONS, ReportPart.FLUID, ReportPart.WEIGHT, ReportPart.NOTES)
    val CYCLE = setOf(ReportPart.CYCLE)
    val ALL = ReportPart.entries.toSet()
}

/**
 * A plain, readable A4 report made on the phone with Android's own PdfDocument (no libraries, no internet):
 * the main points and one line per measure with a small chart, then day by day (up to 31 days)
 * or one line per day for longer stretches.
 */
object PdfReport {
    private const val DETAIL_DAYS = 31

    private fun partOf(k: Key): ReportPart = when (k) {
        Key.FLUID -> ReportPart.FLUID
        Key.WEIGHT -> ReportPart.WEIGHT
        Key.SLEEP -> ReportPart.SLEEP
        Key.STEPS, Key.ACTIVITY -> ReportPart.ACTIVITY
        Key.BOWEL -> ReportPart.BOWEL
        Key.REASONS -> ReportPart.REASONS
        else -> ReportPart.FOOD
    }

    fun write(c: Context, out: OutputStream, data: MainViewModel.ReportData, parts: Set<ReportPart>, withProfile: Boolean, withTimes: Boolean) {
        val pdf = Pdf(c)
        try {
            draw(c, pdf, data, parts, withProfile, withTimes)
            pdf.doc.writeTo(out)
        } finally {
            pdf.doc.close()
        }
    }

    private fun draw(c: Context, pdf: Pdf, data: MainViewModel.ReportData, parts: Set<ReportPart>, withProfile: Boolean, withTimes: Boolean) {
        val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        val nDays = (data.to.toEpochDay() - data.from.toEpochDay() + 1).toInt()

        // ---- title ----
        pdf.text(c.getString(R.string.pdf_title), pdf.h1)
        pdf.text(
            c.getString(R.string.pdf_period, data.from.format(dateFmt), data.to.format(dateFmt), nDays) + " · " +
                c.getString(R.string.pdf_created, java.time.LocalDate.now().format(dateFmt)),
            pdf.small,
        )
        if (withProfile) {
            val p = data.profile
            val t = data.targets
            pdf.text(
                c.getString(
                    R.string.pdf_profile,
                    c.getString(if (p.sex == Sex.FEMALE) R.string.female else R.string.male), p.age, p.heightCm,
                    (data.days.lastOrNull { it.weight != null }?.weight?.kg ?: p.weightKg).f1(),
                    if (t.showKcal) t.kcal.f0() else "—", t.protein.f0(), (t.waterMl / 1000).f1(),
                ),
                pdf.body,
            )
        }
        pdf.space(8f)

        // ---- overview: big numbers and charts ----
        val logged = data.days.filter { it.entries.isNotEmpty() }
        val t = data.targets
        if (logged.isNotEmpty() && (ReportPart.FOOD in parts || ReportPart.FLUID in parts || ReportPart.WEIGHT in parts)) {
            pdf.heading(c.getString(R.string.pdf_overview))
            fun tint(v: Double, target: Double, good: Double, ok: Double) =
                levelColor(if (v >= target * good) ua.tarilka.data.Habits.Level.GOOD else if (v >= target * ok) ua.tarilka.data.Habits.Level.OK else ua.tarilka.data.Habits.Level.WATCH).toArgb()
            val tiles = buildList {
                if (ReportPart.FOOD in parts) {
                    if (t.showKcal) {
                        val k = logged.map { d -> d.entries.sumOf { it.kcal } }.average()
                        val kLevel = kotlin.math.abs(k - t.kcal).let { if (it <= t.kcal * 0.1) ua.tarilka.data.Habits.Level.GOOD else if (it <= t.kcal * 0.25) ua.tarilka.data.Habits.Level.OK else ua.tarilka.data.Habits.Level.WATCH }
                        add(Triple(c.getString(R.string.pdf_tile_kcal), k.f0() + "\n" + c.getString(R.string.pdf_of, t.kcal.f0() + " " + c.getString(R.string.unit_kcal)), levelColor(kLevel).toArgb()))
                    }
                    val pr = logged.map { d -> d.entries.sumOf { it.protein } }.average()
                    add(Triple(c.getString(R.string.pdf_tile_protein), pr.f0() + " " + c.getString(R.string.unit_g) + "\n" + c.getString(R.string.pdf_of, t.protein.f0() + " " + c.getString(R.string.unit_g)), tint(pr, t.protein, 0.9, 0.7)))
                }
                if (ReportPart.FLUID in parts) {
                    data.days.map { it.fluidMl }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.let { f ->
                        add(Triple(c.getString(R.string.pdf_tile_fluid), (f / 1000).f1() + " " + c.getString(R.string.unit_l) + "\n" + c.getString(R.string.pdf_of, (t.waterMl / 1000).f1() + " " + c.getString(R.string.unit_l)), tint(f, t.waterMl, 0.9, 0.7)))
                    }
                }
                add(Triple(c.getString(R.string.pdf_tile_days), "${logged.size}\n" + c.getString(R.string.pdf_of, nDays.toString()), 0xFF4E6E58.toInt()))
            }
            pdf.tiles(tiles)
            if (ReportPart.FOOD in parts && t.showKcal) {
                // Calories: a bar a day up to a month, otherwise the summary's stretches.
                val kcalMetric = data.summary.metrics.firstOrNull { it.key == Key.KCAL }
                if (nDays <= DETAIL_DAYS) {
                    val labelFmt = DateTimeFormatter.ofPattern(if (nDays <= 7) "EEE d" else "d", c.resources.configuration.locales[0])
                    pdf.barChart(
                        c.getString(R.string.pdf_chart_kcal),
                        data.days.map { d -> d.entries.sumOf { it.kcal }.takeIf { it > 0 } },
                        data.days.map { it.date.format(labelFmt) }, t.kcal, 0xFF8FC9A0.toInt(),
                    )
                } else if (kcalMetric != null) {
                    pdf.barChart(c.getString(R.string.pdf_chart_kcal_avg), kcalMetric.series, data.summary.barLabels, t.kcal, 0xFF8FC9A0.toInt())
                }
                val p = logged.sumOf { d -> d.entries.sumOf { it.protein } } * 4
                val f = logged.sumOf { d -> d.entries.sumOf { it.fat } } * 9
                val cb = logged.sumOf { d -> d.entries.sumOf { it.carbs } } * 4
                pdf.stackedBar(
                    c.getString(R.string.pdf_chart_macros),
                    listOf(
                        Triple(c.getString(R.string.protein), p, 0xFFE0857A.toInt()),
                        Triple(c.getString(R.string.fat), f, 0xFFE3B14D.toInt()),
                        Triple(c.getString(R.string.carbs), cb, 0xFF7FA6DC.toInt()),
                    ),
                    c.getString(R.string.pdf_macros_note),
                )
            }
            if (ReportPart.WEIGHT in parts) {
                val pts = data.days.mapNotNull { d -> d.weight?.let { d.date.toEpochDay().toDouble() to it.kg } }
                pdf.lineChart(c.getString(R.string.m_weight) + ", " + c.getString(R.string.kg), pts, data.from.format(dateFmt) to data.to.format(dateFmt))
            }
            pdf.space(6f)
        }

        // ---- the main points and the measures ----
        val metrics = data.summary.metrics.filter { partOf(it.key) in parts }
        if (ReportPart.SUMMARY in parts && metrics.isNotEmpty()) {
            pdf.heading(c.getString(R.string.report_main))
            highlightLines(c, metrics, ua.tarilka.data.Report.highlights(metrics)).forEach { pdf.text("• $it", pdf.body) }
            if (ReportPart.FOOD in parts) {
                if (data.summary.advice.change.isNotEmpty()) {
                    pdf.space(4f)
                    pdf.text(c.getString(R.string.advice_change), pdf.bold)
                    data.summary.advice.change.forEach { pdf.text("• " + adviceText(c, it), pdf.body) }
                }
                data.summary.advice.good?.let {
                    pdf.text(c.getString(R.string.advice_good), pdf.bold)
                    pdf.text("• " + adviceText(c, it), pdf.body)
                }
            }
            pdf.space(6f)
            pdf.labels(data.summary.barLabels)
            metrics.forEach { m ->
                pdf.metric(m, metricName(c, m.key) + ": " + metricValue(c, m), metricChange(c, m))
            }
            pdf.endLabels()
            pdf.text(c.getString(R.string.report_bars_hint), pdf.small)
            pdf.space(8f)
        }

        // ---- cycle ----
        if (ReportPart.CYCLE in parts && (data.periods.isNotEmpty() || data.cycle?.lastStart != null)) {
            pdf.heading(c.getString(R.string.cycle_title))
            data.cycle?.let { ci ->
                ci.avgLength?.let { pdf.text(c.getString(R.string.cycle_avg, it, ci.lengths.size), pdf.body) }
                ci.nextStart?.let { pdf.text(c.getString(R.string.cycle_next, shortDate(it)), pdf.body) }
                if (ci.irregular) pdf.text(c.getString(R.string.cycle_irregular), pdf.body)
                if (ci.longGap) pdf.text(c.getString(R.string.cycle_long_gap), pdf.body)
                if (ci.seeDoctor) {
                    val why = buildList {
                        if (ci.tooShortOrLong) add(c.getString(R.string.cycle_flag_length))
                        if (ci.longBleeding) add(c.getString(R.string.cycle_flag_long))
                        if (ci.heavyOrPainful) add(c.getString(R.string.cycle_flag_heavy))
                    }.joinToString(" ")
                    pdf.text(c.getString(R.string.cycle_doctor, why), pdf.body)
                }
            }
            val flows = c.resources.getStringArray(R.array.cycle_flows)
            val pains = c.resources.getStringArray(R.array.cycle_pains)
            data.periods.sortedBy { it.start }.forEach { p ->
                val days = data.days.filter { d -> d.periodDay != null && d.date.toEpochDay() in p.start..(p.end ?: (p.start + 9)) }.mapNotNull { it.periodDay }
                val flow = days.mapNotNull { it.flow }.maxOrNull()?.let { flows.getOrNull(it) }
                val pain = days.mapNotNull { it.pain }.maxOrNull()?.let { pains.getOrNull(it) }
                pdf.text(
                    "• " + shortDate(p.start) + (p.end?.let { " – " + shortDate(it) + " (" + c.getString(R.string.pdf_days_n, (it - p.start + 1).toInt()) + ")" } ?: "") +
                        listOfNotNull(flow?.let { c.getString(R.string.pdf_flow, it) }, pain?.let { c.getString(R.string.pdf_pain, it) }).joinToString(prefix = if (flow != null || pain != null) " · " else "", separator = " · "),
                    pdf.body,
                )
            }
            pdf.text(c.getString(R.string.cycle_disclaimer), pdf.small)
            pdf.space(8f)
        }

        // ---- day by day ----
        val dayParts = parts - ReportPart.SUMMARY - ReportPart.CYCLE
        val days = data.days.filter { d ->
            (ReportPart.FOOD in parts && d.entries.isNotEmpty()) ||
                (ReportPart.FLUID in parts && d.fluidMl > 0) || (ReportPart.WEIGHT in parts && d.weight != null) ||
                (ReportPart.SLEEP in parts && d.sleep != null) || (ReportPart.ACTIVITY in parts && (d.steps != null || d.workouts.isNotEmpty())) ||
                (ReportPart.BOWEL in parts && d.bowel != null) || (ReportPart.NOTES in parts && !d.note.isNullOrBlank()) ||
                (ReportPart.CYCLE in parts && (d.inPeriod || d.periodDay != null))
        }
        // Over a month the days are a table, which has no cycle column: cycle alone then has no day section.
        if (days.isNotEmpty() && (dayParts.isNotEmpty() || (ReportPart.CYCLE in parts && nDays <= DETAIL_DAYS))) {
            pdf.heading(c.getString(R.string.pdf_by_day))
            val dayFmt = DateTimeFormatter.ofPattern("EEE, d.MM.yyyy", c.resources.configuration.locales[0])
            if (nDays > DETAIL_DAYS) {
                // One line per day, only for days with something in its columns.
                val tableDays = days.filter { d ->
                    (ReportPart.FOOD in parts && d.entries.isNotEmpty()) || (ReportPart.FLUID in parts && d.fluidMl > 0) ||
                        (ReportPart.WEIGHT in parts && d.weight != null) || (ReportPart.SLEEP in parts && d.sleep != null) ||
                        (ReportPart.ACTIVITY in parts && d.steps != null)
                }
                val cols = buildList {
                    add(c.getString(R.string.pdf_col_date) to 1.4f)
                    if (ReportPart.FOOD in parts) { add(c.getString(R.string.unit_kcal) to 0.9f); add(c.getString(R.string.pdf_col_protein) to 0.9f) }
                    if (ReportPart.FLUID in parts) add(c.getString(R.string.m_fluid) to 0.9f)
                    if (ReportPart.WEIGHT in parts) add(c.getString(R.string.m_weight) to 0.9f)
                    if (ReportPart.SLEEP in parts) add(c.getString(R.string.m_sleep) to 0.8f)
                    if (ReportPart.ACTIVITY in parts) add(c.getString(R.string.m_steps) to 0.9f)
                }
                if (tableDays.isNotEmpty() && cols.size > 1) pdf.row(cols.map { it.first }, cols.map { it.second }, pdf.bold)
                tableDays.takeIf { cols.size > 1 }.orEmpty().forEach { d ->
                    val cells = buildList {
                        add(d.date.format(DateTimeFormatter.ofPattern("d.MM.yyyy")))
                        if (ReportPart.FOOD in parts) {
                            add(d.entries.sumOf { it.kcal }.takeIf { it > 0 }?.f0() ?: "—")
                            add(d.entries.sumOf { it.protein }.takeIf { it > 0 }?.f0() ?: "—")
                        }
                        if (ReportPart.FLUID in parts) add(if (d.fluidMl > 0) (d.fluidMl / 1000).f1() else "—")
                        if (ReportPart.WEIGHT in parts) add(d.weight?.kg?.f1() ?: "—")
                        if (ReportPart.SLEEP in parts) add(d.sleep?.f1() ?: "—")
                        if (ReportPart.ACTIVITY in parts) add(d.steps?.toString() ?: "—")
                    }
                    pdf.row(cells, cols.map { it.second }, pdf.body, striped = true)
                }
                // Notes do not fit a table: listed after it.
                if (ReportPart.NOTES in parts) {
                    days.filter { !it.note.isNullOrBlank() }.forEach { d ->
                        pdf.text(d.date.format(DateTimeFormatter.ofPattern("d.MM.yyyy")) + " — " + d.note, pdf.body)
                    }
                }
                pdf.text(c.getString(R.string.pdf_compact_hint), pdf.small)
            } else {
                val reasons = REASON_NAMES.map { c.getString(it) }
                val bowelForms = c.resources.getStringArray(R.array.bowel_forms)
                val types = c.resources.getStringArray(R.array.workout_types)
                val levels = c.resources.getStringArray(R.array.workout_intensity)
                val flows = c.resources.getStringArray(R.array.cycle_flows)
                val pains = c.resources.getStringArray(R.array.cycle_pains)
                val meals = listOf(R.string.meal_breakfast, R.string.meal_lunch, R.string.meal_dinner, R.string.meal_snack).map { c.getString(it) }
                val kcalUnit = c.getString(R.string.unit_kcal)
                val g = c.getString(R.string.unit_g)
                days.forEach { d ->
                    val dayKcal = d.entries.sumOf { it.kcal }
                    pdf.dayTitle(d.date.format(dayFmt).replaceFirstChar { it.uppercase() }, if (ReportPart.FOOD in parts && dayKcal > 0) "${dayKcal.f0()} $kcalUnit" else null)
                    if (ReportPart.FOOD in parts && d.entries.isNotEmpty()) {
                        d.entries.groupBy { it.meal }.toSortedMap().forEach { (meal, list) ->
                            val at = if (withTimes && meal != 3) " · " + java.time.Instant.ofEpochMilli(list.first().createdAt).atZone(zone).toLocalTime().format(time) else ""
                            pdf.twoSides(meals.getOrElse(meal) { "" } + at, "${list.sumOf { it.kcal }.f0()} $kcalUnit", pdf.bold)
                            list.forEach { e ->
                                val tm = if (withTimes && meal == 3) java.time.Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalTime().format(time) + "  " else ""
                                pdf.row(
                                    listOf(tm + (if (e.fromAi) "✦ " else "") + e.name(), "${e.grams.f0()} $g", "${e.kcal.f0()} $kcalUnit"),
                                    listOf(4.2f, 1f, 1f), pdf.body, indent = 10f,
                                )
                            }
                            pdf.text(
                                "${c.getString(R.string.short_protein)} ${list.sumOf { it.protein }.f0()} $g · ${c.getString(R.string.short_fat)} ${list.sumOf { it.fat }.f0()} $g · " +
                                    "${c.getString(R.string.short_carbs)} ${list.sumOf { it.carbs }.f0()} $g",
                                pdf.small, indent = 10f,
                            )
                            if (ReportPart.REASONS in parts) {
                                list.firstOrNull { it.reason != null || it.hunger != null || it.hardToStop }?.let { e ->
                                    val why = listOfNotNull(
                                        e.reason?.let { reasons.getOrNull(it) },
                                        e.hunger?.let { c.getString(R.string.pdf_hunger, it) },
                                        if (e.hardToStop) c.getString(R.string.pdf_hard_to_stop) else null,
                                    ).joinToString(" · ")
                                    pdf.text(c.getString(R.string.pdf_why, why), pdf.small, indent = 10f)
                                }
                            }
                        }
                        val pr = d.entries.sumOf { it.protein }
                        val fi = d.entries.sumOf { it.fiber }
                        pdf.space(2f)
                        if (data.targets.showKcal) pdf.progress(dayKcal, data.targets.kcal, c.getString(R.string.pdf_of_kcal, dayKcal.f0(), data.targets.kcal.f0()))
                        pdf.text(c.getString(R.string.pdf_day_rest, pr.f0(), data.targets.protein.f0(), fi.f0(), data.targets.fiber.f0()), pdf.small)
                    }
                    val lines = buildList {
                        if (ReportPart.FLUID in parts && d.fluidMl > 0) add(c.getString(R.string.mv_litres, (d.fluidMl / 1000).f1(), (data.targets.waterMl / 1000).f1()).let { c.getString(R.string.m_fluid) + ": " + it })
                        if (ReportPart.WEIGHT in parts) d.weight?.let { w -> add(c.getString(R.string.m_weight) + ": " + w.kg.f1() + " " + c.getString(R.string.kg) + (w.minutes?.let { m -> " · %02d:%02d".format(m / 60, m % 60) } ?: "")) }
                        if (ReportPart.SLEEP in parts) d.sleep?.let { add(c.getString(R.string.m_sleep) + ": " + it.f1() + " " + c.getString(R.string.unit_h)) }
                        if (ReportPart.ACTIVITY in parts) {
                            d.steps?.let { add(c.getString(R.string.m_steps) + ": " + it) }
                            d.workouts.forEach { w -> add(types.getOrElse(w.type) { "" } + ": " + c.getString(R.string.minutes_n, w.minutes) + " · " + levels.getOrElse(w.intensity) { "" }) }
                        }
                        if (ReportPart.BOWEL in parts) d.bowel?.let { b ->
                            add(c.getString(R.string.m_bowel) + ": " + c.getString(R.string.pdf_times_n, b.count) + (b.form?.let { " · " + bowelForms.getOrElse(it) { "" } } ?: ""))
                        }
                        if (ReportPart.CYCLE in parts && (d.inPeriod || d.periodDay != null)) {
                            add(c.getString(R.string.pdf_period_day) + listOfNotNull(
                                d.periodDay?.flow?.let { c.getString(R.string.pdf_flow, flows.getOrElse(it) { "" }) },
                                d.periodDay?.pain?.let { c.getString(R.string.pdf_pain, pains.getOrElse(it) { "" }) },
                            ).joinToString(prefix = " · ", separator = " · ").takeIf { d.periodDay != null }.orEmpty())
                        }
                        if (ReportPart.NOTES in parts && !d.note.isNullOrBlank()) add(c.getString(R.string.pdf_note, d.note))
                    }
                    lines.forEach { pdf.text(it, pdf.body) }
                    pdf.space(6f)
                }
            }
        }

        pdf.space(10f)
        pdf.text(c.getString(R.string.pdf_footer), pdf.small)
        pdf.finish()
    }
}

/** A small page-flowing writer: text, rows, headings and bar charts, with automatic page breaks and page numbers. */
private class Pdf(c: Context) {
    val doc = PdfDocument()
    private val w = 595
    private val h = 842
    private val m = 40f
    private val width = w - 2 * m
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var n = 0

    private val ink = 0xFF222222.toInt()
    private val grey = 0xFF6B6B6B.toInt()
    private val accent = 0xFF4E6E58.toInt()
    // Sizes for reading on paper or a phone: body 12 pt, notes 10 pt.
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = ink }
    val bold = TextPaint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val small = TextPaint(body).apply { textSize = 10f; color = grey }
    val h1 = TextPaint(bold).apply { textSize = 24f; color = accent }
    private val h2 = TextPaint(bold).apply { textSize = 16f; color = accent }
    private val day = TextPaint(bold).apply { textSize = 13f; color = Color.WHITE }
    private val big = TextPaint(bold).apply { textSize = 20f }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEF2EE.toInt() }
    private val pageNo = c.getString(R.string.pdf_page)

    private fun newPage() {
        finish()
        n++
        page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
        canvas = page!!.canvas
        y = m
    }

    fun finish() {
        val p = page ?: return
        canvas.drawText("$pageNo $n", w - m - small.measureText("$pageNo $n"), h - 20f, small)
        doc.finishPage(p)
        page = null
    }

    private fun ensure(height: Float) {
        if (page == null || y + height > h - m) newPage()
    }

    fun space(dy: Float) { if (page != null) y += dy }

    fun text(s: String, paint: TextPaint, indent: Float = 0f) {
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, (width - indent).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
        // Long text can run over pages line by line.
        var line = 0
        while (line < layout.lineCount) {
            ensure(layout.getLineBottom(line) - layout.getLineTop(line).toFloat())
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && y + (layout.getLineBottom(last + 1) - top) <= h - m) last++
            canvas.save()
            canvas.translate(m + indent, y - top)
            canvas.clipRect(0f, top.toFloat(), width, layout.getLineBottom(last).toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.getLineBottom(last) - top
            line = last + 1
        }
        y += 2f
    }

    fun heading(s: String) {
        ensure(40f)
        y += 6f
        text(s, h2)
        canvas.drawLine(m, y, w - m, y, Paint().apply { color = 0xFFD5DDD6.toInt(); strokeWidth = 0.8f })
        y += 6f
    }

    /** A coloured band with the date on the left and the day's total on the right. */
    fun dayTitle(s: String, right: String? = null) {
        ensure(70f)
        y += 8f
        canvas.drawRoundRect(RectF(m, y, w - m, y + 22f), 4f, 4f, band)
        canvas.drawText(s, m + 8f, y + 15.5f, day)
        right?.let { canvas.drawText(it, w - m - 8f - day.measureText(it), y + 15.5f, day) }
        y += 28f
    }

    /** "Breakfast · 08:30" on the left, its calories on the right. */
    fun twoSides(left: String, right: String, paint: TextPaint) {
        val lineH = paint.textSize * 1.55f
        ensure(lineH)
        canvas.drawText(TextUtils.ellipsize(left, paint, width * 0.7f, TextUtils.TruncateAt.END).toString(), m, y + paint.textSize, paint)
        canvas.drawText(right, w - m - paint.measureText(right), y + paint.textSize, paint)
        y += lineH
    }

    /** A thin bar: how much of the target, never red (over the target is amber). */
    fun progress(value: Double, target: Double, label: String) {
        ensure(30f)
        val frac = if (target > 0) (value / target).coerceIn(0.0, 1.3) else 0.0
        val trackW = width * 0.5f
        canvas.drawRoundRect(RectF(m, y + 4f, m + trackW, y + 12f), 4f, 4f, soft)
        val col = if (frac > 1.1) 0xFFFFB300.toInt() else 0xFF43A047.toInt()
        canvas.drawRoundRect(RectF(m, y + 4f, m + trackW * (frac / 1.3).toFloat(), y + 12f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        val tx = m + trackW / 1.3f
        canvas.drawLine(tx, y + 1f, tx, y + 15f, Paint().apply { color = grey; strokeWidth = 1f })
        canvas.drawText(TextUtils.ellipsize(label, bold, width - trackW - 12f, TextUtils.TruncateAt.END).toString(), m + trackW + 10f, y + 13f, bold)
        y += 22f
    }

    /** Big numbers in boxes: what matters at a glance. Value may have a second line after "\n". */
    fun tiles(items: List<Triple<String, String, Int>>) {
        if (items.isEmpty()) return
        val tileH = 62f
        ensure(tileH + 8f)
        val gap = 8f
        val tw = (width - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (title, value, color) ->
            val x = m + i * (tw + gap)
            canvas.drawRoundRect(RectF(x, y, x + tw, y + tileH), 6f, 6f, soft)
            canvas.drawRect(RectF(x, y, x + 4f, y + tileH), Paint().apply { this.color = color })
            canvas.drawText(TextUtils.ellipsize(title, small, tw - 14f, TextUtils.TruncateAt.END).toString(), x + 10f, y + 16f, small)
            val lines = value.split("\n")
            canvas.drawText(TextUtils.ellipsize(lines[0], big, tw - 14f, TextUtils.TruncateAt.END).toString(), x + 10f, y + 40f, big)
            lines.getOrNull(1)?.let { canvas.drawText(TextUtils.ellipsize(it, small, tw - 14f, TextUtils.TruncateAt.END).toString(), x + 10f, y + 55f, small) }
        }
        y += tileH + 10f
    }

    /** A titled bar chart: one bar per day (or stretch), the target as a dashed line, labels under it. */
    fun barChart(title: String, values: List<Double?>, labels: List<String>, target: Double?, color: Int) {
        if (values.isEmpty()) return
        val chartH = 110f
        ensure(chartH + 50f)
        text(title, bold)
        y += 2f
        val top = y + 10f
        val max = ((values.filterNotNull() + listOfNotNull(target)).maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.1
        val slot = width / values.size
        val bw = slot * 0.65f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawLine(m, top + chartH, m + width, top + chartH, Paint().apply { this.color = 0xFFCCCCCC.toInt(); strokeWidth = 0.8f })
        values.forEachIndexed { i, v ->
            val x = m + i * slot + (slot - bw) / 2
            if (v != null && v > 0) {
                val bh = (v / max * chartH).toFloat()
                canvas.drawRoundRect(RectF(x, top + chartH - bh, x + bw, top + chartH), 2f, 2f, paint)
                if (values.size <= 14) {
                    val t = v.f0()
                    canvas.drawText(t, x + (bw - small.measureText(t)) / 2, top + chartH - bh - 3f, small)
                }
            }
        }
        target?.let { t ->
            val ty = top + chartH - (t / max * chartH).toFloat()
            canvas.drawLine(m, ty, m + width, ty, Paint().apply { this.color = grey; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
        }
        val step = when { labels.size <= 16 -> 1; labels.size <= 32 -> 2; else -> (labels.size / 12).coerceAtLeast(1) }
        labels.forEachIndexed { i, l ->
            if (i % step == 0) canvas.drawText(l, m + i * slot + (slot - small.measureText(l)) / 2, top + chartH + 13f, small)
        }
        y = top + chartH + 22f
    }

    /** Where the calories came from: protein, fat and carbohydrates as one bar of 100 %. */
    fun stackedBar(title: String, parts: List<Triple<String, Double, Int>>, note: String) {
        val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
        ensure(70f)
        text(title, bold)
        var x = m
        val barH = 20f
        val label = TextPaint(small).apply { color = Color.WHITE }
        parts.forEach { (name, v, color) ->
            val pw = (v / total * width).toFloat()
            canvas.drawRect(RectF(x, y, x + pw, y + barH), Paint().apply { this.color = color })
            val t = "$name ${(v / total * 100).f0()}%"
            if (label.measureText(t) < pw - 6f) canvas.drawText(t, x + 4f, y + 14f, label)
            x += pw
        }
        y += barH + 4f
        text(note, small)
    }

    /** A simple line for weight, from just below the lowest value. */
    fun lineChart(title: String, points: List<Pair<Double, Double>>, xLabels: Pair<String, String>) {
        if (points.size < 2) return
        val chartH = 90f
        ensure(chartH + 50f)
        text(title, bold)
        val top = y + 10f
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }.coerceAtLeast(minX + 1)
        val minY = points.minOf { it.second } - 0.5
        val maxY = points.maxOf { it.second } + 0.5
        fun px(v: Double) = m + ((v - minX) / (maxX - minX) * (width - 20f)).toFloat() + 10f
        fun py(v: Double) = top + chartH - ((v - minY) / (maxY - minY) * chartH).toFloat()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2f; style = Paint.Style.STROKE }
        val path = android.graphics.Path()
        points.forEachIndexed { i, (a, b) -> if (i == 0) path.moveTo(px(a), py(b)) else path.lineTo(px(a), py(b)) }
        canvas.drawPath(path, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        points.forEach { (a, b) -> canvas.drawCircle(px(a), py(b), 2.5f, dot) }
        val first = points.first().second.f1()
        val last = points.last().second.f1()
        canvas.drawText(first, px(points.first().first), py(points.first().second) - 6f, small)
        canvas.drawText(last, px(points.last().first) - small.measureText(last), py(points.last().second) - 6f, small)
        canvas.drawText(xLabels.first, m, top + chartH + 13f, small)
        canvas.drawText(xLabels.second, m + width - small.measureText(xLabels.second), top + chartH + 13f, small)
        y = top + chartH + 22f
    }

    private var zebra = false

    fun row(cells: List<String>, weights: List<Float>, paint: TextPaint, indent: Float = 0f, striped: Boolean = false) {
        val lineH = paint.textSize * 1.6f
        ensure(lineH)
        if (striped) {
            zebra = !zebra
            if (zebra) canvas.drawRect(RectF(m + indent - 2f, y, w - m, y + lineH), soft)
        }
        val total = weights.sum()
        var x = m + indent
        val avail = width - indent
        cells.forEachIndexed { i, cell ->
            val cw = avail * weights[i] / total
            val shown = TextUtils.ellipsize(cell, paint, cw - 6f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, x, y + paint.textSize, paint)
            x += cw
        }
        y += lineH
    }

    /** The bar labels; drawn again at the top of each new page while the measures continue. */
    private var currentLabels: List<String> = emptyList()

    fun labels(labels: List<String>) {
        currentLabels = labels
        drawLabels()
    }

    fun endLabels() { currentLabels = emptyList() }

    private fun drawLabels() {
        val labels = currentLabels
        if (labels.isEmpty() || labels.size > 31) return
        ensure(14f)
        val slot = width / labels.size
        labels.forEachIndexed { i, l ->
            if (labels.size <= 12 || i % 2 == 0) canvas.drawText(l, m + i * slot + (slot - small.measureText(l)) / 2, y + 8f, small)
        }
        y += 12f
    }

    /** One measure: coloured dot with name and value (wrapped, nothing cut), the change, then its bars at full width. */
    fun metric(mtr: Metric, title: String, change: String?) {
        val chartH = 22f
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, bold, (width - 12f).toInt()).build()
        val changeLayout = change?.let { StaticLayout.Builder.obtain(it, 0, it.length, small, (width - 12f).toInt()).build() }
        val need = titleLayout.height + (changeLayout?.height ?: 0) + chartH + 10f
        val before = n
        ensure(need)
        if (n != before && currentLabels.isNotEmpty()) { drawLabels(); ensure(need) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = levelColor(mtr.level).toArgb() }
        canvas.drawCircle(m + 4f, y + 7f, 3.5f, dot)
        canvas.save(); canvas.translate(m + 12f, y); titleLayout.draw(canvas); canvas.restore()
        y += titleLayout.height
        changeLayout?.let { canvas.save(); canvas.translate(m + 12f, y); it.draw(canvas); canvas.restore(); y += it.height }
        y += 3f
        // Bars across the full width, lined up with the labels.
        val values = mtr.series.filterNotNull()
        val base = if (mtr.key == Key.WEIGHT) (values.minOrNull() ?: 0.0) - 1.0 else 0.0
        val max = ((values + listOfNotNull(mtr.seriesTarget)).maxOrNull()?.takeIf { it > base } ?: (base + 1.0)) - base
        val slot = width / mtr.series.size.coerceAtLeast(1)
        val barW = slot * 0.6f
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = levelColor(mtr.level).copy(alpha = 0.7f).toArgb() }
        val empty = Paint().apply { color = 0xFFDDDDDD.toInt() }
        mtr.series.forEachIndexed { i, v ->
            val x = m + i * slot + (slot - barW) / 2
            if (v == null) {
                canvas.drawRect(x, y + chartH - 1.5f, x + barW, y + chartH, empty)
            } else {
                val bh = ((v - base) / max * chartH).toFloat().coerceAtLeast(1.5f)
                canvas.drawRoundRect(RectF(x, y + chartH - bh, x + barW, y + chartH), 1.5f, 1.5f, bar)
            }
        }
        mtr.seriesTarget?.let { t ->
            val ty = y + chartH - ((t - base) / max * chartH).toFloat()
            canvas.drawLine(m, ty, m + width, ty, Paint().apply { color = grey; strokeWidth = 0.6f; pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f) })
        }
        y += chartH + 6f
    }
}
