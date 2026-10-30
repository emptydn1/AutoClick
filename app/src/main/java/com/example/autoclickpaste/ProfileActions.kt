package com.example.autoclickpaste

import android.content.Context

/**
 * Lưu / mở / đổi tên / xóa cấu hình từ màn hình chính.
 * Nếu dịch vụ đang chạy thì đi qua dịch vụ, để các điểm trên màn hình cập nhật ngay
 * và cấu hình đang dùng không bị dịch vụ ghi đè.
 */
object ProfileActions {

    /** Tên cấu hình đang mở (null = chưa lưu). */
    fun currentName(context: Context): String? {
        val service = ClickService.instance
        return if (service != null) service.currentProfileName else ConfigStore.load(context).currentProfile
    }

    /** Số điểm đang đặt. */
    fun pointCount(context: Context): Int {
        val service = ClickService.instance
        return service?.pointCount ?: ConfigStore.load(context).targets.size
    }

    /** Lưu các điểm đang đặt thành cấu hình [name] (ghi đè nếu trùng tên). */
    fun saveCurrent(context: Context, name: String) {
        val service = ClickService.instance
        if (service != null) {
            service.saveProfile(name, notify = false)
            return
        }
        val config = ConfigStore.load(context)
        ProfileStore.save(context, name, config)
        config.currentProfile = name
        ConfigStore.save(context, config)
    }

    /** Thay các điểm đang đặt bằng cấu hình [name]. */
    fun open(context: Context, name: String): Boolean {
        val service = ClickService.instance
        if (service != null) return service.loadProfile(name, notify = false)
        val profile = ProfileStore.load(context, name) ?: return false
        val config = ConfigStore.load(context)
        config.targets.clear()
        config.targets.addAll(profile.targets)
        config.loops = profile.loops
        config.defaultDelayMs = profile.defaultDelayMs
        config.currentProfile = name
        ConfigStore.save(context, config)
        return true
    }

    fun rename(context: Context, oldName: String, newName: String): Boolean {
        if (!ProfileStore.rename(context, oldName, newName)) return false
        val service = ClickService.instance
        if (service != null) {
            service.onProfileRenamed(oldName, newName)
        } else {
            val config = ConfigStore.load(context)
            if (config.currentProfile == oldName) {
                config.currentProfile = newName
                ConfigStore.save(context, config)
            }
        }
        return true
    }

    fun delete(context: Context, name: String) {
        ProfileStore.delete(context, name)
        val service = ClickService.instance
        if (service != null) {
            service.onProfileDeleted(name)
        } else {
            val config = ConfigStore.load(context)
            if (config.currentProfile == name) {
                config.currentProfile = null
                ConfigStore.save(context, config)
            }
        }
    }
}
