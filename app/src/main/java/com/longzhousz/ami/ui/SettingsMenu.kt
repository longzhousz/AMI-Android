package com.longzhousz.ami.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.longzhousz.ami.R

internal enum class SettingsSection(
    @StringRes val title: Int,
) {
    HOME(R.string.common_settings),
    LYRICS(R.string.settings_translation_section),
    LOCAL(R.string.settings_local_section),
    STORAGE(R.string.settings_storage_section),
    MORE(R.string.settings_more),
}

@Composable
internal fun SettingsMenu(
    section: SettingsSection,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("settings-${section.name.lowercase(java.util.Locale.ROOT)}")) {
        if (section != SettingsSection.HOME) {
            Row(Modifier.fillMaxWidth().padding(start = if (section == SettingsSection.HOME) 28.dp else 8.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (section != SettingsSection.HOME) {
                    ActionIcon(Icons.Rounded.ArrowBack, stringResource(R.string.settings_back), onBack)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(section.title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = if (section == SettingsSection.HOME) 12.dp else 0.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun SettingsHome(
    showAccount: Boolean,
    onAccount: () -> Unit,
    onListening: () -> Unit,
    onTheme: () -> Unit,
    onSection: (SettingsSection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup {
            if (showAccount) SettingRow(Icons.Rounded.PersonOutline, stringResource(R.string.settings_account), onAccount)
            SettingRow(Icons.Rounded.Insights, stringResource(R.string.settings_listening), onListening)
        }
        SettingsGroup {
            SettingRow(Icons.Rounded.Lyrics, stringResource(R.string.settings_translation_section), { onSection(SettingsSection.LYRICS) })
            SettingRow(Icons.Rounded.Palette, stringResource(R.string.settings_theme), onTheme)
        }
        SettingsGroup {
            SettingRow(Icons.Rounded.LibraryMusic, stringResource(R.string.settings_local_section), { onSection(SettingsSection.LOCAL) })
            SettingRow(Icons.Rounded.Storage, stringResource(R.string.settings_storage_section), { onSection(SettingsSection.STORAGE) })
            SettingRow(Icons.Rounded.MoreHoriz, stringResource(R.string.settings_more), { onSection(SettingsSection.MORE) })
        }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(24.dp)), content = content)
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
