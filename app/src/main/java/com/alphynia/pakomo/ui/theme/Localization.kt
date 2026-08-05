package com.alphynia.pakomo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.alphynia.pakomo.core.model.AppLanguage

/**
 * 当前界面语言。用 [staticCompositionLocalOf]:语言切换时整棵 UI 重组(与 [LocalThemeMode] 同理),
 * 从而所有 [t] 文案即时跟随。
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.DEFAULT }

/** 就近双语文案:`Text(t("设置", "Settings"))`。取当前 [LocalAppLanguage]。 */
@Composable
@ReadOnlyComposable
fun t(zh: String, en: String): String = LocalAppLanguage.current.tr(zh, en)
