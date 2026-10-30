package com.example.autoclickpaste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────── Màu của phần nổi (bảng điều khiển, các điểm) ─────────────
const val COLOR_CLICK = 0xFF2F80ED.toInt()   // điểm click: xanh dương
const val COLOR_HOLD = 0xFF9B51E0.toInt()    // điểm nhấn giữ: tím
const val COLOR_PASTE = 0xFFF2994A.toInt()   // điểm dán: cam
const val COLOR_DRAG = 0xFF27AE60.toInt()    // điểm kéo thả: xanh lá
const val COLOR_PANEL = 0xF2232833.toInt()   // nền bảng điều khiển (xám than)

/** Đổi dp sang px. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Đổi dp sang px (số thực, dùng cho bo góc, độ nổi…). */
fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

/** Chuỗi 2 dòng: dòng trên to, dòng dưới nhỏ. */
fun twoLine(big: String, small: String, smallScale: Float = 0.5f): CharSequence =
    SpannableStringBuilder(big).apply {
        append("\n")
        val start = length
        append(small)
        setSpan(RelativeSizeSpan(smallScale), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

/** Màu theo loại điểm. */
fun colorOf(type: TargetType): Int = when (type) {
    TargetType.CLICK -> COLOR_CLICK
    TargetType.HOLD -> COLOR_HOLD
    TargetType.PASTE -> COLOR_PASTE
    TargetType.DRAG -> COLOR_DRAG
}

// ───────────── Biểu tượng vẽ bằng code (sắc nét ở mọi kích cỡ, không phụ thuộc phông chữ) ─────────────

enum class PanelIcon { PLAY, STOP, ADD, REMOVE, SETTINGS, MINIMIZE, TARGET }

/** Màu của từng biểu tượng trên bảng điều khiển. */
fun iconColor(icon: PanelIcon): Int = when (icon) {
    PanelIcon.PLAY -> 0xFF4DA3FF.toInt()
    PanelIcon.STOP -> 0xFFFF6B6B.toInt()
    PanelIcon.ADD -> 0xFF5AD78C.toInt()
    PanelIcon.REMOVE -> 0xFFFF8A65.toInt()
    PanelIcon.SETTINGS, PanelIcon.MINIMIZE -> 0xFFD3D9E4.toInt()
    PanelIcon.TARGET -> Color.WHITE
}

class IconDrawable(private val icon: PanelIcon, tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        when (icon) {
            PanelIcon.PLAY -> {
                paint.style = Paint.Style.FILL
                paint.pathEffect = CornerPathEffect(s * 0.1f)
                path.reset()
                path.moveTo(cx - s * 0.24f, cy - s * 0.33f)
                path.lineTo(cx + s * 0.36f, cy)
                path.lineTo(cx - s * 0.24f, cy + s * 0.33f)
                path.close()
                canvas.drawPath(path, paint)
                paint.pathEffect = null
            }
            PanelIcon.STOP -> {
                paint.style = Paint.Style.FILL
                val h = s * 0.27f
                canvas.drawRoundRect(cx - h, cy - h, cx + h, cy + h, s * 0.08f, s * 0.08f, paint)
            }
            PanelIcon.ADD, PanelIcon.REMOVE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.13f
                val h = s * 0.3f
                canvas.drawLine(cx - h, cy, cx + h, cy, paint)
                if (icon == PanelIcon.ADD) canvas.drawLine(cx, cy - h, cx, cy + h, paint)
            }
            PanelIcon.SETTINGS -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.12f
                canvas.drawCircle(cx, cy, s * 0.2f, paint)            // vành bánh răng
                paint.strokeCap = Paint.Cap.BUTT
                for (i in 0 until 8) {                                 // 8 răng
                    val angle = (i * PI / 4).toFloat()
                    val c = cos(angle)
                    val sn = sin(angle)
                    canvas.drawLine(cx + c * s * 0.25f, cy + sn * s * 0.25f, cx + c * s * 0.38f, cy + sn * s * 0.38f, paint)
                }
                paint.strokeCap = Paint.Cap.ROUND
            }
            PanelIcon.MINIMIZE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.12f
                path.reset()
                path.moveTo(cx - s * 0.28f, cy - s * 0.12f)
                path.lineTo(cx, cy + s * 0.16f)
                path.lineTo(cx + s * 0.28f, cy - s * 0.12f)
                canvas.drawPath(path, paint)
            }
            PanelIcon.TARGET -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.11f
                canvas.drawCircle(cx, cy, s * 0.33f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, s * 0.12f, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ───────────── Bảng điều khiển ─────────────

/** 5 nút của bảng điều khiển theo thứ tự: biểu tượng + chữ nhỏ (khi bật "Hiện chữ"). */
val PANEL_BUTTONS = listOf(
    PanelIcon.PLAY to "Chạy",
    PanelIcon.ADD to "Thêm",
    PanelIcon.REMOVE to "Xóa",
    PanelIcon.SETTINGS to "Cài đặt",
    PanelIcon.MINIMIZE to "Thu nhỏ",
)

/** Khung bảng điều khiển: nền tối bo góc, viền mảnh; dọc / ngang, to / nhỏ, độ đậm theo [a]. */
fun makePanelContainer(ctx: Context, a: Appearance): LinearLayout = LinearLayout(ctx).apply {
    val s = a.panelScale / 100f
    orientation = if (a.horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    val pad = ctx.dpF(4f * s).roundToInt()
    setPadding(pad, pad, pad, pad)
    background = roundedBg(COLOR_PANEL, ctx.dpF(14f * s), 0x2EFFFFFF, max(1, ctx.dp(1)))
    alpha = a.opacity / 100f
}

/** 1 nút trên bảng điều khiển: ô vuông có biểu tượng (và chữ nhỏ nếu bật). */
class PanelButton(ctx: Context, icon: PanelIcon, caption: String, a: Appearance) {
    private val image = ImageView(ctx).apply { setImageDrawable(IconDrawable(icon, iconColor(icon))) }
    private val label: TextView? = if (a.showCaptions) {
        TextView(ctx).apply {
            text = caption
            textSize = 9.5f * a.panelScale / 100f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
        }
    } else {
        null
    }

    val view: LinearLayout = LinearLayout(ctx).apply {
        val s = a.panelScale / 100f
        val gap = a.buttonGap.toFloat()
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val iconPx = ctx.dpF(20f * s).roundToInt()
        addView(image, LinearLayout.LayoutParams(iconPx, iconPx))
        label?.let {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpF(2f * s).roundToInt()
            })
        }
        // Ô vuông đều nhau: biểu tượng + khoảng cách [Appearance.buttonGap]
        minimumWidth = ctx.dpF(((if (a.showCaptions) 38f else 24f) + gap) * s).roundToInt()
        minimumHeight = ctx.dpF(((if (a.showCaptions) 36f else 24f) + gap) * s).roundToInt()
        background = ripple(null, ctx.dpF(10f * s), 0x33FFFFFF)
        isClickable = true
    }

    fun setIcon(icon: PanelIcon) = image.setImageDrawable(IconDrawable(icon, iconColor(icon)))

    fun setCaption(text: String) {
        label?.text = text
    }
}

// ───────────── Vòng tròn của 1 điểm ─────────────

/**
 * Vòng tròn 1 điểm: viền màu theo loại, nền mờ, CHẤM Ở TÂM là đúng chỗ thao tác,
 * số thứ tự trong huy hiệu nhỏ ở góc. Vòng "thả" của điểm kéo thả có viền đứt.
 */
class MarkerView(ctx: Context) : View(ctx) {
    var markerColor: Int = COLOR_CLICK
        set(value) {
            field = value
            invalidate()
        }
    var number: String = ""
        set(value) {
            field = value
            invalidate()
        }
    var dashed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80FFFFFF.toInt()
    }
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var dashSize = -1f
    private var dashEffect: DashPathEffect? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = s * 0.36f
        val solid = markerColor or 0xFF000000.toInt()

        fillPaint.color = (solid and 0x00FFFFFF) or 0x3D000000          // nền mờ ~24%
        canvas.drawCircle(cx, cy, r, fillPaint)

        haloPaint.strokeWidth = s * 0.035f                                 // viền trắng mảnh cho dễ thấy trên nền tối
        canvas.drawCircle(cx, cy, r + s * 0.045f, haloPaint)

        if (dashed && dashSize != s) {
            dashSize = s
            dashEffect = DashPathEffect(floatArrayOf(s * 0.09f, s * 0.06f), 0f)
        }
        ringPaint.color = solid
        ringPaint.strokeWidth = s * 0.06f
        ringPaint.pathEffect = if (dashed) dashEffect else null
        canvas.drawCircle(cx, cy, r, ringPaint)

        solidPaint.color = solid                                           // chấm tâm = chỗ thao tác
        canvas.drawCircle(cx, cy, s * 0.055f, solidPaint)

        if (number.isNotEmpty()) {                                         // huy hiệu số thứ tự
            val br = s * 0.17f
            val bx = cx + r * 0.78f
            val by = cy - r * 0.78f
            canvas.drawCircle(bx, by, br, solidPaint)
            badgeRing.strokeWidth = s * 0.03f
            canvas.drawCircle(bx, by, br, badgeRing)
            textPaint.textSize = br * if (number.length > 1) 1.0f else 1.25f
            canvas.drawText(number, bx, by - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }
}
