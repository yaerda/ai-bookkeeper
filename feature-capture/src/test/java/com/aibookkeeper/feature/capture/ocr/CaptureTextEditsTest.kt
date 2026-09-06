package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.model.ExtractionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CaptureTextEditsTest {
    private val lines = listOf("午餐 ¥26.00", "公交 ¥3.00", "退款 ¥50.00")
    private val items = listOf(
        item(26.0, "EXPENSE", "餐饮", "午餐", "2026-09-01"),
        item(3.0, "EXPENSE", "交通", "公交", "2026-09-02"),
        item(50.0, "INCOME", "退款", "退款", "2026-09-03")
    )
    private val summary = items.first().copy(amount = 79.0, date = "2026-09-06", note = "账单")

    @Test
    fun `deleting the middle line removes its item without shifting metadata`() {
        val result = edit("午餐 ¥26.00\n退款 ¥50.00")

        assertEquals(listOf(items[0], items[2]), result.items)
        assertEquals(listOf(lines[0], lines[2]), result.lines)
        assertEquals(summary.copy(amount = 76.0), result.summary)
    }

    @Test
    fun `deleting the first line retains the other rows classifications and dates`() {
        assertEquals(items.drop(1), edit(lines.drop(1).joinToString("\n")).items)
    }

    @Test
    fun `deleting the last line does not leave an invisible saved item`() {
        assertEquals(items.dropLast(1), edit(lines.dropLast(1).joinToString("\n")).items)
    }

    @Test
    fun `deleting all lines clears both batch and summary`() {
        val result = edit(" \n\n")

        assertEquals(emptyList<ExtractionResult>(), result.items)
        assertEquals(emptyList<String>(), result.lines)
        assertNull(result.summary)
    }

    @Test
    fun `deletion plus amount edit keeps the retained rows identity`() {
        val result = edit("退款 ¥72.50")

        assertEquals(listOf(items[2].copy(amount = 72.5)), result.items)
        assertEquals(72.5, result.summary?.amount)
    }

    @Test
    fun `deletion plus description edit keeps the retained category and date`() {
        val result = edit("午餐 ¥26.00\n商品退货 ¥50.00")

        assertEquals(listOf(items[0], items[2].copy(note = "商品退货")), result.items)
    }

    @Test
    fun `editing an expense amount does not turn an unsigned income into an expense`() {
        val result = edit("午餐 ¥28.50\n公交 ¥3.00\n退款 ¥50.00")

        assertEquals(listOf(items[0].copy(amount = 28.5), items[1], items[2]), result.items)
    }

    @Test
    fun `an explicit amount sign still edits the transaction direction`() {
        val result = edit("午餐 +￥8.25\n公交 ¥3.00\n退款 -¥10.00")

        assertEquals(items[0].copy(amount = 8.25, type = "INCOME"), result.items[0])
        assertEquals(items[2].copy(amount = 10.0, type = "EXPENSE"), result.items[2])
    }

    @Test
    fun `reopening and editing again starts with only committed retained rows`() {
        val first = edit("午餐 ¥26.00\n退款 ¥60.00")
        val reopened = applyCaptureTextEdits(
            first.lines.joinToString("\n"), first.lines, first.items, first.summary
        )
        val second = applyCaptureTextEdits(
            "退款 ¥65.00", reopened.lines, reopened.items, reopened.summary
        )

        assertEquals(first, reopened)
        assertEquals(listOf(items[2].copy(amount = 65.0)), second.items)
        assertEquals(65.0, second.summary?.amount)
    }

    @Test
    fun `reopening an emptied editor never restores old items or summary`() {
        val cleared = edit("")
        val reopened = applyCaptureTextEdits("", cleared.lines, cleared.items, cleared.summary)

        assertEquals(emptyList<ExtractionResult>(), reopened.items)
        assertNull(reopened.summary)
    }

    @Test
    fun `duplicate recognized lines retain only the requested occurrence count`() {
        val duplicate = items.first().copy(date = "2026-09-04")
        val result = applyCaptureTextEdits(
            lines.first(), listOf(lines.first(), lines.first()),
            listOf(items.first(), duplicate), summary
        )

        assertEquals(listOf(items.first()), result.items)
    }

    @Test
    fun `unchanged text and extra blank lines keep the AI metadata and summary`() {
        val aiItems = items.map { it.copy(note = "${it.note}的 AI 备注") }
        val discountedSummary = summary.copy(amount = 70.0)
        val result = applyCaptureTextEdits(
            "\n${lines.joinToString("\n\n")}\n", lines, aiItems, discountedSummary
        )

        assertEquals(aiItems, result.items)
        assertEquals(discountedSummary, result.summary)
    }

    @Test
    fun `text only OCR still updates the summary amount and retains its date`() {
        val result = applyCaptureTextEdits("早餐 ¥12.50", listOf("早餐 ¥10.00"), emptyList(), summary)

        assertEquals(summary.copy(amount = 12.5), result.summary)
    }

    @Test
    fun `moving retained lines does not change their categories or dates`() {
        assertEquals(listOf(items[2], items[0]), edit("退款 ¥50.00\n午餐 ¥26.00").items)
    }

    @Test
    fun `a newly added line cannot duplicate another retained row`() {
        val result = edit("${lines.joinToString("\n")}\n新项目 ¥12.00")

        assertEquals(items, result.items.take(3))
        assertEquals(4, result.items.size)
        assertEquals("新项目", result.items.last().note)
        assertEquals("其他", result.items.last().category)
        assertEquals(12.0, result.items.last().amount)
        assertEquals(summary.date, result.items.last().date)
    }

    @Test
    fun `removing an amount does not resurrect the old amount`() {
        val result = edit("午餐\n公交 ¥3.00\n退款 ¥50.00")

        assertEquals(items[0].copy(amount = 0.0), result.items[0])
        assertEquals(53.0, result.summary?.amount)
    }

    @Test
    fun `deleting then editing both retained fields keeps that rows metadata`() {
        val session = editor()
        session.edit("午餐 ¥26.00\n退款 ¥50.00")
        session.edit("午餐 ¥26.00\n退货款 ¥72.50")

        assertEquals(listOf(items[0], items[2].copy(amount = 72.5, note = "退货款")), session.result.items)
        assertEquals(98.5, session.result.summary?.amount)
    }

    @Test
    fun `undoing editor deletion restores the original AI items and summary`() {
        val session = editor()
        session.edit("")
        session.edit(lines.joinToString("\n"))

        assertEquals(CaptureTextEditResult(lines, items, summary), session.result)
    }

    @Test
    fun `undoing to an intermediate draft restores edited amounts and deleted row metadata`() {
        val session = editor()
        val intermediate = "午餐 ¥28.50\n公交 ¥3.00\n退款 ¥50.00"
        session.edit(intermediate)
        val intermediateResult = session.result
        session.edit("午餐 ¥28.50")
        session.edit(intermediate)

        assertEquals(intermediateResult, session.result)
        assertEquals(listOf(items[0].copy(amount = 28.5), items[1], items[2]), session.result.items)
    }

    @Test
    fun `reopened editor starts a new history with only the committed retained rows`() {
        val first = editor()
        val text = "退款 ¥60.00"
        first.edit(text)
        val reopened = CaptureTextEditSession(text, first.result)
        reopened.edit("")
        reopened.edit(text)

        assertEquals(listOf(items[2].copy(amount = 60.0)), reopened.result.items)
    }

    private fun editor() = CaptureTextEditSession(
        lines.joinToString("\n"), CaptureTextEditResult(lines, items, summary)
    )

    private fun edit(text: String) = applyCaptureTextEdits(text, lines, items, summary)

    private fun item(amount: Double, type: String, category: String, note: String, date: String) =
        ExtractionResult(
            amount = amount,
            type = type,
            category = category,
            merchantName = "$category 商户",
            date = date,
            note = note,
            confidence = 0.9f
        )
}
