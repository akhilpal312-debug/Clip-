package com.aiclipcreator.core.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.aiclipcreator.core.models.CaptionCue
import com.aiclipcreator.core.models.CaptionStyle
import kotlin.math.min

/**
 * Renders one caption cue to a transparent bitmap that is composited over the video frame.
 * Font size scales with output height so 720p and 1080p exports look the same.
 */
class CaptionOverlay {

    fun bitmapFor(cue: CaptionCue?, style: CaptionStyle, width: Int, height: Int): Bitmap? {
        val text = cue?.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        val bandHeight = (height * 0.32f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = height / 720f
        val textSize = (style.fontSizeSp * scale * 2.1f).coerceAtLeast(14f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            this.textSize = textSize
            typeface = Typeface.create(style.fontFamily, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val displayText = if (style.uppercase) text.uppercase() else text
        val horizontalPad = (width * 0.06f).toInt()
        val layoutWidth = width - horizontalPad * 2
        val layout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
        val blockHeight = layout.height
        val boxPad = (textSize * 0.42f).toInt().coerceAtLeast(8)
        val boxTop = (bandHeight - blockHeight) / 2f - boxPad
        val boxHeight = blockHeight + boxPad * 2
        val boxRect = RectF(
            horizontalPad * 0.55f,
            boxTop.coerceAtLeast(2f),
            width - horizontalPad * 0.55f,
            (boxTop + boxHeight).coerceAtMost(bandHeight - 2f)
        )
        if (Color.alpha(style.backgroundColor) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.backgroundColor
                this.style = Paint.Style.FILL
            }
            canvas.drawRoundRect(boxRect, textSize * 0.32f, textSize * 0.32f, bg)
        }
        canvas.save()
        canvas.translate(horizontalPad.toFloat(), boxRect.top + boxPad)
        // Outline pass then fill pass keeps text legible over bright footage.
        val outline = TextPaint(paint).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = (textSize * 0.11f).coerceAtLeast(1.2f)
            color = style.outlineColor
        }
        val outlineLayout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, outline, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
        outlineLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }

    fun textWidthEstimate(style: CaptionStyle, height: Int): Int = min(style.fontSizeSp * 2, height / 4)
}
