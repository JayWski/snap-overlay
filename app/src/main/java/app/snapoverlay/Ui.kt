package app.snapoverlay

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small helpers for building the overlay in code (no layout files). Everything is styled for a dark panel. */
object Ui {
    const val PURPLE = 0xFF5B45D6.toInt()
    const val COST_BLUE = 0xFF2F6FD6.toInt()
    const val POWER_ORANGE = 0xFFE8742A.toInt()
    const val PANEL_BG = 0xF21B1631.toInt()
    const val ROW_BG = 0xFF2A2350.toInt()
    const val BUTTON_BG = 0xFF3B3270.toInt()
    const val WIN_GREEN = 0xFF2E9D57.toInt()
    const val LOSS_RED = 0xFFC8423B.toInt()
    const val MUTED = 0xFFB7B0D9.toInt()

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun text(context: Context, value: String, sizeSp: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun matchWidth(context: Context, topMarginDp: Int = 6) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(context, topMarginDp) }

    fun weighted(context: Context, marginDp: Int = 3) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(context, marginDp); marginEnd = dp(context, marginDp) }

    /** A flat rounded button. */
    fun button(context: Context, label: String, color: Int = BUTTON_BG, sizeSp: Float = 15f, onClick: () -> Unit): TextView =
        text(context, label, sizeSp, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, dp(context, 8).toFloat())
            val h = dp(context, 12); val v = dp(context, 9)
            setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A tappable row with a title and a smaller line under it. */
    fun listRow(context: Context, title: String, subtitle: String?, onClick: () -> Unit): LinearLayout =
        vertical(context).apply {
            background = rounded(ROW_BG, dp(context, 10).toFloat())
            val p = dp(context, 12)
            setPadding(p, dp(context, 10), p, dp(context, 10))
            layoutParams = matchWidth(context)
            addView(text(context, title, 16f, Color.WHITE, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (subtitle != null) addView(text(context, subtitle, 13f, MUTED).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun input(context: Context, hint: String, multiLine: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(ROW_BG, dp(context, 8).toFloat())
        val p = dp(context, 10)
        setPadding(p, p, p, p)
        layoutParams = matchWidth(context)
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3; maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
    }

    private fun badge(context: Context, value: String, color: Int): TextView =
        text(context, value, 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
            val size = dp(context, 28)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    /** One card line: blue cost circle, name, orange power circle. */
    fun cardRow(context: Context, card: ShownCard, drawn: Boolean = false, trailing: String? = null): LinearLayout {
        val row = horizontal(context).apply {
            val pad = dp(context, 4)
            setPadding(pad, pad, pad, pad)
        }
        row.addView(badge(context, card.cost?.toString() ?: "?", COST_BLUE))
        row.addView(text(context, card.name, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10); marginEnd = dp(context, 10)
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(badge(context, card.power?.toString() ?: "?", POWER_ORANGE))
        if (trailing != null) row.addView(text(context, trailing, 18f, MUTED, bold = true).apply {
            setPadding(dp(context, 10), 0, dp(context, 4), 0)
        })
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
