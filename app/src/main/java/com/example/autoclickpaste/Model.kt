package com.example.autoclickpaste

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Loại điểm: chỉ click, nhấn giữ, click rồi dán chữ, hoặc kéo thả từ điểm này tới điểm "thả". */
enum class TargetType { CLICK, HOLD, PASTE, DRAG }

/** Cách đưa chữ vào ô nhập. */
enum class PasteMode {
    /** Dán tại vị trí con trỏ, giống bấm "Dán" (giữ nguyên chữ cũ). */
    PASTE_AT_CURSOR,

    /** Xóa hết chữ cũ trong ô rồi mới dán. */
    REPLACE_ALL,
}

/**
 * Một điểm trên màn hình.
 * [x], [y] là vị trí góc trên-trái của vòng tròn (toạ độ cửa sổ nổi, đơn vị px).
 * Cú click thật sự rơi vào TÂM vòng tròn.
 */
class ClickTarget(
    var type: TargetType,
    var x: Int,
    var y: Int,
    var delayMs: Long,                 // thời gian chờ SAU bước này
    var text: String = "",             // nội dung cần dán (chỉ dùng cho điểm PASTE)
    var pasteMode: PasteMode = PasteMode.PASTE_AT_CURSOR,
    var x2: Int = -1,                  // vị trí vòng tròn "thả" (điểm DRAG); -1 = chưa đặt
    var y2: Int = -1,
    var dragMs: Long = 500,            // thời gian kéo từ điểm đầu tới điểm thả
    var holdMs: Long = 0,              // nhấn giữ ở điểm đầu trước khi kéo (0 = vuốt ngay)
    var pressMs: Long = 1000,          // thời gian nhấn giữ (điểm HOLD)
    var clicks: Int = 1,               // số lần click liên tiếp (điểm CLICK)
) {
    companion object {
        const val MAX_CLICKS = 1000
    }
}

/** Toàn bộ cấu hình đang dùng, được lưu tự động để lần sau mở vẫn còn. */
class Config(
    val targets: MutableList<ClickTarget> = mutableListOf(),
    var loops: Int = 0,                // 0 = lặp vô hạn
    var defaultDelayMs: Long = 1000,   // thời gian chờ mặc định cho điểm mới
    var panelX: Int = 0,
    var panelY: Int = 300,
    var panelVisible: Boolean = true,
    var currentProfile: String? = null, // tên cấu hình đã lưu đang mở (null = chưa lưu)
)

/** Lưu / đọc cấu hình đang dùng dạng JSON trong SharedPreferences. */
object ConfigStore {
    private const val PREFS = "auto_click_paste"
    private const val KEY = "config"

    fun load(context: Context): Config {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return Config()
        return try {
            val o = JSONObject(raw)
            val config = Config(
                loops = o.optInt("loops", 0),
                defaultDelayMs = o.optLong("defaultDelayMs", 1000),
                panelX = o.optInt("panelX", 0),
                panelY = o.optInt("panelY", 300),
                panelVisible = o.optBoolean("panelVisible", true),
                currentProfile = o.optString("currentProfile", "").ifEmpty { null },
            )
            config.targets.addAll(targetsFromJson(o.optJSONArray("targets") ?: JSONArray()))
            config
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(context: Context, config: Config) {
        val o = JSONObject()
            .put("loops", config.loops)
            .put("defaultDelayMs", config.defaultDelayMs)
            .put("panelX", config.panelX)
            .put("panelY", config.panelY)
            .put("panelVisible", config.panelVisible)
            .put("currentProfile", config.currentProfile ?: "")
            .put("targets", targetsToJson(config.targets))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, o.toString())
            .apply()
    }

    /** Danh sách điểm → JSON (dùng chung cho cấu hình đang dùng và cấu hình đã lưu). */
    fun targetsToJson(targets: List<ClickTarget>): JSONArray {
        val arr = JSONArray()
        for (t in targets) {
            arr.put(
                JSONObject()
                    .put("type", t.type.name)
                    .put("x", t.x)
                    .put("y", t.y)
                    .put("delayMs", t.delayMs)
                    .put("text", t.text)
                    .put("pasteMode", t.pasteMode.name)
                    .put("x2", t.x2)
                    .put("y2", t.y2)
                    .put("dragMs", t.dragMs)
                    .put("holdMs", t.holdMs)
                    .put("pressMs", t.pressMs)
                    .put("clicks", t.clicks)
            )
        }
        return arr
    }

    /** JSON → danh sách điểm mới (bản sao độc lập). */
    fun targetsFromJson(arr: JSONArray): MutableList<ClickTarget> {
        val list = mutableListOf<ClickTarget>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            list.add(
                ClickTarget(
                    type = enumOr(t.optString("type"), TargetType.CLICK),
                    x = t.optInt("x"),
                    y = t.optInt("y"),
                    delayMs = t.optLong("delayMs", 1000),
                    text = t.optString("text", ""),
                    pasteMode = enumOr(t.optString("pasteMode"), PasteMode.PASTE_AT_CURSOR),
                    x2 = t.optInt("x2", -1),
                    y2 = t.optInt("y2", -1),
                    dragMs = t.optLong("dragMs", 500),
                    holdMs = t.optLong("holdMs", 0),
                    pressMs = t.optLong("pressMs", 1000),
                    clicks = t.optInt("clicks", 1).coerceIn(1, ClickTarget.MAX_CLICKS),
                )
            )
        }
        return list
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}

/** Một cấu hình đã lưu theo tên: các điểm + số vòng lặp + thời gian chờ mặc định. */
class Profile(
    val name: String,
    val targets: MutableList<ClickTarget>,
    val loops: Int,
    val defaultDelayMs: Long,
)

/** Các cấu hình đã lưu theo tên (lưu / mở / xóa), giống mục "Quản lý cấu hình" của app auto clicker. */
object ProfileStore {
    private const val PREFS = "auto_click_paste"
    private const val KEY = "profiles"   // JSON: { "tên": { targets, loops, defaultDelayMs, savedAt } }

    private fun readAll(context: Context): JSONObject = try {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun writeAll(context: Context, all: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, all.toString())
            .apply()
    }

    /** Tên các cấu hình, cái lưu gần nhất đứng đầu. */
    fun names(context: Context): List<String> {
        val all = readAll(context)
        return all.keys().asSequence().toList()
            .sortedByDescending { all.optJSONObject(it)?.optLong("savedAt", 0) ?: 0 }
    }

    /** Mô tả ngắn: số điểm của cấu hình. */
    fun summary(context: Context, name: String): String {
        val o = readAll(context).optJSONObject(name) ?: return ""
        return "${o.optJSONArray("targets")?.length() ?: 0} điểm"
    }

    fun save(context: Context, name: String, config: Config) {
        val all = readAll(context)
        all.put(
            name,
            JSONObject()
                .put("targets", ConfigStore.targetsToJson(config.targets))
                .put("loops", config.loops)
                .put("defaultDelayMs", config.defaultDelayMs)
                .put("savedAt", System.currentTimeMillis())
        )
        writeAll(context, all)
    }

    fun load(context: Context, name: String): Profile? {
        val o = readAll(context).optJSONObject(name) ?: return null
        return Profile(
            name = name,
            targets = ConfigStore.targetsFromJson(o.optJSONArray("targets") ?: JSONArray()),
            loops = o.optInt("loops", 0),
            defaultDelayMs = o.optLong("defaultDelayMs", 1000),
        )
    }

    fun delete(context: Context, name: String) {
        val all = readAll(context)
        all.remove(name)
        writeAll(context, all)
    }

    /** Đổi tên; trả về false nếu không có cấu hình [oldName] hoặc [newName] đã tồn tại. */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val all = readAll(context)
        val profile = all.optJSONObject(oldName) ?: return false
        if (all.has(newName)) return false
        all.remove(oldName)
        all.put(newName, profile)
        writeAll(context, all)
        return true
    }
}

/**
 * Ghi nhớ "người dùng vừa bấm BẬT trong app để đi cấp quyền Trợ năng".
 * Dịch vụ kết nối xong sẽ tự mở lại app (Android không tự quay về sau khi bật quyền trong Cài đặt).
 */
object GrantReturn {
    private const val PREFS = "auto_click_paste"
    private const val KEY = "returnAfterGrantAt"
    private const val VALID_MS = 5 * 60 * 1000L   // chỉ tính nếu bật quyền trong vòng 5 phút

    fun mark(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY, System.currentTimeMillis())
            .apply()
    }

    /** true nếu vừa được đánh dấu (trong 5 phút); đồng thời xóa dấu. */
    fun consume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY, 0L)
        if (at == 0L) return false
        prefs.edit().remove(KEY).apply()
        return System.currentTimeMillis() - at in 0..VALID_MS
    }
}

/** Giao diện: kích thước điểm, kích cỡ / hướng bảng điều khiển, chữ dưới nút, độ đậm. */
class Appearance(
    var markerSizeDp: Int = 56,        // kích thước vòng tròn: 32..100 dp
    var panelScale: Int = 100,         // kích cỡ bảng điều khiển: 50..150 %
    var horizontal: Boolean = false,   // bảng nằm ngang (false = dọc)
    var showCaptions: Boolean = false, // hiện chữ nhỏ dưới biểu tượng (mặc định tắt cho gọn)
    var opacity: Int = 90,             // độ đậm của bảng và các điểm: 30..100 %
    var buttonGap: Int = 4,            // khoảng cách giữa các nút trên bảng: 0 (sát) .. 20 (rộng) dp
) {
    companion object {
        const val GAP_MIN = 0
        const val GAP_MAX = 20
        const val MARKER_MIN = 32
        const val MARKER_MAX = 100
        const val SCALE_MIN = 50
        const val SCALE_MAX = 150
        const val OPACITY_MIN = 30
        const val OPACITY_MAX = 100
    }
}

/** Lưu giao diện riêng với cấu hình điểm, để màn hình Giao diện và dịch vụ không ghi đè nhau. */
object AppearanceStore {
    private const val PREFS = "auto_click_paste"
    private const val KEY = "appearance"

    fun load(context: Context): Appearance {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return Appearance()
        return try {
            val o = JSONObject(raw)
            Appearance(
                markerSizeDp = o.optInt("markerSizeDp", 56).coerceIn(Appearance.MARKER_MIN, Appearance.MARKER_MAX),
                panelScale = o.optInt("panelScale", 100).coerceIn(Appearance.SCALE_MIN, Appearance.SCALE_MAX),
                horizontal = o.optBoolean("horizontal", false),
                showCaptions = o.optBoolean("showCaptions", false),
                opacity = o.optInt("opacity", 90).coerceIn(Appearance.OPACITY_MIN, Appearance.OPACITY_MAX),
                buttonGap = o.optInt("buttonGap", 4).coerceIn(Appearance.GAP_MIN, Appearance.GAP_MAX),
            )
        } catch (e: Exception) {
            Appearance()
        }
    }

    fun save(context: Context, a: Appearance) {
        val o = JSONObject()
            .put("markerSizeDp", a.markerSizeDp)
            .put("panelScale", a.panelScale)
            .put("horizontal", a.horizontal)
            .put("showCaptions", a.showCaptions)
            .put("opacity", a.opacity)
            .put("buttonGap", a.buttonGap)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, o.toString())
            .apply()
    }
}
