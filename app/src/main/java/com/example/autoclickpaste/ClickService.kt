package com.example.autoclickpaste

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dịch vụ Trợ năng (AccessibilityService) – trái tim của app:
 *  - Vẽ bảng điều khiển nổi và các điểm (vòng tròn) đè lên mọi app bằng cửa sổ
 *    TYPE_ACCESSIBILITY_OVERLAY (không cần thêm quyền "Hiển thị trên ứng dụng khác").
 *  - Chạy lần lượt 1 → 2 → 3…: click, nhấn giữ, kéo thả (dispatchGesture),
 *    click rồi dán chữ (ACTION_PASTE qua clipboard, dự phòng ACTION_SET_TEXT).
 *  - Bật / tắt bằng nút trong app (MainActivity gọi showOverlay / hideOverlay).
 */
class ClickService : AccessibilityService() {

    companion object {
        /** Dịch vụ đang chạy (null nếu chưa cấp quyền Trợ năng). MainActivity dùng để bật / tắt. */
        @Volatile
        var instance: ClickService? = null
            private set

        private const val BUBBLE_SIZE_DP = 40         // nút tròn khi thu nhỏ bảng (ở kích cỡ 100%)
        private const val END_MARKER_RATIO = 0.82f    // vòng tròn "thả" nhỏ hơn vòng "kéo" một chút
        private const val TAP_DURATION_MS = 50L       // thời gian "chạm" của 1 cú click
        private const val MIN_STEP_DELAY_MS = 10L     // chặn vòng lặp quá nhanh làm đơ máy
        private const val FOCUS_POLL_MS = 100L        // sau khi click, dò ô nhập mỗi 100 ms…
        private const val FOCUS_POLL_TRIES = 15       // …tối đa khoảng 1,5 giây
        private const val CLIPBOARD_SETTLE_MS = 80L
        private const val START_DELAY_MS = 300L       // chờ các vòng tròn chuyển sang "click xuyên qua"
        private const val MAX_GESTURE_MS = 59_000L    // Android giới hạn 1 cử chỉ tối đa 60 giây
    }

    /** Một điểm đang hiện: 1 vòng tròn, riêng điểm kéo thả có thêm vòng tròn "thả". */
    private class Marker(
        val target: ClickTarget,
        val view: MarkerView,
        val params: WindowManager.LayoutParams,
        val endView: MarkerView? = null,
        val endParams: WindowManager.LayoutParams? = null,
    ) {
        fun windows(): List<Pair<MarkerView, WindowManager.LayoutParams>> =
            if (endView != null && endParams != null) listOf(view to params, endView to endParams)
            else listOf(view to params)
    }

    /** Một bước khi chạy: toạ độ tâm trên màn hình (x2, y2 = điểm thả của bước kéo thả). */
    private class Step(val marker: Marker, val x: Float, val y: Float, val x2: Float, val y2: Float)

    private lateinit var wm: WindowManager
    private lateinit var config: Config
    private lateinit var appearance: Appearance       // kích thước, kiểu bảng, độ đậm (màn hình Giao diện)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var playButton: PanelButton? = null
    private val editButtons = mutableListOf<View>()   // các nút bị ẩn khi đang chạy
    private val markers = mutableListOf<Marker>()

    private var bubble: View? = null                  // nút tròn nhỏ khi bảng đang thu nhỏ
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var lineView: LineOverlayView? = null     // lớp vẽ mũi tên của các điểm kéo thả
    private var lineParams: WindowManager.LayoutParams? = null

    private val isPlaying: Boolean get() = playJob?.isActive == true

    /** Độ đậm bình thường của bảng / điểm, và khi điểm mờ đi lúc đang chạy. */
    private val normalAlpha: Float get() = appearance.opacity / 100f
    private val dimAlpha: Float get() = normalAlpha * 0.5f
    val isOverlayShown: Boolean get() = panel != null || bubble != null

    // ───────────────────────────── Vòng đời dịch vụ ─────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Lấy WindowManager SAU khi dịch vụ đã kết nối, để cửa sổ nổi nhận đúng "token" Trợ năng.
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        config = ConfigStore.load(this)
        appearance = AppearanceStore.load(this)
        instance = this
        if (config.panelVisible) showOverlay()
        // Vừa cấp quyền từ nút BẬT trong app → tự quay về màn hình app (chờ Cài đặt đóng hộp thoại xong)
        if (GrantReturn.consume(this)) {
            scope.launch {
                delay(400)
                returnToApp()
            }
        }
    }

    /** Đưa màn hình chính của app lên trước (đóng luôn trang Cài đặt nếu nó nằm trên app). */
    private fun returnToApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    .putExtra(MainActivity.EXTRA_JUST_GRANTED, true)
            )
        } catch (e: Exception) {
            // Một số máy chặn mở app từ nền – người dùng tự bấm Quay lại là được
        }
    }

    /** Màn hình Giao diện gọi khi người dùng đổi kích thước / kiểu: vẽ lại bảng và các điểm ngay. */
    fun applyAppearance() {
        appearance = AppearanceStore.load(this)
        when {
            panel != null -> {
                stopPlaying()
                removePanelAndMarkers()
                createPanel()
                reloadMarkers()
            }
            bubble != null -> {
                removeBubble()
                createBubble()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = stopPlaying()

    /** Xoay màn hình: kéo bảng và các điểm về lại trong màn hình nếu bị lọt ra ngoài. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (panel ?: bubble)?.post { keepOnScreen() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        instance = null
        playJob?.cancel()
        removeOverlayViews()
        scope.cancel()
    }

    // ───────────────────────────── Bật / tắt, thu nhỏ ─────────────────────────────

    /** BẬT: hiện bảng điều khiển và các điểm đã lưu (nút trong app gọi). */
    fun showOverlay() {
        if (isOverlayShown) return
        config.panelVisible = true
        saveConfig()
        expandPanel()
    }

    /** TẮT: cất hết bảng, các điểm, nút tròn (dừng lại nếu đang chạy). */
    fun hideOverlay() {
        stopPlaying()
        config.panelVisible = false
        saveConfig()
        removeOverlayViews()
    }

    fun stopPlaying() {
        playJob?.cancel()
    }

    /** Mở bảng đầy đủ + các điểm (bỏ nút tròn nếu đang thu nhỏ). */
    private fun expandPanel() {
        removeBubble()
        if (panel != null) return
        createPanel()
        reloadMarkers()
    }

    /** Thu nhỏ: cất bảng và các điểm đi, chỉ còn 1 nút tròn nhỏ. Chạm nút tròn để mở lại. */
    private fun minimizePanel() {
        stopPlaying()
        removePanelAndMarkers()   // vị trí và nội dung các điểm vẫn được lưu
        createBubble()
    }

    private fun removeOverlayViews() {
        removePanelAndMarkers()
        removeBubble()
    }

    private fun removePanelAndMarkers() {
        if (!::wm.isInitialized) return
        markers.forEach { removeMarkerViews(it) }
        markers.clear()
        removeLineOverlay()
        panel?.let { safeRemove(it) }
        panel = null
        panelParams = null
        playButton = null
        editButtons.clear()
    }

    private fun createBubble() {
        if (bubble != null) return
        val size = dp(BUBBLE_SIZE_DP * appearance.panelScale / 100)
        val (sw, sh) = screenSize()
        val view = ImageView(this).apply {
            setImageDrawable(IconDrawable(PanelIcon.TARGET, iconColor(PanelIcon.TARGET)))
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
            alpha = normalAlpha
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_PANEL)
                setStroke(max(1, dp(1)), 0x4DFFFFFF)
            }
        }
        // Nút tròn nằm đúng chỗ của bảng; kéo nút tròn thì bảng mở ra ở chỗ mới
        val params = overlayParams(
            config.panelX.coerceIn(0, max(0, sw - size)),
            config.panelY.coerceIn(0, max(0, sh - size)),
            size,
            size,
        )
        makeDraggable(view, view, params, onTap = { expandPanel() }) {
            config.panelX = params.x
            config.panelY = params.y
            saveConfig()
        }
        wm.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    private fun removeBubble() {
        if (!::wm.isInitialized) return
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
    }

    // ───────────────────────────── Bảng điều khiển nổi ─────────────────────────────

    private fun createPanel() {
        // Dựng bảng bằng cùng hàm với phần xem trước ở màn hình Giao diện (Ui.kt)
        val root = makePanelContainer(this, appearance)
        val buttons = PANEL_BUTTONS.map { (icon, caption) -> PanelButton(this, icon, caption, appearance) }
        val (play, add, removeLast, settings, minimize) = buttons
        play.view.setOnClickListener { togglePlay() }
        add.view.setOnClickListener { addTarget() }
        removeLast.view.setOnClickListener { markers.lastOrNull()?.let { removeMarker(it) } }
        settings.view.setOnClickListener { showSettingsDialog() }
        minimize.view.setOnClickListener { minimizePanel() }

        buttons.forEach { root.addView(it.view) }
        editButtons.clear()
        editButtons.addAll(listOf(add.view, removeLast.view, settings.view, minimize.view))
        playButton = play

        // Đo kích thước bảng để giữ trọn bảng trong màn hình (kể cả khi mở ra từ nút tròn sát mép)
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val (sw, sh) = screenSize()
        val params = overlayParams(
            config.panelX.coerceIn(0, max(0, sw - root.measuredWidth)),
            config.panelY.coerceIn(0, max(0, sh - root.measuredHeight)),
        )

        // Kéo ở BẤT KỲ chỗ nào trên bảng (kể cả đè lên nút) để di chuyển; chạm nhẹ thì là bấm nút.
        val savePanelPosition = {
            config.panelX = params.x
            config.panelY = params.y
            saveConfig()
        }
        makeDraggable(root, root, params, onTap = null, onDragEnd = savePanelPosition)
        for (b in buttons) {
            makeDraggable(b.view, root, params, onTap = { b.view.performClick() }, onDragEnd = savePanelPosition)
        }
        wm.addView(root, params)
        panel = root
        panelParams = params
    }

    // ───────────────────────────── Các điểm (vòng tròn) ─────────────────────────────

    /** (Tạo lại) toàn bộ vòng tròn từ cấu hình đã lưu. */
    private fun reloadMarkers() {
        markers.forEach { removeMarkerViews(it) }
        markers.clear()
        removeLineOverlay()
        if (config.targets.any { it.type == TargetType.DRAG }) createLineOverlay()
        config.targets.forEach { markers.add(createMarker(it)) }
        refreshLabels()
    }

    private fun createMarker(target: ClickTarget): Marker {
        val (sw, sh) = screenSize()
        val color = colorOf(target.type)
        val sizeDp = appearance.markerSizeDp
        val size = dp(sizeDp)
        val view = markerView(color, dashed = false)
        val params = overlayParams(
            target.x.coerceIn(0, max(0, sw - size)),
            target.y.coerceIn(0, max(0, sh - size)),
            size,
            size,
        )

        // Điểm kéo thả: thêm vòng tròn "thả" (viền đứt)
        var endView: MarkerView? = null
        var endParams: WindowManager.LayoutParams? = null
        if (target.type == TargetType.DRAG) {
            val endSize = dp((sizeDp * END_MARKER_RATIO).roundToInt())
            if (target.x2 < 0 || target.y2 < 0) {
                // Lần đầu: đặt vòng "thả" bên phải điểm kéo (bên trái nếu sát mép phải)
                target.x2 = if (params.x + size + dp(160) < sw) params.x + dp(160) else max(0, params.x - dp(160))
                target.y2 = params.y + (size - endSize) / 2
            }
            endView = markerView(color, dashed = true)
            endParams = overlayParams(
                target.x2.coerceIn(0, max(0, sw - endSize)),
                target.y2.coerceIn(0, max(0, sh - endSize)),
                endSize,
                endSize,
            )
        }

        val marker = Marker(target, view, params, endView, endParams)
        val redrawLine: () -> Unit = { lineView?.invalidate() }

        // Kéo để di chuyển, chạm nhẹ để mở hộp thoại sửa
        makeDraggable(view, view, params, onTap = { showTargetDialog(marker) }, onMove = redrawLine) {
            target.x = params.x
            target.y = params.y
            saveConfig()
        }
        wm.addView(view, params)

        val ev = endView
        val ep = endParams
        if (ev != null && ep != null) {
            makeDraggable(ev, ev, ep, onTap = { showTargetDialog(marker) }, onMove = redrawLine) {
                target.x2 = ep.x
                target.y2 = ep.y
                saveConfig()
            }
            wm.addView(ev, ep)
        }
        lineView?.invalidate()
        return marker
    }

    private fun markerView(fillColor: Int, dashed: Boolean) = MarkerView(this).also {
        it.markerColor = fillColor
        it.dashed = dashed
        it.alpha = normalAlpha
    }

    /** Đổi màu vòng tròn khi đổi loại điểm. */
    private fun paintMarker(m: Marker) {
        val color = colorOf(m.target.type)
        for ((v, _) in m.windows()) v.markerColor = color
    }

    /** Đánh số lại 1, 2, 3… trong huy hiệu ở góc mỗi vòng tròn. */
    private fun refreshLabels() {
        markers.forEachIndexed { i, m ->
            val number = (i + 1).toString()
            m.view.number = number
            m.endView?.number = number
        }
    }

    /** Thêm 1 điểm click ở giữa màn hình. Chạm vào điểm để đổi kiểu thao tác. */
    private fun addTarget() {
        val (sw, sh) = screenSize()
        val size = dp(appearance.markerSizeDp)
        val offset = (markers.size % 5) * dp(20)   // lệch nhẹ để điểm mới không chồng lên điểm cũ
        val target = ClickTarget(
            type = TargetType.CLICK,
            x = (sw - size) / 2 + offset,
            y = (sh - size) / 2 + offset,
            delayMs = config.defaultDelayMs,
        )
        config.targets.add(target)
        markers.add(createMarker(target))
        refreshLabels()
        saveConfig()
        if (markers.size == 1) {
            toast("Kéo điểm tới chỗ cần bấm. Chạm vào điểm để đổi kiểu: nhấn giữ, dán chữ, kéo thả…")
        }
    }

    private fun removeMarker(m: Marker) {
        removeMarkerViews(m)
        markers.remove(m)
        config.targets.remove(m.target)
        if (markers.none { it.target.type == TargetType.DRAG }) removeLineOverlay() else lineView?.invalidate()
        refreshLabels()
        saveConfig()
    }

    private fun removeMarkerViews(m: Marker) {
        for ((v, _) in m.windows()) safeRemove(v)
    }

    // ───────────────────────────── Mũi tên của điểm kéo thả ─────────────────────────────

    private fun createLineOverlay() {
        if (lineView != null) return
        val (sw, sh) = screenSize()
        val view = LineOverlayView(this)
        val params = overlayParams(0, 0, sw, sh).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // không chặn chạm
        }
        wm.addView(view, params)
        lineView = view
        lineParams = params
    }

    private fun removeLineOverlay() {
        if (!::wm.isInitialized) return
        lineView?.let { safeRemove(it) }
        lineView = null
        lineParams = null
    }

    /** Lớp trong suốt phủ màn hình, không nhận chạm: vẽ mũi tên từ vòng "kéo" tới vòng "thả". */
    private inner class LineOverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_DRAG
            style = Paint.Style.STROKE
            strokeWidth = dpF(3f)
            strokeCap = Paint.Cap.ROUND
        }
        private val head = dpF(12f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.alpha = (255 * normalAlpha).roundToInt()
            for (m in markers) {
                val ep = m.endParams ?: continue
                val sp = m.params
                val sx = sp.x + sp.width / 2f
                val sy = sp.y + sp.height / 2f
                val ex = ep.x + ep.width / 2f
                val ey = ep.y + ep.height / 2f
                val len = hypot(ex - sx, ey - sy)
                val r1 = sp.width * 0.42f                 // dừng ở mép vòng tròn (bán kính vòng ≈ 0,36 + viền)
                val r2 = ep.width * 0.42f
                if (len <= r1 + r2) continue              // 2 vòng tròn chồng lên nhau: không vẽ
                val ux = (ex - sx) / len
                val uy = (ey - sy) / len
                val bx = ex - ux * r2
                val by = ey - uy * r2
                canvas.drawLine(sx + ux * r1, sy + uy * r1, bx, by, paint)
                val back = atan2(uy, ux) + PI.toFloat()
                canvas.drawLine(bx, by, bx + cos(back - 0.5f) * head, by + sin(back - 0.5f) * head, paint)
                canvas.drawLine(bx, by, bx + cos(back + 0.5f) * head, by + sin(back + 0.5f) * head, paint)
            }
        }
    }

    // ───────────────────────────── Chạy / dừng ─────────────────────────────

    private fun togglePlay() {
        if (isPlaying) stopPlaying() else startPlaying()
    }

    private fun startPlaying() {
        if (markers.isEmpty()) {
            toast("Chưa có điểm nào. Bấm ＋ để thêm điểm.")
            return
        }
        // Toạ độ thao tác = tâm của từng vòng tròn trên màn hình
        val steps = markers.map { m ->
            val (x, y) = centerOnScreen(m.view)
            val (x2, y2) = m.endView?.let { centerOnScreen(it) } ?: (x to y)
            Step(m, x, y, x2, y2)
        }
        // Điểm nằm dưới bảng điều khiển thì thao tác sẽ trúng bảng → nhắc người dùng
        panel?.let { p ->
            val loc = IntArray(2)
            p.getLocationOnScreen(loc)
            val box = Rect(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
            val covered = steps.indexOfFirst {
                box.contains(it.x.toInt(), it.y.toInt()) || box.contains(it.x2.toInt(), it.y2.toInt())
            }
            if (covered >= 0) {
                toast("Điểm ${covered + 1} đang nằm dưới bảng điều khiển – hãy kéo bảng ra chỗ khác.")
                return
            }
        }

        setPlayingUi(true)
        playJob = scope.launch {
            val myJob = coroutineContext[Job]
            var pasteWarned = false
            try {
                // Android cần vài khung hình để áp dụng cờ FLAG_NOT_TOUCHABLE cho các vòng tròn.
                // Nếu thao tác ngay, cú đầu tiên sẽ trúng chính vòng tròn chứ không xuống app bên dưới.
                delay(START_DELAY_MS)
                var loop = 0
                while (isActive && (config.loops == 0 || loop < config.loops)) {
                    for ((i, step) in steps.withIndex()) {
                        highlight(i)
                        val t = step.marker.target
                        when (t.type) {
                            TargetType.CLICK -> tap(step.x, step.y)
                            TargetType.HOLD -> press(step.x, step.y, t.pressMs)
                            TargetType.PASTE -> {
                                tap(step.x, step.y)
                                if (t.text.isNotEmpty() && !pasteText(t, step.x, step.y) && !pasteWarned) {
                                    pasteWarned = true
                                    toast("Điểm ${i + 1}: không tìm thấy ô nhập chữ để dán")
                                }
                            }
                            TargetType.DRAG -> drag(step.x, step.y, step.x2, step.y2, t.holdMs, t.dragMs)
                        }
                        delay(max(t.delayMs, MIN_STEP_DELAY_MS))
                    }
                    loop++
                }
                if (config.loops > 0) toast("Xong $loop vòng")
            } finally {
                // Chỉ trả giao diện về trạng thái "dừng" nếu chưa có lượt chạy mới
                if (playJob === myJob) {
                    playJob = null
                    setPlayingUi(false)
                }
            }
        }
    }

    private fun setPlayingUi(playing: Boolean) {
        playButton?.setIcon(if (playing) PanelIcon.STOP else PanelIcon.PLAY)
        playButton?.setCaption(if (playing) "Dừng" else "Chạy")
        for (b in editButtons) b.visibility = if (playing) View.GONE else View.VISIBLE
        for (m in markers) {
            for ((v, p) in m.windows()) {
                // Khi chạy: cho thao tác "xuyên qua" vòng tròn xuống app bên dưới
                p.flags = if (playing) {
                    p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                v.alpha = if (playing) dimAlpha else normalAlpha
                safeUpdate(v, p)
            }
        }
    }

    /** Làm sáng điểm đang chạy, các điểm khác mờ đi. */
    private fun highlight(index: Int) {
        markers.forEachIndexed { i, m ->
            val a = if (i == index) normalAlpha else dimAlpha
            m.view.alpha = a
            m.endView?.alpha = a
        }
    }

    private fun centerOnScreen(v: View): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] + v.width / 2f) to (loc[1] + v.height / 2f)
    }

    // ───────────────────────────── Click, nhấn giữ, kéo thả, dán ─────────────────────────────

    /** Chạm 1 lần tại (x, y) – toạ độ màn hình. */
    private suspend fun tap(x: Float, y: Float): Boolean = press(x, y, TAP_DURATION_MS)

    /** Đặt ngón tay tại (x, y), giữ yên [pressMs] rồi nhả (click thường cũng là nhấn rất ngắn). */
    private suspend fun press(x: Float, y: Float, pressMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, pressMs.coerceIn(1L, MAX_GESTURE_MS)))
    }

    /** Kéo từ (x1, y1) tới (x2, y2) trong [moveMs]; nếu [holdMs] > 0 thì nhấn giữ ở điểm đầu trước. */
    private suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, moveMs: Long): Boolean {
        val move = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val moveDuration = moveMs.coerceIn(1L, MAX_GESTURE_MS)
        if (holdMs > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return holdThenDrag(x1, y1, move, holdMs.coerceIn(1L, MAX_GESTURE_MS), moveDuration)
        }
        return dispatch(GestureDescription.StrokeDescription(move, 0, moveDuration))
    }

    /** Android 8+: nét 1 giữ yên ở điểm đầu (ngón tay không nhấc), nét 2 nối tiếp kéo tới điểm thả. */
    @TargetApi(Build.VERSION_CODES.O)
    private suspend fun holdThenDrag(x1: Float, y1: Float, move: Path, holdMs: Long, moveMs: Long): Boolean {
        val hold = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1) }, 0, holdMs, true)
        if (!dispatch(hold)) return false
        return dispatch(hold.continueStroke(move, 0, moveMs, false))
    }

    /** Gửi 1 cử chỉ và chờ tới khi xong. Trả về false nếu bị hủy. */
    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }

    /**
     * Dán chữ của [t] vào ô nhập vừa được click tại (x, y):
     *  1) Chờ ô nhập nhận focus (tối đa ~1,5 giây).
     *  2) "Dán tại con trỏ": chép vào clipboard rồi ACTION_PASTE (giống người dùng bấm Dán);
     *     nếu app không hỗ trợ thì ghi thẳng bằng ACTION_SET_TEXT (nối vào cuối).
     *  3) "Xóa chữ cũ rồi dán": ACTION_SET_TEXT thay toàn bộ; dự phòng: chọn hết rồi dán đè.
     */
    private suspend fun pasteText(t: ClickTarget, x: Float, y: Float): Boolean {
        var node: AccessibilityNodeInfo? = null
        for (attempt in 0 until FOCUS_POLL_TRIES) {
            delay(FOCUS_POLL_MS)
            node = findInputNode(x.toInt(), y.toInt())
            if (node != null) break
        }
        val input = node ?: return false
        return when (t.pasteMode) {
            PasteMode.PASTE_AT_CURSOR ->
                pasteViaClipboard(input, t.text) || setText(input, currentText(input) + t.text)
            PasteMode.REPLACE_ALL ->
                setText(input, t.text) || (selectAll(input) && pasteViaClipboard(input, t.text))
        }
    }

    /** Tìm ô nhập: ưu tiên ô đang có focus; nếu không có thì tìm ô nhập nằm đúng tại toạ độ vừa click. */
    private fun findInputNode(x: Int, y: Int): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        }
        for (root in roots.ifEmpty { listOfNotNull(rootInActiveWindow) }) {
            val found = findEditableAt(root, x, y, 0) ?: continue
            found.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return found
        }
        return null
    }

    private fun findEditableAt(node: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > 60) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableAt(child, x, y, depth + 1)?.let { return it }
        }
        return if (node.isEditable) node else null
    }

    private suspend fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("AutoClickPaste", text))
        } catch (e: Exception) {
            return false
        }
        delay(CLIPBOARD_SETTLE_MS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun selectAll(node: AccessibilityNodeInfo): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText(node).length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /** Chữ hiện có trong ô (bỏ qua chữ gợi ý/hint). */
    private fun currentText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) return ""
        return node.text?.toString().orEmpty()
    }

    // ───────────────────────────── Hộp thoại sửa 1 điểm ─────────────────────────────

    /** Sửa 1 điểm: kiểu thao tác (click / giữ / dán / kéo), thời gian chờ, nội dung dán, kéo thả, xóa. */
    private fun showTargetDialog(marker: Marker) {
        if (isPlaying) return
        val t = marker.target
        val sheet = OverlaySheet(this, "Điểm ${markers.indexOf(marker) + 1}", "Chọn kiểu thao tác cho điểm này")
        val ctx = sheet.ctx
        val types = listOf(TargetType.CLICK, TargetType.HOLD, TargetType.PASTE, TargetType.DRAG)
        var selected = t.type

        // Nhấn giữ
        val (pressRow, pressInput) = ctx.numberRow("Thời gian nhấn giữ", t.pressMs, "ms")
        val holdSection = ctx.column(
            ctx.groupLabel("NHẤN GIỮ"),
            pressRow,
            ctx.helpText("Giữ yên ngón tay tại tâm vòng tròn rồi mới nhả (1000 ms = 1 giây, tối đa 59 giây)."),
        )

        // Dán chữ
        val textInput = ctx.inputBox().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            hint = "Nhập nội dung cần dán…"
            setText(t.text)
        }
        val (replaceRow, replaceSwitch) = ctx.switchRow("Xóa chữ cũ trong ô rồi mới dán", t.pasteMode == PasteMode.REPLACE_ALL)
        val pasteSection = ctx.column(ctx.groupLabel("NỘI DUNG DÁN"), textInput, replaceRow)

        // Kéo thả
        val (dragRow, dragInput) = ctx.numberRow("Thời gian kéo", t.dragMs, "ms")
        val (holdRow, holdInput) = ctx.numberRow("Nhấn giữ trước khi kéo", t.holdMs, "ms")
        val dragSection = ctx.column(
            ctx.groupLabel("KÉO THẢ"),
            dragRow,
            holdRow,
            ctx.helpText("Kéo vòng tròn viền đứt tới chỗ muốn thả. Giữ trước khi kéo: 0 = vuốt ngay; kéo biểu tượng / đồ vật thường cần 500–1000 ms."),
        )

        val updateSections = {
            holdSection.visibility = if (selected == TargetType.HOLD) View.VISIBLE else View.GONE
            pasteSection.visibility = if (selected == TargetType.PASTE) View.VISIBLE else View.GONE
            dragSection.visibility = if (selected == TargetType.DRAG) View.VISIBLE else View.GONE
        }
        val chips = ctx.choiceChips(listOf("Click", "Giữ", "Dán", "Kéo"), types.map { colorOf(it) }, types.indexOf(t.type)) {
            selected = types[it]
            updateSections()
        }
        val (delayRow, delayInput) = ctx.numberRow("Chờ sau bước này", t.delayMs, "ms")

        sheet.body.addView(chips, ctx.matchWrap(4))
        sheet.body.addView(delayRow, ctx.matchWrap(12))
        sheet.body.addView(holdSection)
        sheet.body.addView(pasteSection)
        sheet.body.addView(dragSection)
        updateSections()

        sheet.action("Xóa điểm", SheetAction.DANGER_TEXT, left = true) { removeMarker(marker) }
        sheet.action("Hủy")
        sheet.action("Lưu", SheetAction.PRIMARY) {
            val oldType = t.type
            t.type = selected
            t.delayMs = parseMs(delayInput.text.toString(), t.delayMs)
            t.text = textInput.text.toString()
            t.pasteMode = if (replaceSwitch.isChecked) PasteMode.REPLACE_ALL else PasteMode.PASTE_AT_CURSOR
            t.pressMs = parseMs(pressInput.text.toString(), t.pressMs).coerceIn(1L, MAX_GESTURE_MS)
            t.dragMs = parseMs(dragInput.text.toString(), t.dragMs).coerceIn(1L, MAX_GESTURE_MS)
            t.holdMs = parseMs(holdInput.text.toString(), t.holdMs).coerceAtMost(MAX_GESTURE_MS)
            if ((oldType == TargetType.DRAG) != (t.type == TargetType.DRAG)) {
                reloadMarkers()          // thêm / bỏ vòng tròn "thả"
            } else {
                paintMarker(marker)
                refreshLabels()
            }
            saveConfig()
        }
        sheet.show()
    }

    // ───────────────────────────── Cài đặt chung ─────────────────────────────

    /** Cài đặt: số vòng lặp, thời gian chờ mặc định, lưu / mở cấu hình, xóa tất cả. */
    private fun showSettingsDialog() {
        val sheet = OverlaySheet(
            this,
            "Cài đặt",
            "${markers.size} điểm · Cấu hình: ${config.currentProfile ?: "chưa lưu"}",
        )
        val ctx = sheet.ctx
        val (loopsRow, loopsInput) = ctx.numberRow("Số vòng lặp", config.loops.toLong(), "vòng")
        val (delayRow, delayInput) = ctx.numberRow("Chờ mặc định (điểm mới)", config.defaultDelayMs, "ms")
        val (applyRow, applySwitch) = ctx.switchRow("Áp dụng thời gian chờ cho tất cả điểm", false)

        sheet.body.addView(ctx.groupLabel("CHẠY"))
        sheet.body.addView(loopsRow)
        sheet.body.addView(ctx.helpText("0 vòng = lặp vô hạn, tới khi bấm Dừng."))
        sheet.body.addView(delayRow)
        sheet.body.addView(applyRow)

        val savedCount = ProfileStore.names(this).size
        val saveButton = ctx.outlineButton("Lưu cấu hình…")
        val openButton = ctx.outlineButton("Mở cấu hình đã lưu ($savedCount)")
        sheet.body.addView(ctx.groupLabel("CẤU HÌNH"))
        sheet.body.addView(saveButton, ctx.matchWrap(0))
        sheet.body.addView(openButton, ctx.matchWrap(8))

        val applyFields = {
            config.loops = parseMs(loopsInput.text.toString(), config.loops.toLong()).toInt()
            config.defaultDelayMs = parseMs(delayInput.text.toString(), config.defaultDelayMs)
            if (applySwitch.isChecked) config.targets.forEach { it.delayMs = config.defaultDelayMs }
            saveConfig()
        }
        // Lưu luôn số vòng lặp / thời gian chờ vừa sửa rồi mới mở hộp thoại cấu hình
        saveButton.setOnClickListener {
            applyFields()
            sheet.dialog.dismiss()
            showSaveProfileDialog()
        }
        openButton.setOnClickListener {
            applyFields()
            sheet.dialog.dismiss()
            showOpenProfileDialog()
        }

        sheet.action("Xóa tất cả", SheetAction.DANGER_TEXT, left = true) { confirmClearAll() }
        sheet.action("Hủy")
        sheet.action("Lưu", SheetAction.PRIMARY) { applyFields() }
        sheet.show()
    }

    private fun confirmClearAll() {
        if (markers.isEmpty()) return
        confirmSheet(
            "Xóa tất cả ${markers.size} điểm?",
            "Các điểm trên màn hình sẽ bị xóa. Cấu hình đã lưu không bị ảnh hưởng.",
            "Xóa tất cả",
            danger = true,
        ) { markers.toList().forEach { removeMarker(it) } }
    }

    /** Hộp thoại hỏi lại: tiêu đề, nội dung, nút Hủy và nút xác nhận. */
    private fun confirmSheet(heading: String, message: String, okLabel: String, danger: Boolean = false, onOk: () -> Unit) {
        val sheet = OverlaySheet(this, heading)
        sheet.body.addView(sheet.ctx.bodyText(message))
        sheet.action("Hủy")
        sheet.action(okLabel, if (danger) SheetAction.DANGER else SheetAction.PRIMARY, onClick = onOk)
        sheet.show()
    }

    // ───────────────────────────── Lưu / mở cấu hình ─────────────────────────────

    /** Lưu toàn bộ điểm hiện tại (kèm số vòng lặp, thời gian chờ mặc định) thành 1 cấu hình có tên. */
    private fun showSaveProfileDialog() {
        if (config.targets.isEmpty()) {
            toast("Chưa có điểm nào để lưu. Bấm ＋ để thêm điểm.")
            return
        }
        val existing = ProfileStore.names(this)
        val sheet = OverlaySheet(this, "Lưu cấu hình", "${config.targets.size} điểm · ${loopsText()}")
        val ctx = sheet.ctx
        val nameInput = ctx.inputBox().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            hint = "Ví dụ: Gửi tin Zalo"
            setText(config.currentProfile ?: "Cấu hình ${existing.size + 1}")
            setSelectAllOnFocus(true)
        }
        sheet.body.addView(ctx.groupLabel("TÊN CẤU HÌNH"))
        sheet.body.addView(nameInput)
        if (existing.isNotEmpty()) sheet.body.addView(ctx.helpText("Đã lưu: " + existing.joinToString(", ")))
        sheet.action("Hủy")
        sheet.action("Lưu", SheetAction.PRIMARY) {
            val name = nameInput.text.toString().trim()
            when {
                name.isEmpty() -> toast("Hãy nhập tên cấu hình")
                name in existing && name != config.currentProfile -> confirmSheet(
                    "Ghi đè “$name”?",
                    "Cấu hình cũ cùng tên sẽ được thay bằng ${config.targets.size} điểm hiện tại.",
                    "Ghi đè",
                ) { saveProfile(name) }
                else -> saveProfile(name)
            }
        }
        sheet.show(showKeyboard = true)
        nameInput.requestFocus()
    }

    /** Lưu các điểm đang đặt thành cấu hình [name] (màn hình chính cũng gọi, với notify = false). */
    fun saveProfile(name: String, notify: Boolean = true) {
        ProfileStore.save(this, name, config)
        config.currentProfile = name
        saveConfig()
        if (notify) toast("Đã lưu cấu hình “$name” (${config.targets.size} điểm)")
    }

    /** Cho màn hình chính: tên cấu hình đang mở và số điểm đang đặt. */
    val currentProfileName: String? get() = if (::config.isInitialized) config.currentProfile else null
    val pointCount: Int get() = if (::config.isInitialized) config.targets.size else 0

    fun onProfileRenamed(oldName: String, newName: String) {
        if (config.currentProfile == oldName) {
            config.currentProfile = newName
            saveConfig()
        }
    }

    fun onProfileDeleted(name: String) {
        if (config.currentProfile == name) {
            config.currentProfile = null
            saveConfig()
        }
    }

    /** Danh sách cấu hình đã lưu: chạm dòng hoặc "Mở" để dùng, "Xóa" để xóa. */
    private fun showOpenProfileDialog() {
        val names = ProfileStore.names(this)
        if (names.isEmpty()) {
            toast("Chưa có cấu hình nào. Đặt điểm xong bấm ⚙ → Lưu cấu hình.")
            return
        }
        val sheet = OverlaySheet(this, "Cấu hình đã lưu", "Chọn cấu hình để dùng")
        val ctx = sheet.ctx
        names.forEachIndexed { i, name ->
            if (i > 0) sheet.body.addView(ctx.thinDivider())
            val isCurrent = name == config.currentProfile
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply {
                        text = name
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(UI_TEXT)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = ProfileStore.summary(this@ClickService, name) + if (isCurrent) "  ·  Đang dùng" else ""
                        textSize = 13f
                        setTextColor(if (isCurrent) UI_GREEN else UI_TEXT_2)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ctx.textAction("Mở").apply {
                    setOnClickListener {
                        sheet.dialog.dismiss()
                        confirmLoad(name)
                    }
                })
                addView(ctx.textAction("Xóa", UI_DANGER).apply {
                    setOnClickListener {
                        sheet.dialog.dismiss()
                        confirmDelete(name)
                    }
                })
            }
            sheet.body.addView(row)
        }
        sheet.action("Đóng")
        sheet.show()
    }

    private fun confirmLoad(name: String) {
        if (config.targets.isEmpty()) {   // màn hình đang trống thì mở luôn, không cần hỏi
            loadProfile(name)
            return
        }
        confirmSheet(
            "Mở “$name”?",
            "${config.targets.size} điểm đang có trên màn hình sẽ được thay bằng các điểm của cấu hình này.",
            "Mở",
        ) { loadProfile(name) }
    }

    /** Thay các điểm hiện tại bằng cấu hình đã lưu (màn hình chính cũng gọi, với notify = false). */
    fun loadProfile(name: String, notify: Boolean = true): Boolean {
        val profile = ProfileStore.load(this, name)
        if (profile == null) {
            if (notify) toast("Không đọc được cấu hình “$name”")
            return false
        }
        stopPlaying()
        config.targets.clear()
        config.targets.addAll(profile.targets)
        config.loops = profile.loops
        config.defaultDelayMs = profile.defaultDelayMs
        config.currentProfile = name
        saveConfig()
        if (panel != null) reloadMarkers()
        if (notify) toast("Đã mở “$name” (${profile.targets.size} điểm, ${loopsText()})")
        return true
    }

    private fun confirmDelete(name: String) {
        confirmSheet(
            "Xóa “$name”?",
            "Chỉ xóa bản đã lưu, các điểm đang có trên màn hình vẫn giữ nguyên.",
            "Xóa",
            danger = true,
        ) {
            ProfileStore.delete(this, name)
            onProfileDeleted(name)
            toast("Đã xóa “$name”")
            if (ProfileStore.names(this).isNotEmpty()) showOpenProfileDialog()
        }
    }

    private fun loopsText(): String = if (config.loops == 0) "lặp vô hạn" else "${config.loops} vòng"

    private fun parseMs(s: String, fallback: Long): Long = s.trim().toLongOrNull()?.coerceAtLeast(0) ?: fallback

    // ───────────────────────────── Tiện ích cửa sổ ─────────────────────────────

    private fun overlayParams(
        px: Int,
        py: Int,
        w: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = px
        y = py
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Chạm vào [handle] rồi kéo → di chuyển cả cửa sổ [window] (gọi [onMove] trong lúc kéo).
     * Chạm nhẹ (không kéo quá ngưỡng) → gọi [onTap]. Nhờ vậy nút vừa bấm được vừa dùng để kéo bảng.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(
        handle: View,
        window: View,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)?,
        onMove: (() -> Unit)? = null,
        onDragEnd: () -> Unit,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var maxX = 0
        var maxY = 0
        var dragging = false
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    val (sw, sh) = screenSize()
                    maxX = max(0, sw - window.width)
                    maxY = max(0, sh - window.height)
                    dragging = false
                    if (onTap != null) {
                        v.drawableHotspotChanged(e.x, e.y)
                        v.isPressed = true          // hiệu ứng nhấn nút
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.isPressed = false         // đang kéo thì không tính là bấm
                    }
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        safeUpdate(window, params)
                        onMove?.invoke()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) onDragEnd() else onTap?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) onDragEnd()
                }
            }
            true
        }
    }

    private fun keepOnScreen() {
        val (sw, sh) = screenSize()
        val p = panel
        val pp = panelParams
        if (p != null && pp != null) clampWindow(p, pp, sw, sh)
        val b = bubble
        val bp = bubbleParams
        if (b != null && bp != null) clampWindow(b, bp, sw, sh)
        for (m in markers) {
            for ((v, lp) in m.windows()) clampWindow(v, lp, sw, sh)
        }
        val lv = lineView
        val lp = lineParams
        if (lv != null && lp != null) {
            lp.width = sw
            lp.height = sh
            safeUpdate(lv, lp)
            lv.invalidate()
        }
    }

    private fun clampWindow(view: View, lp: WindowManager.LayoutParams, sw: Int, sh: Int) {
        val w = if (view.width > 0) view.width else dp(64)
        val h = if (view.height > 0) view.height else dp(64)
        val nx = lp.x.coerceIn(0, max(0, sw - w))
        val ny = lp.y.coerceIn(0, max(0, sh - h))
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            safeUpdate(view, lp)
        }
    }

    /** Kích thước toàn màn hình (px) theo hướng xoay hiện tại. */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            // cửa sổ đã bị gỡ – bỏ qua
        }
    }

    private fun safeRemove(view: View) {
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            // đã gỡ rồi – bỏ qua
        }
    }

    private fun saveConfig() {
        if (::config.isInitialized) ConfigStore.save(this, config)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
