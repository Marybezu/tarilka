package ua.tarilka.ui

import android.content.Context
import android.graphics.Canvas
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
                    t.kcal.f0(), t.protein.f0(), (t.waterMl / 1000).f1(),
                ),
                pdf.body,
            )
        }
        pdf.space(8f)

        // ---- the main points and the measures ----
        val metrics = data.summary.metrics.filter { partOf(it.key) in parts }
        if (ReportPart.SUMMARY in parts && metrics.isNotEmpty()) {
            pdf.heading(c.getString(R.string.report_main))
            highlightLines(c, metrics, ua.tarilka.data.Report.highlights(metrics)).forEach { pdf.text("• $it", pdf.body) }
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
        if (days.isNotEmpty() && (dayParts.isNotEmpty() || ReportPart.CYCLE in parts)) {
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
                    pdf.row(cells, cols.map { it.second }, pdf.body)
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
                days.forEach { d ->
                    pdf.dayTitle(d.date.format(dayFmt).replaceFirstChar { it.uppercase() })
                    if (ReportPart.FOOD in parts && d.entries.isNotEmpty()) {
                        d.entries.groupBy { it.meal }.toSortedMap().forEach { (meal, list) ->
                            val at = if (withTimes && meal != 3) " · " + java.time.Instant.ofEpochMilli(list.first().createdAt).atZone(zone).toLocalTime().format(time) else ""
                            pdf.text(meals.getOrElse(meal) { "" } + at, pdf.bold)
                            list.forEach { e ->
                                val t = if (withTimes && meal == 3) java.time.Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalTime().format(time) + "  " else ""
                                pdf.row(
                                    listOf(t + (if (e.fromAi) "✦ " else "") + e.name(), "${e.grams.f0()} ${c.getString(R.string.unit_g)}", "${e.kcal.f0()} ${c.getString(R.string.unit_kcal)}",
                                        "${c.getString(R.string.short_protein)} ${e.protein.f0()} ${c.getString(R.string.short_fat)} ${e.fat.f0()} ${c.getString(R.string.short_carbs)} ${e.carbs.f0()}"),
                                    listOf(3.2f, 0.8f, 0.9f, 1.6f), pdf.body, indent = 10f,
                                )
                            }
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
                        val k = d.entries.sumOf { it.kcal }
                        val pr = d.entries.sumOf { it.protein }
                        val fi = d.entries.sumOf { it.fiber }
                        pdf.text(c.getString(R.string.pdf_day_total, k.f0(), data.targets.kcal.f0(), pr.f0(), data.targets.protein.f0(), fi.f0(), data.targets.fiber.f0()), pdf.bold)
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
        pdf.doc.writeTo(out)
        pdf.doc.close()
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
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = ink }
    val bold = TextPaint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val small = TextPaint(body).apply { textSize = 8.5f; color = grey }
    val h1 = TextPaint(bold).apply { textSize = 20f; color = accent }
    private val h2 = TextPaint(bold).apply { textSize = 13f; color = accent }
    private val day = TextPaint(bold).apply { textSize = 11f }
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

    fun dayTitle(s: String) {
        ensure(60f)
        y += 4f
        text(s, day)
    }

    fun row(cells: List<String>, weights: List<Float>, paint: TextPaint, indent: Float = 0f) {
        val lineH = paint.textSize * 1.5f
        ensure(lineH)
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
