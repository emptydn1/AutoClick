package com.example.autoclickpaste

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ───────────── Bảng màu giao diện app (màn hình chính, Giao diện) ─────────────
const val UI_PRIMARY = 0xFF1E6FD9.toInt()       // xanh chủ đạo
const val UI_BG = 0xFFF2F4F8.toInt()            // nền xám nhạt
const val UI_CARD = 0xFFFFFFFF.toInt()          // thẻ trắng
const val UI_TEXT = 0xFF1C2333.toInt()          // chữ chính
const val UI_TEXT_2 = 0xFF6B7385.toInt()        // chữ phụ
const val UI_DIVIDER = 0xFFE7EAF0.toInt()
const val UI_GREEN = 0xFF22A05A.toInt()         // trạng thái đang bật
const val UI_SLATE = 0xFF5B6475.toInt()         // nút TẮT
const val UI_IDLE = 0xFFB4BBC8.toInt()          // trạng thái đang tắt
const val UI_FIELD = 0xFFF1F3F7.toInt()         // nền ô nhập
const val UI_DANGER = 0xFFE5484D.toInt()        // đỏ (xóa)
const val UI_WARN_BG = 0xFFFFF4E5.toInt()
const val UI_WARN_BORDER = 0xFFFFC27A.toInt()
const val UI_WARN_TEXT = 0xFF8A4B00.toInt()

/** LayoutParams rộng hết chiều ngang, cao vừa nội dung, cách phía trên [topMarginDp]. */
fun Context.matchWrap(topMarginDp: Int = 0) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(topMarginDp)
    }

/** LayoutParams vừa nội dung, cách phía trên [topMarginDp]. */
fun Context.wrapWrap(topMarginDp: Int = 0) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(topMarginDp)
    }

/** Nền bo góc, có thể có viền. */
fun roundedBg(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

/** Hiệu ứng gợn sóng khi chạm, bo góc theo [radius]. */
fun ripple(content: GradientDrawable?, radius: Float, rippleColor: Int = 0x22000000): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(rippleColor),
        content,
        if (content == null) roundedBg(Color.WHITE, radius) else null,
    )

/** Dựng màn hình: thanh tiêu đề màu + nội dung cuộn; tự chừa chỗ cho thanh trạng thái (Android 15+). */
fun Activity.setScreen(header: View, content: LinearLayout) {
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        addView(content)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UI_BG)
        addView(header, matchWrap())
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val headerTop = header.paddingTop
        val contentBottom = content.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(header.paddingLeft, headerTop + bars.top, header.paddingRight, header.paddingBottom)
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, contentBottom + bars.bottom)
            insets
        }
    }
    setContentView(root)
}

/** Thanh tiêu đề màu xanh: tên + dòng mô tả; có nút ← nếu [onBack] khác null. */
fun Context.appHeader(heading: String, subheading: String?, onBack: (() -> Unit)? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UI_PRIMARY)
        setPadding(dp(20), dp(18), dp(20), dp(22))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (onBack != null) {
            row.addView(TextView(context).apply {
                text = "←"
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ripple(null, dpF(20f), 0x33FFFFFF)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        }
        row.addView(TextView(context).apply {
            text = heading
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        addView(row)
        if (subheading != null) {
            addView(TextView(context).apply {
                text = subheading
                textSize = 13f
                setTextColor(0xD9FFFFFF.toInt())
                setPadding(if (onBack != null) dp(50) else 0, dp(4), 0, 0)
            })
        }
    }

/** Thẻ trắng bo góc chứa 1 nhóm nội dung. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = roundedBg(UI_CARD, dpF(18f))
    elevation = dpF(1.5f)
    setPadding(dp(18), dp(16), dp(18), dp(16))
    layoutParams = matchWrap(14)
}

/** Tiêu đề nhỏ của 1 thẻ: chữ in hoa màu xám. */
fun Context.sectionHeader(label: String): TextView = TextView(this).apply {
    text = label
    textSize = 12f
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.08f
    setTextColor(UI_TEXT_2)
}

/** Nút lớn tô màu, rộng hết thẻ. */
fun Context.pillButton(label: String, color: Int): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    textSize = 16f
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.06f
    setTextColor(Color.WHITE)
    minHeight = dp(52)
    setPillColor(this, color)
}

/** Đổi màu nút lớn (BẬT xanh / TẮT xám). */
fun Context.setPillColor(button: TextView, color: Int) {
    button.background = ripple(roundedBg(color, dpF(14f)), dpF(14f), 0x33FFFFFF)
}

/** Nút viền (nút phụ). */
fun Context.outlineButton(label: String): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    textSize = 15f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(UI_PRIMARY)
    minHeight = dp(48)
    background = ripple(roundedBg(Color.TRANSPARENT, dpF(14f), UI_PRIMARY, dp(1)), dpF(14f), 0x221E6FD9)
}

/** Nút chữ nhỏ (Mở, ⋮ …). */
fun Context.textAction(label: String, color: Int = UI_PRIMARY): TextView = TextView(this).apply {
    text = label
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(color)
    gravity = Gravity.CENTER
    minWidth = dp(44)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    background = ripple(null, dpF(10f))
}

/** Dòng mở 1 màn hình khác: biểu tượng, tiêu đề, mô tả, mũi tên ›. */
fun Context.navRow(icon: String, label: String, description: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = ripple(null, dpF(12f))
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = roundedBg(0x1A1E6FD9, dpF(12f))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UI_TEXT)
            })
            addView(TextView(context).apply {
                text = description
                textSize = 13f
                setTextColor(UI_TEXT_2)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        addView(TextView(context).apply {
            text = "›"
            textSize = 26f
            setTextColor(UI_IDLE)
        })
    }

/** Đường kẻ mảnh giữa các dòng. */
fun Context.thinDivider(topMarginDp: Int = 0, bottomMarginDp: Int = 0): View = View(this).apply {
    setBackgroundColor(UI_DIVIDER)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(topMarginDp)
        bottomMargin = dp(bottomMarginDp)
    }
}

/** Chấm tròn trạng thái. */
fun Context.dot(color: Int): View = View(this).apply {
    background = roundedBg(color, dpF(6f))
}
