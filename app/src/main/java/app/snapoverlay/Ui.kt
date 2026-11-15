package app.snapoverlay

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small helpers for building screens in code (no layout files). */
object Ui {
    const val PURPLE = 0xFF3A2A8C.toInt()
    const val COST_BLUE = 0xFF2F6FD6.toInt()
    const val POWER_ORANGE = 0xFFE8742A.toInt()
    const val PANEL_BG = 0xF01B1631.toInt()

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun text(context: Context, value: String, sizeSp: Float = 16f, color: Int? = null, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (color != null) setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun badge(context: Context, value: String, color: Int): TextView =
        text(context, value, 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
            val size = dp(context, 28)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    /** One card line: blue cost circle, name, orange power circle. */
    fun cardRow(context: Context, card: ShownCard, dark: Boolean, drawn: Boolean = false): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(context, 4)
            setPadding(pad, pad, pad, pad)
        }
        row.addView(badge(context, card.cost?.toString() ?: "?", COST_BLUE))
        val name = text(context, card.name, 15f, if (dark) Color.WHITE else Color.BLACK).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10); marginEnd = dp(context, 10)
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(name)
        row.addView(badge(context, card.power?.toString() ?: "?", POWER_ORANGE))
        setDrawn(row, drawn)
        return row
    }

    fun setDrawn(row: LinearLayout, drawn: Boolean) {
        row.alpha = if (drawn) 0.35f else 1f
        val name = row.getChildAt(1) as TextView
        name.paintFlags = if (drawn) name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    fun spacer(context: Context, heightDp: Int): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp)) }
}

/** A ScrollView that stops growing at [maxHeightPx] and scrolls instead. */
class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
