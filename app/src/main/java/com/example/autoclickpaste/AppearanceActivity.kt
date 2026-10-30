package com.example.autoclickpaste

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Màn hình "Giao diện": kích thước điểm, kích cỡ / hướng bảng điều khiển, chữ dưới biểu tượng, độ đậm.
 * Có xem trước; thay đổi được áp dụng ngay cho bảng và các điểm đang hiện trên màn hình.
 */
class AppearanceActivity : Activity() {

    private var a = Appearance()
    private var updating = false

    private lateinit var markerPreview: MarkerView
    private lateinit var markerValue: TextView
    private lateinit var panelPreviewBox: FrameLayout
    private lateinit var panelValue: TextView
    private lateinit var opacityValue: TextView
    private lateinit var markerSeek: SeekBar
    private lateinit var panelSeek: SeekBar
    private lateinit var opacitySeek: SeekBar
    private lateinit var gapSeek: SeekBar
    private lateinit var horizontalToggle: Switch
    private lateinit var captionToggle: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        a = AppearanceStore.load(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(24))
            clipToPadding = false
        }

        // ── Thẻ kích thước điểm ──
        val markerCard = card()
        markerCard.addView(sectionHeader("KÍCH THƯỚC ĐIỂM"))
        markerPreview = MarkerView(this).apply {
            number = "1"
            markerColor = COLOR_CLICK
        }
        markerCard.addView(
            FrameLayout(this).apply {
                addView(markerPreview, FrameLayout.LayoutParams(dp(a.markerSizeDp), dp(a.markerSizeDp), Gravity.CENTER))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Appearance.MARKER_MAX + 16)),
        )
        markerSeek = seekBar((Appearance.MARKER_MAX - Appearance.MARKER_MIN) / 4) { p ->
            a.markerSizeDp = Appearance.MARKER_MIN + p * 4
        }
        markerCard.addView(markerSeek)
        markerCard.addView(scaleLabels("Nhỏ", "Lớn"))
        markerValue = valueLabel()
        markerCard.addView(markerValue)
        content.addView(markerCard)

        // ── Thẻ bảng điều khiển ──
        val panelCard = card()
        panelCard.addView(sectionHeader("BẢNG ĐIỀU KHIỂN"))
        panelPreviewBox = FrameLayout(this).apply { setPadding(0, dp(14), 0, dp(14)) }
        panelCard.addView(panelPreviewBox)
        panelSeek = seekBar((Appearance.SCALE_MAX - Appearance.SCALE_MIN) / 10) { p ->
            a.panelScale = Appearance.SCALE_MIN + p * 10
        }
        panelCard.addView(panelSeek)
        panelCard.addView(scaleLabels("Nhỏ", "Vừa", "Lớn"))
        panelValue = valueLabel()
        panelCard.addView(panelValue)
        panelCard.addView(TextView(this).apply {
            text = "Khoảng cách giữa các nút"
            textSize = 14f
            setTextColor(UI_TEXT)
            setPadding(0, dp(14), 0, 0)
        })
        gapSeek = seekBar((Appearance.GAP_MAX - Appearance.GAP_MIN) / 2) { p ->
            a.buttonGap = Appearance.GAP_MIN + p * 2
        }
        panelCard.addView(gapSeek)
        panelCard.addView(scaleLabels("Sát", "Rộng"))
        panelCard.addView(thinDivider(topMarginDp = 12, bottomMarginDp = 4))
        horizontalToggle = toggle("Bảng nằm ngang") { a.horizontal = it }
        panelCard.addView(horizontalToggle)
        captionToggle = toggle("Hiện chữ dưới biểu tượng") { a.showCaptions = it }
        panelCard.addView(captionToggle)
        content.addView(panelCard)

        // ── Thẻ độ đậm ──
        val opacityCard = card()
        opacityCard.addView(sectionHeader("ĐỘ ĐẬM CỦA BẢNG VÀ CÁC ĐIỂM"))
        opacitySeek = seekBar((Appearance.OPACITY_MAX - Appearance.OPACITY_MIN) / 5) { p ->
            a.opacity = Appearance.OPACITY_MIN + p * 5
        }
        opacityCard.addView(opacitySeek, matchWrap(8))
        opacityCard.addView(scaleLabels("Trong", "Đậm"))
        opacityValue = valueLabel()
        opacityCard.addView(opacityValue)
        content.addView(opacityCard)

        // ── Khôi phục mặc định ──
        content.addView(
            outlineButton("Khôi phục mặc định").apply {
                setOnClickListener {
                    a = Appearance()
                    syncControls()
                    updatePreviews()
                    save()
                }
            },
            matchWrap(18),
        )

        setScreen(appHeader("Giao diện", "Thay đổi được áp dụng ngay", onBack = { finish() }), content)
        syncControls()
        updatePreviews()
    }

    /** Lưu và báo dịch vụ vẽ lại bảng / điểm (nếu đang chạy). */
    private fun save() {
        AppearanceStore.save(this, a)
        ClickService.instance?.applyAppearance()
    }

    /** Đưa các thanh kéo / công tắc về đúng giá trị hiện tại. */
    private fun syncControls() {
        updating = true
        markerSeek.progress = (a.markerSizeDp - Appearance.MARKER_MIN) / 4
        panelSeek.progress = (a.panelScale - Appearance.SCALE_MIN) / 10
        opacitySeek.progress = (a.opacity - Appearance.OPACITY_MIN) / 5
        gapSeek.progress = (a.buttonGap - Appearance.GAP_MIN) / 2
        horizontalToggle.isChecked = a.horizontal
        captionToggle.isChecked = a.showCaptions
        updating = false
    }

    /** Vẽ lại phần xem trước bằng đúng các hàm mà bảng nổi thật sự dùng (Ui.kt). */
    private fun updatePreviews() {
        markerPreview.alpha = a.opacity / 100f
        markerPreview.layoutParams =
            FrameLayout.LayoutParams(dp(a.markerSizeDp), dp(a.markerSizeDp), Gravity.CENTER)
        markerValue.text = "${a.markerSizeDp} dp"

        panelPreviewBox.removeAllViews()
        val panel = makePanelContainer(this, a)
        PANEL_BUTTONS.forEach { (icon, caption) -> panel.addView(PanelButton(this, icon, caption, a).view) }
        panelPreviewBox.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        panelValue.text = "${a.panelScale}%"
        opacityValue.text = "${a.opacity}%"
    }

    /** Thanh kéo: kéo tới đâu xem trước tới đó, nhả tay thì lưu và áp dụng. */
    private fun seekBar(steps: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = steps
        setPadding(dp(14), dp(10), dp(14), dp(6))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updating) return
                onChange(progress)
                updatePreviews()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = save()
        })
    }

    private fun toggle(label: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        setTextColor(UI_TEXT)
        setPadding(0, dp(12), 0, dp(12))
        setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                onChange(checked)
                updatePreviews()
                save()
            }
        }
    }

    private fun valueLabel() = TextView(this).apply {
        textSize = 13f
        setTextColor(UI_PRIMARY)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(2), 0, 0)
    }

    private fun scaleLabels(vararg labels: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)
        labels.forEachIndexed { i, s ->
            addView(
                TextView(this@AppearanceActivity).apply {
                    text = s
                    textSize = 12f
                    setTextColor(UI_TEXT_2)
                    gravity = when (i) {
                        0 -> Gravity.START
                        labels.lastIndex -> Gravity.END
                        else -> Gravity.CENTER_HORIZONTAL
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }
}
