package com.example.autoclickpaste

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

/** Màn hình chính: BẬT / TẮT Auto Click, quản lý cấu hình đã lưu, mở màn hình Giao diện. */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var powerButton: TextView
    private lateinit var shortcutWarning: LinearLayout
    private lateinit var profileList: LinearLayout
    private lateinit var profileEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(24))
            clipToPadding = false
        }

        // ── Thẻ BẬT / TẮT ──
        val powerCard = card()
        powerCard.addView(sectionHeader("AUTO CLICK"))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        statusDot = dot(UI_IDLE)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UI_TEXT)
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(statusTitle)
        powerCard.addView(statusRow)
        statusDesc = TextView(this).apply {
            textSize = 14f
            setTextColor(UI_TEXT_2)
            setPadding(0, dp(4), 0, dp(16))
        }
        powerCard.addView(statusDesc)
        powerButton = pillButton("BẬT", UI_PRIMARY).apply { setOnClickListener { togglePower() } }
        powerCard.addView(powerButton, matchWrap())
        content.addView(powerCard)

        // ── Cảnh báo nút tròn Trợ năng của Android (chỉ hiện khi đang bật phím tắt) ──
        shortcutWarning = card().apply {
            background = roundedBg(UI_WARN_BG, dpF(18f), UI_WARN_BORDER, dp(1))
            elevation = 0f
            addView(TextView(this@MainActivity).apply {
                text = "Nút tròn Trợ năng của Android đang bật cho app này nên hiện ở mép màn hình. App không cần nút đó."
                textSize = 14f
                setTextColor(UI_WARN_TEXT)
            })
            addView(textAction("Tắt nút tròn", UI_WARN_TEXT).apply {
                setOnClickListener {
                    openServiceSettings()
                    toast("Chọn ${getString(R.string.service_name)} → tắt “Phím tắt ${getString(R.string.service_name)}”")
                }
            }, wrapWrap(6))
        }
        content.addView(shortcutWarning)

        // ── Thẻ cấu hình đã lưu ──
        val profileCard = card()
        profileCard.addView(sectionHeader("CẤU HÌNH ĐÃ LƯU"))
        profileEmpty = TextView(this).apply {
            text = "Chưa có cấu hình nào. Đặt các điểm trên bảng điều khiển rồi bấm lưu bên dưới."
            textSize = 14f
            setTextColor(UI_TEXT_2)
            setPadding(0, dp(10), 0, dp(4))
        }
        profileCard.addView(profileEmpty)
        profileList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        profileCard.addView(profileList)
        profileCard.addView(
            outlineButton("＋  Lưu cấu hình hiện tại").apply { setOnClickListener { askSaveCurrent() } },
            matchWrap(12),
        )
        content.addView(profileCard)

        // ── Thẻ cài đặt ──
        val settingsCard = card()
        settingsCard.addView(sectionHeader("CÀI ĐẶT"))
        settingsCard.addView(
            navRow("🎨", "Giao diện", "Kích thước điểm, bảng điều khiển, độ đậm") {
                startActivity(Intent(this, AppearanceActivity::class.java))
            },
            matchWrap(6),
        )
        content.addView(settingsCard)

        setScreen(appHeader(getString(R.string.app_name), "Click · Nhấn giữ · Dán chữ · Kéo thả"), content)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshProfiles()
        // Dịch vụ vừa mở lại app sau khi cấp quyền
        if (intent?.getBooleanExtra(EXTRA_JUST_GRANTED, false) == true) {
            intent.removeExtra(EXTRA_JUST_GRANTED)
            toast("Đã cấp quyền ✓ Bảng điều khiển đã hiện – mở app cần auto rồi bấm ▶")
        }
    }

    // ───────────────────────────── Bật / tắt ─────────────────────────────

    private fun togglePower() {
        if (ClickService.instance?.isOverlayShown == true) turnOff() else turnOn()
    }

    /** BẬT: lần đầu thì dẫn đi cấp quyền Trợ năng; đã có quyền thì hiện bảng điều khiển ngay. */
    private fun turnOn() {
        val service = ClickService.instance
        if (service == null) {
            // Ghi nhớ "đang bật" – dịch vụ kết nối xong sẽ tự hiện bảng điều khiển
            val config = ConfigStore.load(this)
            config.panelVisible = true
            ConfigStore.save(this, config)
            GrantReturn.mark(this)   // bật quyền xong thì dịch vụ tự mở lại app
            toast(
                "Lần đầu: chọn ${getString(R.string.service_name)} → chỉ bật “Sử dụng ${getString(R.string.service_name)}” " +
                    "→ Cho phép. KHÔNG cần bật “Phím tắt”."
            )
            openServiceSettings()
        } else {
            service.showOverlay()
            toast("Đã bật. Mở app cần auto, đặt điểm rồi bấm ▶")
            moveTaskToBack(true)
        }
        refreshStatus()
    }

    /** TẮT: cất bảng điều khiển và các điểm, dừng nếu đang chạy. */
    private fun turnOff() {
        val service = ClickService.instance
        if (service != null) {
            service.hideOverlay()
        } else {
            val config = ConfigStore.load(this)
            config.panelVisible = false
            ConfigStore.save(this, config)
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val service = ClickService.instance
        val on = service?.isOverlayShown == true
        val dotColor: Int
        val titleText: String
        val descText: String
        when {
            service == null -> {
                dotColor = UI_IDLE
                titleText = "Chưa cấp quyền"
                descText = "Bấm BẬT để cấp quyền Trợ năng – chỉ cần làm 1 lần."
            }
            on -> {
                dotColor = UI_GREEN
                titleText = "Đang bật"
                descText = "Bảng điều khiển đang hiện trên màn hình."
            }
            else -> {
                dotColor = UI_IDLE
                titleText = "Đang tắt"
                descText = "Bấm BẬT để hiện bảng điều khiển."
            }
        }
        statusDot.background = roundedBg(dotColor, dpF(6f))
        statusTitle.text = titleText
        statusDesc.text = descText
        powerButton.text = if (on) "TẮT" else "BẬT"
        setPillColor(powerButton, if (on) UI_SLATE else UI_PRIMARY)
        shortcutWarning.visibility = if (isShortcutButtonOn()) View.VISIBLE else View.GONE
    }

    /**
     * Phím tắt Trợ năng (nút tròn ở mép màn hình) của app có đang bật không.
     * Đọc cài đặt hệ thống "accessibility_button_targets"; máy không cho đọc thì coi như không bật.
     */
    private fun isShortcutButtonOn(): Boolean = try {
        val me = ComponentName(this, ClickService::class.java)
        Settings.Secure.getString(contentResolver, "accessibility_button_targets")
            .orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it) == me }
    } catch (e: Exception) {
        false
    }

    /** Mở Cài đặt Trợ năng; trên Pixel / Android gốc sẽ cuộn tới và làm nổi bật mục của app. */
    private fun openServiceSettings() {
        val component = ComponentName(this, ClickService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Không mở được Cài đặt Trợ năng: ${e.message}")
        }
    }

    // ───────────────────────────── Cấu hình đã lưu ─────────────────────────────

    private fun refreshProfiles() {
        profileList.removeAllViews()
        val names = ProfileStore.names(this)
        val current = ProfileActions.currentName(this)
        profileEmpty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        names.forEachIndexed { i, name ->
            if (i > 0) profileList.addView(thinDivider())
            profileList.addView(profileRow(name, name == current))
        }
    }

    /** 1 dòng cấu hình: tên, số điểm, nút Mở và menu ⋮ (Đổi tên / Xóa). */
    private fun profileRow(name: String, isCurrent: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val texts = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UI_TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@MainActivity).apply {
                text = ProfileStore.summary(this@MainActivity, name) + if (isCurrent) "  ·  Đang dùng" else ""
                textSize = 13f
                setTextColor(if (isCurrent) UI_GREEN else UI_TEXT_2)
            })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(textAction("Mở").apply { setOnClickListener { askOpen(name) } })
        addView(textAction("⋮", UI_TEXT_2).apply {
            textSize = 20f
            setOnClickListener { v -> showProfileMenu(v, name) }
        })
    }

    private fun showProfileMenu(anchor: View, name: String) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Đổi tên")
            menu.add(0, 2, 1, "Xóa")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> askRename(name)
                    2 -> askDelete(name)
                }
                true
            }
            show()
        }
    }

    private fun askSaveCurrent() {
        val count = ProfileActions.pointCount(this)
        if (count == 0) {
            toast("Chưa có điểm nào. Bật Auto Click và thêm điểm trước.")
            return
        }
        val existing = ProfileStore.names(this)
        val current = ProfileActions.currentName(this)
        askName("Lưu cấu hình ($count điểm)", current ?: "Cấu hình ${existing.size + 1}", "Lưu") { name ->
            if (name in existing && name != current) {
                confirm("Ghi đè “$name”?", "Cấu hình cũ cùng tên sẽ được thay bằng $count điểm hiện tại.", "Ghi đè") {
                    saveAs(name)
                }
            } else {
                saveAs(name)
            }
        }
    }

    private fun saveAs(name: String) {
        ProfileActions.saveCurrent(this, name)
        toast("Đã lưu “$name”")
        refreshProfiles()
    }

    private fun askOpen(name: String) {
        val count = ProfileActions.pointCount(this)
        val open = {
            if (ProfileActions.open(this, name)) toast("Đã mở “$name”") else toast("Không đọc được cấu hình “$name”")
            refreshProfiles()
        }
        if (count == 0) {
            open()
        } else {
            confirm("Mở “$name”?", "$count điểm đang đặt sẽ được thay bằng các điểm của cấu hình này.", "Mở", open)
        }
    }

    private fun askRename(name: String) {
        askName("Đổi tên cấu hình", name, "Lưu") { newName ->
            when {
                newName == name -> Unit
                newName in ProfileStore.names(this) -> toast("Đã có cấu hình tên “$newName”")
                ProfileActions.rename(this, name, newName) -> refreshProfiles()
                else -> toast("Không đổi tên được")
            }
        }
    }

    private fun askDelete(name: String) {
        confirm("Xóa “$name”?", "Chỉ xóa bản đã lưu, các điểm đang dùng vẫn giữ nguyên.", "Xóa") {
            ProfileActions.delete(this, name)
            refreshProfiles()
        }
    }

    // ───────────────────────────── Hộp thoại ─────────────────────────────

    private fun dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun askName(dialogTitle: String, initial: String, positive: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        val dialog = dialogBuilder()
            .setTitle(dialogTitle)
            .setView(box)
            .setPositiveButton(positive) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) toast("Hãy nhập tên") else onOk(value)
            }
            .setNegativeButton("Hủy", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun confirm(dialogTitle: String, message: String, positive: String, onOk: () -> Unit) {
        dialogBuilder()
            .setTitle(dialogTitle)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /** Dịch vụ gắn cờ này khi tự mở lại app sau khi người dùng vừa cấp quyền. */
        const val EXTRA_JUST_GRANTED = "just_granted"
    }
}
