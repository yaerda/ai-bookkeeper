package com.aibookkeeper.feature.capture.ocr

import com.aibookkeeper.core.data.model.ExtractionResult
import java.time.LocalDate

internal data class CaptureTextEditResult(
    val lines: List<String>,
    val items: List<ExtractionResult>,
    val summary: ExtractionResult?
)

internal class CaptureTextEditSession(text: String, initial: CaptureTextEditResult) {
    var result: CaptureTextEditResult = initial
        private set

    private val history = mutableMapOf(text to initial)

    fun edit(text: String) {
        // Track each edit, including IME undo, before a later edit changes another field of the same row.
        result = history.getOrPut(text) {
            applyCaptureTextEdits(text, result.lines, result.items, result.summary)
        }
    }
}

private val amountRegex = Regex("([+-])?\\s*[¥￥]\\s*(\\d+\\.?\\d*)")

private data class CaptureLine(val text: String) {
    private val match = amountRegex.find(text)
    val amount = match?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
    val description = text.replace(amountRegex, "").trim()
    val explicitType = when (match?.groupValues?.get(1)) {
        "+" -> "INCOME"
        "-" -> "EXPENSE"
        else -> null
    }
}

internal fun applyCaptureTextEdits(
    text: String,
    originalLines: List<String>,
    originalItems: List<ExtractionResult>,
    summary: ExtractionResult?
): CaptureTextEditResult {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines == originalLines) {
        return CaptureTextEditResult(lines, originalItems, summary)
    }
    if (lines.isEmpty()) {
        return CaptureTextEditResult(emptyList(), emptyList(), null)
    }

    val original = originalLines.map(::CaptureLine)
    val edited = lines.map(::CaptureLine)
    val matches = matchCaptureLines(original, edited)
    val items = edited.mapIndexed { index, line ->
        val originalIndex = matches[index]
        val previous = originalItems.getOrNull(originalIndex)
        val previousLine = original.getOrNull(originalIndex)
        when {
            previous != null && previousLine?.text == line.text -> previous
            previous != null -> previous.copy(
                amount = line.amount,
                type = line.explicitType ?: previous.type,
                note = if (previousLine?.description == line.description) previous.note else line.description
            )
            else -> ExtractionResult(
                amount = line.amount,
                type = line.explicitType ?: "EXPENSE",
                category = "其他",
                merchantName = summary?.merchantName,
                date = summary?.date ?: LocalDate.now().toString(),
                note = line.description,
                confidence = 0.5f
            )
        }
    }
    val totalAmount = items.sumOf { kotlin.math.abs(it.amount ?: 0.0) }
    return CaptureTextEditResult(
        lines = lines,
        items = items,
        summary = summary?.copy(amount = totalAmount)
            ?: if (totalAmount > 0) ExtractionResult(
                amount = totalAmount,
                type = "EXPENSE",
                category = "其他",
                date = LocalDate.now().toString(),
                confidence = 0.5f
            ) else null
    )
}

private fun matchCaptureLines(original: List<CaptureLine>, edited: List<CaptureLine>): IntArray {
    val matches = IntArray(edited.size) { -1 }
    val remaining = original.indices.toMutableSet()

    fun matchWhere(predicate: (CaptureLine, CaptureLine) -> Boolean) {
        edited.forEachIndexed { index, line ->
            if (matches[index] == -1) {
                val match = remaining.firstOrNull { predicate(original[it], line) }
                if (match != null) {
                    matches[index] = match
                    remaining.remove(match)
                }
            }
        }
    }

    // Reserve retained rows before pairing edited rows, so deletion never shifts their metadata.
    matchWhere { before, after -> before.text.trim() == after.text.trim() }
    matchWhere { before, after ->
        before.description.isNotBlank() && before.description == after.description
    }
    matchWhere { before, after -> before.amount != 0.0 && before.amount == after.amount }
    edited.indices.filter { matches[it] == -1 }.zip(remaining).forEach { (index, originalIndex) ->
        matches[index] = originalIndex
    }
    return matches
}
