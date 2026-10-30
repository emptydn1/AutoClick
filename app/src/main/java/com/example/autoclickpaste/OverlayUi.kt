package com.example.autoclickpaste

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/** Kiểu nút ở chân hộp thoại. */
enum class SheetAction { PRIMARY, DANGER, TEXT, DANGER_TEXT }

/**
 * Hộp thoại nổi dạng thẻ trắng bo góc, dùng trong dịch vụ Trợ năng (hiện đè lên mọi app):
 * tiêu đề, phần thân cuộn được ([body]), hàng nút ở dưới ([action]).
 */
class OverlaySheet(service: Context, heading: String, subheading: String? = null) {
    val dialog = Dialog(service, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
    val ctx: Context = dialog.context
    val body: LinearLayout
    private val footer: LinearLayout
    private val footerLeft: LinearLayout

    init {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, ctx.dpF(22f))
        }
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(20), ctx.dp(22), ctx.dp(4))
            addView(TextView(ctx).apply {
                text = heading
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UI_TEXT)
            })
            if (subheading != null) {
                addView(TextView(ctx).apply {
                    text = subheading
                    textSize = 13f
                    setTextColor(UI_TEXT_2)
                    setPadding(0, ctx.dp(2), 0, 0)
                })
            }
        })
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), ctx.dp(8))
        }
        // Thân cuộn được, co lại khi nội dung dài để hàng nút luôn nằm trong màn hình
        card.addView(
            ScrollView(ctx).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(16), ctx.dp(16))
        }
        footerLeft = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(footerLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(footer)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)   // hiện được lên trên app khác
        }
    }

    /** Thêm 1 nút ở chân hộp thoại; [left] = true để đặt bên trái (ví dụ "Xóa điểm"). */
    fun action(
        label: String,
        style: SheetAction = SheetAction.TEXT,
        left: Boolean = false,
        onClick: () -> Unit = {},
    ) {
        val button = TextView(ctx).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = ctx.dp(44)
            minWidth = ctx.dp(64)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            when (style) {
                SheetAction.PRIMARY -> {
                    setTextColor(Color.WHITE)
                    background = ripple(roundedBg(UI_PRIMARY, ctx.dpF(14f)), ctx.dpF(14f), 0x33FFFFFF)
                }
                SheetAction.DANGER -> {
                    setTextColor(Color.WHITE)
                    background = ripple(roundedBg(UI_DANGER, ctx.dpF(14f)), ctx.dpF(14f), 0x33FFFFFF)
                }
                SheetAction.TEXT -> {
                    setTextColor(UI_TEXT_2)
                    background = ripple(null, ctx.dpF(14f))
                }
                SheetAction.DANGER_TEXT -> {
                    setTextColor(UI_DANGER)
                    background = ripple(null, ctx.dpF(14f))
                }
            }
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (left) {
            footerLeft.addView(button, lp)
        } else {
            lp.marginStart = ctx.dp(6)
            footer.addView(button, lp)
        }
    }

    /** Hiện hộp thoại, rộng vừa màn hình (tối đa 440 dp). [showKeyboard] = mở sẵn bàn phím. */
    fun show(showKeyboard: Boolean = false) {
        if (showKeyboard) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        try {
            dialog.show()
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            dialog.window?.setLayout(min(screenWidth - ctx.dp(28), ctx.dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Không mở được hộp thoại: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// ───────────── Các thành phần trong hộp thoại ─────────────

/** Gom nhiều view thành 1 cột. */
fun Context.column(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    views.forEach { addView(it) }
}

/** Tiêu đề nhỏ của 1 nhóm (chữ in hoa xám). */
fun Context.groupLabel(label: String): TextView = TextView(this).apply {
    text = label
    textSize = 12f
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.08f
    setTextColor(UI_TEXT_2)
    setPadding(0, dp(16), 0, dp(6))
}

/** Đoạn chữ thường trong thân hộp thoại. */
fun Context.bodyText(s: String): TextView = TextView(this).apply {
    text = s
    textSize = 15f
    setTextColor(UI_TEXT)
    setLineSpacing(0f, 1.15f)
}

/** Chữ gợi ý nhỏ màu xám. */
fun Context.helpText(s: String): TextView = TextView(this).apply {
    text = s
    textSize = 12.5f
    setTextColor(UI_TEXT_2)
    setLineSpacing(0f, 1.1f)
    setPadding(0, dp(2), 0, dp(6))
}

/** Ô nhập bo góc nền xám nhạt, không gạch chân. */
fun Context.inputBox(): EditText = EditText(this).apply {
    background = roundedBg(UI_FIELD, dpF(12f))
    setPadding(dp(14), dp(11), dp(14), dp(11))
    textSize = 15f
    setTextColor(UI_TEXT)
    setHintTextColor(UI_IDLE)
}

/** Dòng "Nhãn ........ [ số ] đơn vị". Trả về dòng và ô nhập. */
fun Context.numberRow(label: String, value: Long, unit: String): Pair<LinearLayout, EditText> {
    val input = inputBox().apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText(value.toString())
        setSelectAllOnFocus(true)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(UI_TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(input, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = unit
            textSize = 13f
            setTextColor(UI_TEXT_2)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return row to input
}

/** Dòng "Nhãn ........ [công tắc]". Chạm cả dòng cũng gạt được. */
fun Context.switchRow(label: String, checked: Boolean): Pair<LinearLayout, Switch> {
    val toggle = Switch(this).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(UI_PRIMARY, 0xFFF4F5F8.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x801E6FD9.toInt(), 0x40000000))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(UI_TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle)
        setOnClickListener { toggle.toggle() }
    }
    return row to toggle
}

/**
 * Hàng nút chọn 1 trong nhiều (chip), mỗi chip có chấm màu; chip đang chọn tô màu của nó.
 * Gọi [onSelect] với vị trí chip khi người dùng chọn.
 */
fun Context.choiceChips(labels: List<String>, colors: List<Int>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val chips = labels.map {
        TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            minHeight = dp(40)
        }
    }
    fun paint(sel: Int) {
        chips.forEachIndexed { i, chip ->
            val active = i == sel
            chip.text = SpannableStringBuilder("●  ${labels[i]}").apply {
                setSpan(ForegroundColorSpan(if (active) Color.WHITE else colors[i]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.8f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.setTextColor(if (active) Color.WHITE else UI_TEXT)
            chip.background = if (active) {
                ripple(roundedBg(colors[i], dpF(12f)), dpF(12f), 0x33FFFFFF)
            } else {
                ripple(roundedBg(UI_FIELD, dpF(12f)), dpF(12f))
            }
        }
    }
    chips.forEachIndexed { i, chip ->
        chip.setOnClickListener {
            paint(i)
            onSelect(i)
        }
        row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i > 0) marginStart = dp(6)
        })
    }
    paint(selected)
    return row
}
