package com.jqorz.pcmonitor4dlg.model

enum class ExitAction {
    ASK, MINIMIZE, EXIT
}

data class AppSettings(
    val autoStartOnBoot: Boolean = false,
    val autoConnectLastDevice: Boolean = false,
    val deviceThemeId: Int = 0,
    val exitAction: ExitAction = ExitAction.ASK
)

/** 设备端主题名（与固件 themes[] 表顺序一致，下标即 0xB1 主题号） */
val DeviceThemeNames = listOf(
    "黑·琥珀", "海军·冰川青", "墨绿·薄荷", "深紫·丁香",
    "黑·珊瑚", "白·砖红", "米白·赭棕", "淡青·深海"
)

/** 各主题背景色（屏上实际显示的RGB888，与强调色一起用于选择器预览色块） */
val DeviceThemeBgs = listOf(
    0x000000, 0x000829, 0x001008, 0x100829,
    0x000000, 0xFFFFFF, 0xF7F3E7, 0xEFF7F7
)

/** 各主题强调色（屏上实际显示的RGB888，用于选择器色点） */
val DeviceThemeAccents = listOf(
    0xFFE300, 0x31D7FF, 0x39E38C, 0xC67DFF,
    0xFF4D4A, 0xE73839, 0xB55D08, 0x087D94
)

/** 深色背景主题数（列表前N项为深色系，其余为浅色系；选择器据此分组画分隔线） */
val DeviceThemeDarkCount = 5

/** 各主题RGB565三元组(bg,ink,accent)，0xB2下发给设备的实际色值 */
val DeviceThemeColors565 = listOf(
    Triple(0x0000, 0xFFFF, 0xFF00),  // 黑·琥珀
    Triple(0x0045, 0xEF9F, 0x36BF),  // 海军·冰川青
    Triple(0x0081, 0xEFBE, 0x3F11),  // 墨绿·薄荷
    Triple(0x1045, 0xF75F, 0xC3FF),  // 深紫·丁香
    Triple(0x0000, 0xFFFF, 0xFA69),  // 黑·珊瑚
    Triple(0xFFFF, 0x0000, 0xE1C7),  // 白·砖红
    Triple(0xF79C, 0x0000, 0xB2E1),  // 米白·赭棕
    Triple(0xEFBE, 0x0000, 0x0BF2)   // 淡青·深海
)
