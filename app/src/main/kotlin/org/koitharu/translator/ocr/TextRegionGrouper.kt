package org.koitharu.translator.ocr

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

class TextRegionGrouper {
    private val lineHeightFactor = 0.55f
    private val horizontalGapFactor = 0.35f
    private val verticalGapFactor = 0.75f
    private val maxBlocksPerRegion = 80

    fun group(blocks: List<TextBlock>): List<TextRegion> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
        val regions = mutableListOf<TextRegion>()
        var current = mutableListOf<TextBlock>()
        var lastTop = 0f
        var lastRight = 0f
        var lineHeight = 0f
        var currentVertical = false

        fun flush() {
            if (current.isEmpty()) return
            val left = current.minOf { it.boundingBox.left }
            val top = current.minOf { it.boundingBox.top }
            val right = current.maxOf { it.boundingBox.right }
            val bottom = current.maxOf { it.boundingBox.bottom }
            val ordered = if (currentVertical) {
                current.sortedWith(compareByDescending<TextBlock> { it.boundingBox.right }.thenBy { it.boundingBox.top })
            } else {
                current.sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
            }
            val text = if (currentVertical) {
                ordered.joinToString("") { it.text.replace("\n", "") }
            } else {
                ordered.joinToString(" ") { it.text.replace("\n", " ").trim() }
            }
            regions += TextRegion(ordered, Rect(left, top, right, bottom), text.trim(), currentVertical)
            current = mutableListOf()
        }

        for (block in sorted) {
            val h = block.boundingBox.height().toFloat().coerceAtLeast(1f)
            if (current.isEmpty()) {
                current += block
                lastTop = block.boundingBox.top.toFloat()
                lastRight = block.boundingBox.right.toFloat()
                lineHeight = h
                currentVertical = block.vertical
                continue
            }
            val sameOrientation = currentVertical == block.vertical
            val topGap = abs(block.boundingBox.top - lastTop)
            val horizontalGap = block.boundingBox.left - lastRight
            val sameLine = topGap <= lineHeight * lineHeightFactor
            val near = horizontalGap <= lineHeight * horizontalGapFactor
            val tooFar = topGap > lineHeight * verticalGapFactor
            val canJoin = if (currentVertical) {
                sameOrientation &&
                    abs(block.boundingBox.left - current.last().boundingBox.left) <= lineHeight * 1.2f &&
                    abs(block.boundingBox.top - current.last().boundingBox.bottom) <= lineHeight * 1.6f
            } else {
                sameOrientation && sameLine && near && !tooFar && horizontalGap >= -lineHeight * 0.2f
            }
            if (canJoin && current.size < maxBlocksPerRegion) {
                current += block
                lastRight = max(lastRight, block.boundingBox.right.toFloat())
                lineHeight = lineHeight * 0.7f + h * 0.3f
            } else {
                flush()
                current += block
                lastTop = block.boundingBox.top.toFloat()
                lastRight = block.boundingBox.right.toFloat()
                lineHeight = h
                currentVertical = block.vertical
            }
        }
        flush()
        return regions.filter { it.text.isNotBlank() }
    }
}
