package me.weishu.kernelsu.ui.screen.settings

import android.util.Base64
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.reboot
import me.weishu.kernelsu.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            pendingAction?.invoke()
            pendingAction = null
        },
        onDismiss = { pendingAction = null },
    )
    val softRestartTitle = stringResource(R.string.settings_soft_restart_confirm)
    val softRestartMessage = stringResource(R.string.settings_soft_restart_confirm_summary)
    val softRestartLabel = stringResource(R.string.settings_soft_restart)
    val soterTitle = stringResource(R.string.settings_soter_fix_confirm)
    val soterMessage = stringResource(R.string.settings_soter_fix_confirm_summary)
    val soterLabel = stringResource(R.string.settings_soter_fix)
    val soterFailed = stringResource(R.string.settings_soter_fix_failed)

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetUiModeIndex = { index ->
            viewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenProfileTemplate = { navigator.push(Route.AppProfileTemplate) },
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetSulogEnabled = viewModel::setSulogEnabled,
        onSetAdbRootEnabled = viewModel::setAdbRootEnabled,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onSetAutoJailbreak = viewModel::setAutoJailbreak,
        onSoftRestart = {
            pendingAction = {
                scope.launch(Dispatchers.IO) { reboot("soft_reboot") }
            }
            confirmDialog.showConfirm(
                title = softRestartTitle,
                content = softRestartMessage,
                confirm = softRestartLabel,
            )
        },
        onRestartAndFixSoter = {
            pendingAction = {
                scope.launch {
                    val installed = withContext(Dispatchers.IO) { installSoterRepairScript(context) }
                    if (installed) {
                        withContext(Dispatchers.IO) { reboot("soft_reboot") }
                    } else {
                        Toast.makeText(context, soterFailed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            confirmDialog.showConfirm(
                title = soterTitle,
                content = soterMessage,
                confirm = soterLabel,
            )
        },
        onOpenAbout = { navigator.push(Route.About) },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, actions, bottomInnerPadding)
        UiMode.Material -> SettingPagerMaterial(uiState, actions, bottomInnerPadding)
    }
}

private fun installSoterRepairScript(context: android.content.Context): Boolean = runCatching {
    val script = context.resources.openRawResource(R.raw.yipasu_soter_key_fix).use { it.readBytes() }
    val encoded = Base64.encodeToString(script, Base64.NO_WRAP)
    val path = "/data/adb/service.d/yipasu_soter_key_fix.sh"
    val stdout = arrayListOf<String>()
    val stderr = arrayListOf<String>()
    val command = "mkdir -p /data/adb/service.d && " +
        "printf %s '$encoded' | base64 -d > '$path' && " +
        "chmod 0755 '$path' && restorecon '$path' 2>/dev/null || true; " +
        "test -s '$path'"
    getRootShell().newJob().add(command).to(stdout, stderr).exec().isSuccess
}.getOrDefault(false)
