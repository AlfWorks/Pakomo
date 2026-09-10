package com.alphynia.pakomo.core.model

/**
 * 应用界面语言。纯数据枚举(不依赖 Compose),因此模型层的枚举标签、服务通知等非 Compose 场景也能用
 * [tr] 取到对应语言的文案。UI 侧通过 `LocalAppLanguage` + `t(zh, en)` 就近书写双语文案。
 *
 * 暂时只支持中文与英文;新增语言时在此加一项并给 [tr] 扩展即可。
 */
enum class AppLanguage(val selfLabel: String) {
    ZH("中文"),
    EN("English");

    /** 按当前语言在中英文之间取值。 */
    fun tr(zh: String, en: String): String = if (this == EN) en else zh

    companion object {
        val DEFAULT = ZH

        /**
         * First-launch default when the user hasn't picked a language yet: follow the system locale —
         * Chinese system → [ZH], anything else → [EN] (the only two languages we ship). Once the user
         * picks a language in Settings it is persisted and [fromName] matches it, so their choice wins.
         */
        fun systemDefault(): AppLanguage =
            if (java.util.Locale.getDefault().language.equals("zh", ignoreCase = true)) ZH else EN

        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: systemDefault()
    }
}
