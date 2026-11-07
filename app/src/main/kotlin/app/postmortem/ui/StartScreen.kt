package app.postmortem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.postmortem.R
import app.postmortem.StartTab
import app.postmortem.UiTags

/**
 * The start page: a bottom bar with the four tabs and the selected tab's screen above it. The bar hides while the keyboard is open, so
 * the PGN field and the Analyse button stay in view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(
    selected: StartTab,
    onSelect: (StartTab) -> Unit,
    content: @Composable (StartTab) -> Unit,
) {
    val keyboardOpen = WindowInsets.isImeVisible
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!keyboardOpen) {
                NavigationBar {
                    StartTab.entries.forEach { tab ->
                        val label = stringResource(tab.labelRes())
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            icon = { Icon(painterResource(tab.iconRes()), label) },
                            label = { Text(label) },
                            modifier = Modifier.testTag(tab.tag()),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content(selected) }
    }
}

private fun StartTab.labelRes(): Int =
    when (this) {
        StartTab.Analyse -> R.string.tab_analyse
        StartTab.Library -> R.string.tab_library
        StartTab.Report -> R.string.tab_report
        StartTab.Settings -> R.string.tab_settings
    }

private fun StartTab.iconRes(): Int =
    when (this) {
        StartTab.Analyse -> R.drawable.ic_tab_analyse
        StartTab.Library -> R.drawable.ic_tab_library
        StartTab.Report -> R.drawable.ic_tab_report
        StartTab.Settings -> R.drawable.ic_tab_settings
    }

private fun StartTab.tag(): String =
    when (this) {
        StartTab.Analyse -> UiTags.TAB_ANALYSE
        StartTab.Library -> UiTags.TAB_LIBRARY
        StartTab.Report -> UiTags.TAB_REPORT
        StartTab.Settings -> UiTags.TAB_SETTINGS
    }
