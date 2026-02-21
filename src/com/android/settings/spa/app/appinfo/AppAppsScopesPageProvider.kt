/*
 * Copyright (C) 2026 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.spa.app.appinfo

import android.app.Activity
import android.app.AppsScope
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.GosPackageState
import android.content.pm.GosPackageStateFlag
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settings.R
import com.android.settings.spa.SpaActivity.Companion.startSpaActivity
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.UserManager

object AppAppsScopesPageProvider : SettingsPageProvider {
    override val name = "AppAppsScopes"

    private const val PACKAGE_NAME = "packageName"
    private const val USER_ID = "userId"

    override val parameter = listOf(
        navArgument(PACKAGE_NAME) { type = NavType.StringType },
        navArgument(USER_ID) { type = NavType.IntType },
    )

    fun getRoute(packageName: String, userId: Int): String = "$name/$packageName/$userId"

    @Composable
    override fun Page(arguments: Bundle?) {
        val packageName = arguments?.getString(PACKAGE_NAME) ?: return
        val userId = arguments.getInt(USER_ID)
        val context = LocalContext.current

        val scope = rememberCoroutineScope()
        val appsScopeState = remember { AppsScopeState(context, packageName, userId, scope) }
        val loading = appsScopeState.loading.collectAsStateWithLifecycle().value

        RegularScaffold(title = stringResource(R.string.apps_scope)) {
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                MainSwitch(appsScopeState)
                RestoreButton(appsScopeState)
                RestrictAllButton(appsScopeState)
                if (appsScopeState.enabled.collectAsStateWithLifecycle().value) {
                    Restrictions(appsScopeState)
                    AllowedPackages(appsScopeState)
                }
            }
        }
    }

    @Composable
    private fun RestoreButton(state: AppsScopeState) {
        Preference(remember {
            object : PreferenceModel {
                override val title = state.context.getString(R.string.apps_scopes_restore)
                override val summary = { state.context.getString(R.string.apps_scopes_restore_summary) }
                override val onClick = { state.restore() }
            }
        })
    }

    @Composable
    private fun RestrictAllButton(state: AppsScopeState) {
        var openDialog by remember { mutableStateOf(false) }

        if (openDialog) {
            AlertDialog(
                onDismissRequest = { openDialog = false },
                title = { Text(stringResource(R.string.apps_scopes_restrict_all_dialog_title)) },
                text = {
                    Column {
                        val options = listOf(
                            R.string.apps_scopes_restrict_all_option_all,
                            R.string.apps_scopes_restrict_all_option_user,
                            R.string.apps_scopes_restrict_all_option_system
                        )
                        options.forEachIndexed { index, resId ->
                            TextButton(
                                onClick = {
                                    state.restrictAll(index)
                                    openDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(resId),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { openDialog = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }

        Preference(remember {
            object : PreferenceModel {
                override val title = state.context.getString(R.string.apps_scopes_restrict_all)
                override val summary = { state.context.getString(R.string.apps_scopes_restrict_all_summary) }
                override val onClick = { openDialog = true }
            }
        })
    }

    @Composable
    private fun AllowedPackages(state: AppsScopeState) {
        val packages = state.allowedPackages.collectAsStateWithLifecycle().value

        val addPackageTitle = stringResource(R.string.apps_scopes_add_package)
        val addPackageRoute = AppAppsScopesPickerPageProvider.getRoute(state.packageName, state.userId)
        val addPackageOnClick = navigator(addPackageRoute)

        Category(title = stringResource(R.string.apps_scopes_allowed_packages_title)) {
            Preference(remember(addPackageRoute) {
                object : PreferenceModel {
                    override val title = addPackageTitle
                    override val onClick = addPackageOnClick
                }
            })
        }

        val (sharedCertApps, others) = remember(packages) {
            val pm = state.context.packageManager
            packages.partition {
                pm.checkSignatures(state.packageName, it.app.packageName) == PackageManager.SIGNATURE_MATCH
            }
        }
        val (systemApps, userApps) = remember(others) {
            others.partition { (it.app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
        }

        if (sharedCertApps.isNotEmpty()) {
            RenderAppGroups(
                apps = sharedCertApps,
                allowedTitleRes = R.string.apps_scopes_allowed_shared_cert_apps,
                restrictedTitleRes = R.string.apps_scopes_restricted_shared_cert_apps,
                state = state
            )
        }

        if (userApps.isNotEmpty()) {
            RenderAppGroups(
                apps = userApps,
                allowedTitleRes = R.string.apps_scopes_allowed_user_apps,
                restrictedTitleRes = R.string.apps_scopes_restricted_user_apps,
                state = state
            )
        }

        if (systemApps.isNotEmpty()) {
            RenderAppGroups(
                apps = systemApps,
                allowedTitleRes = R.string.apps_scopes_allowed_system_apps,
                restrictedTitleRes = R.string.apps_scopes_restricted_system_apps,
                state = state
            )
        }
    }

    @Composable
    private fun RenderAppGroups(
        apps: List<AllowedAppRecord>,
        allowedTitleRes: Int,
        restrictedTitleRes: Int,
        state: AppsScopeState
    ) {
        val (allowed, restricted) = apps.partition { it.allowed }

        if (allowed.isNotEmpty()) {
            Category(title = stringResource(allowedTitleRes) + " (${allowed.size})") {
                for (item in allowed) {
                    key(item.app.packageName) {
                        AllowedAppItem(state, item)
                    }
                }
            }
        }

        if (restricted.isNotEmpty()) {
            Category(title = stringResource(restrictedTitleRes) + " (${restricted.size})") {
                for (item in restricted) {
                    key(item.app.packageName) {
                        AllowedAppItem(state, item)
                    }
                }
            }
        }
    }

    @Composable
    private fun AllowedAppItem(state: AppsScopeState, item: AllowedAppRecord) {
        CustomAllowedAppItem(item) {
            state.removePackage(item.app.packageName)
        }
    }

    @Composable
    private fun CustomAllowedAppItem(
        item: AllowedAppRecord,
        onClick: () -> Unit
    ) {
        val isVisible = item.isNaturallyVisible

        val backgroundColor = if (isVisible) {
            Color.Transparent
        } else {
             MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        }

        val context = LocalContext.current
        val icon = produceState<Drawable?>(initialValue = null, key1 = item.app.packageName) {
            withContext(Dispatchers.IO) {
                value = item.app.loadIcon(context.packageManager)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val drawable = icon.value
            if (drawable != null) {
                Image(
                    painter = rememberDrawablePainter(drawable),
                    contentDescription = item.label,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(48.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(48.dp)
                )
            }

            Text(
                text = item.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    @Composable
    private fun MainSwitch(state: AppsScopeState) {
        val enabled = state.enabled.collectAsStateWithLifecycle().value
        SwitchPreference(remember(enabled) {
            object : SwitchPreferenceModel {
                override val title = state.context.getString(R.string.apps_scopes_main_switch)
                override val checked = { enabled }
                override val onCheckedChange = { newChecked: Boolean -> state.setEnabled(newChecked) }
            }
        })
    }

    @Composable
    private fun Restrictions(state: AppsScopeState) {
        Category(title = stringResource(R.string.apps_scope)) {
            val flags = state.flags.collectAsStateWithLifecycle().value

            // Standard restrictSelf flag
            SwitchPreference(remember(flags) {
                object : SwitchPreferenceModel {
                    override val title = state.context.getString(R.string.apps_scopes_restrict_self)
                    override val checked = { (flags and AppsScope.FLAG_RESTRICT_SELF) != 0 }
                    override val onCheckedChange = { newChecked: Boolean -> state.setRestrictSelf(newChecked) }
                }
            })

            RestrictionSwitch(state, AppsScope.FLAG_RESTRICT_SHARED_CERT, R.string.apps_scopes_restrict_shared_cert)
            RestrictionSwitch(state, AppsScope.FLAG_RESTRICT_SYSTEM, R.string.apps_scopes_restrict_system)
            RestrictionSwitch(state, AppsScope.FLAG_RESTRICT_QUERIES, R.string.apps_scopes_restrict_queries)
        }
    }

    @Composable
    private fun RestrictionSwitch(state: AppsScopeState, flag: Int, titleRes: Int) {
        val flags = state.flags.collectAsStateWithLifecycle().value
        SwitchPreference(remember(flags) {
            object : SwitchPreferenceModel {
                override val title = state.context.getString(titleRes)
                override val checked = { (flags and flag) != 0 }
                override val onCheckedChange = { newChecked: Boolean -> state.setFlag(flag, newChecked) }
            }
        })
    }
}

internal object AppsScopeConstants {
    val QUERY_FLAGS: PackageManager.ApplicationInfoFlags = PackageManager.ApplicationInfoFlags.of(
        PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or
        PackageManager.MATCH_DISABLED_COMPONENTS.toLong() or
        PackageManager.MATCH_INSTANT.toLong() or
        PackageManager.MATCH_ARCHIVED_PACKAGES
    )
}

private data class AllowedAppRecord(
    override val app: ApplicationInfo,
    val label: String,
    val isNaturallyVisible: Boolean,
    val allowed: Boolean,
) : AppRecord

private class AppsScopeState(val context: Context, val packageName: String, val userId: Int, private val scope: CoroutineScope) {
    val enabled = MutableStateFlow(false)
    val flags = MutableStateFlow(0)
    val allowedPackages = MutableStateFlow<List<AllowedAppRecord>>(emptyList())
    val loading = MutableStateFlow(true)
    private val writeMutex = Mutex()

    init {
        refresh()
    }

    private fun refresh() {
        scope.launch {
            loading.value = true
            withContext(Dispatchers.IO) {
                val gps = GosPackageState.get(packageName, userId)
                val isEnabled = gps.hasFlag(GosPackageStateFlag.APPS_SCOPES_ENABLED)
                val config = gps.appsScope
                val pm = context.packageManager

                val newFlags: Int
                val newAllowedPackages: List<AllowedAppRecord>

                if (config != null) {
                    newFlags = config.flags
                    val records = config.specificRules.map { (pkg, allowed) ->
                        try {
                            val ai = pm.getApplicationInfoAsUser(pkg, AppsScopeConstants.QUERY_FLAGS, userId)

                            val isVisible = try { // Check Natural Visibility
                                 pm.canPackageQuery(packageName + ".unfiltered", pkg)
                            } catch (e: PackageManager.NameNotFoundException) {
                                false
                            }

                            AllowedAppRecord(ai, ai.loadLabel(pm).toString(), isVisible, allowed)
                        } catch (e: PackageManager.NameNotFoundException) {
                            AllowedAppRecord(ApplicationInfo().apply { packageName = pkg }, pkg, false, allowed)
                        }
                    }
                    newAllowedPackages = records.sortedBy { it.label.lowercase() }
                } else {
                    newFlags = 0
                    newAllowedPackages = emptyList()
                }

                withContext(Dispatchers.Main) {
                    enabled.value = isEnabled
                    flags.value = newFlags
                    allowedPackages.value = newAllowedPackages
                    loading.value = false
                }
            }
        }
    }

    fun setEnabled(enable: Boolean) {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    ed.setFlagState(GosPackageStateFlag.APPS_SCOPES_ENABLED, enable)
                    ed.apply()
                }
            }
            refresh()
        }
    }

    fun restore() {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    // Clear GosPackageState. This will trigger storage cleanup via persistence layer.
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    ed.setFlagState(GosPackageStateFlag.APPS_SCOPES_ENABLED, false)
                    ed.setAppsScopeConfig(null)
                    ed.apply()
                }
            }
            refresh()
        }
    }

    fun setRestrictSelf(value: Boolean) {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val builder = AppsScope.Builder.from(config)

                    if (value) {
                        builder.addFlag(AppsScope.FLAG_RESTRICT_SELF)
                    } else {
                        builder.clearFlag(AppsScope.FLAG_RESTRICT_SELF)
                    }

                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }

    fun setFlag(flag: Int, value: Boolean) {
        scope.launch {
            writeMutex.withLock {
                val oldFlags = flags.value
                val hasChanged = ((oldFlags and flag) != 0) != value
                if (!hasChanged) return@withLock

                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val builder = AppsScope.Builder.from(config)

                    if (value) builder.addFlag(flag) else builder.clearFlag(flag)

                    // --- Inversion Logic (matching HMAC) ---
                    if (flag == AppsScope.FLAG_RESTRICT_QUERIES ||
                        flag == AppsScope.FLAG_RESTRICT_SYSTEM ||
                        flag == AppsScope.FLAG_RESTRICT_SHARED_CERT) {

                        val pm = context.packageManager
                        val allApps = pm.getInstalledApplicationsAsUser(AppsScopeConstants.QUERY_FLAGS, userId)

                        val relevantApps = allApps.map { app ->
                            async {
                                if (app.packageName == packageName) return@async null
                                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                val matches = when (flag) {
                                    AppsScope.FLAG_RESTRICT_SYSTEM -> isSystem
                                    AppsScope.FLAG_RESTRICT_SHARED_CERT -> {
                                        pm.checkSignatures(packageName, app.packageName) == PackageManager.SIGNATURE_MATCH
                                    }
                                    else -> { // FLAG_RESTRICT_QUERIES
                                        !isSystem && pm.checkSignatures(packageName, app.packageName) != PackageManager.SIGNATURE_MATCH
                                    }
                                }
                                if (matches) app else null
                            }
                        }.awaitAll().filterNotNull()

                        val newFlags = if (value) oldFlags or flag else oldFlags and flag.inv()
                        val newIsWhitelistMode = (newFlags and flag) != 0

                        val visibleAppsAsync = relevantApps.map { app ->
                            async {
                                val pkg = app.packageName
                                val isVisible = try { // Check Natural Visibility
                                    pm.canPackageQuery(packageName + ".unfiltered", pkg)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    false
                                }
                                if (isVisible) app else null
                            }
                        }.awaitAll().filterNotNull()

                        for (app in visibleAppsAsync) {
                            val pkg = app.packageName

                            val currentRule = config?.specificRules?.get(pkg)
                            val oldIsWhitelistMode = (oldFlags and flag) != 0

                            // Visibility Calculation (Current State):
                            // In Whitelist mode: Visible if Rule=true.
                            // In Blacklist mode: Visible if Rule!=false (null or true).
                            val currentlyVisible = if (oldIsWhitelistMode) {
                                currentRule == true
                            } else {
                                currentRule != false
                            }

                            // Target: Maintain currentlyVisible in newDefaultMode
                            // If newDefaultMode = Whitelist (Hidden by default):
                            //   If Visible -> Rule=true (Allowed)
                            //   If Hidden -> Rule=null (Default)
                            // If newDefaultMode = Blacklist (Visible by default):
                            //   If Visible -> Rule=null (Default)
                            //   If Hidden -> Rule=false (Restricted)

                            if (newIsWhitelistMode) {
                                if (currentlyVisible) {
                                    builder.addPackage(pkg, true)
                                } else {
                                    builder.removePackage(pkg)
                                }
                            } else {
                                if (currentlyVisible) {
                                    builder.removePackage(pkg)
                                } else {
                                    builder.addPackage(pkg, false)
                                }
                            }
                        }
                    }

                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }

    fun addPackage(pkg: String) {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val flags = config?.flags ?: 0

                    // When adding a package from the picker, we want to make it an EXCEPTION
                    // to its category's current state.
                    val ai = context.packageManager.getApplicationInfoAsUser(pkg, AppsScopeConstants.QUERY_FLAGS, userId)
                    val isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val flag = if (isSystem) AppsScope.FLAG_RESTRICT_SYSTEM else AppsScope.FLAG_RESTRICT_QUERIES
                    val isRestricted = (flags and flag) != 0

                    val builder = AppsScope.Builder.from(config)
                    // If currently Restricted (Whitelist mode) -> Add rule 'true' to Allow
                    // If currently Allowed (Blacklist mode) -> Add rule 'false' to Restrict
                    builder.addPackage(pkg, isRestricted)

                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }

    fun removePackage(pkg: String) {
        val currentPackages = allowedPackages.value
        allowedPackages.value = currentPackages.filter { it.app.packageName != pkg }

        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val builder = AppsScope.Builder.from(config)
                    builder.removePackage(pkg)
                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            // refresh() // No need to refresh entire state
        }
    }

    fun restrictAll(option: Int) {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    // Ensure enabled
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    ed.setFlagState(GosPackageStateFlag.APPS_SCOPES_ENABLED, true)

                    val config = gps.appsScope
                    val builder = AppsScope.Builder.from(config)

                    // Do NOT touch RESTRICT_SELF.
                    // Flags to enable based on option:
                    // 0 (All): All flags (except self)
                    // 1 (User): Queries, Shared Cert
                    // 2 (System): System

                    val flagsToEnable = when (option) {
                        0 -> AppsScope.FLAG_RESTRICT_QUERIES or AppsScope.FLAG_RESTRICT_SHARED_CERT or
                             AppsScope.FLAG_RESTRICT_SYSTEM
                        1 -> AppsScope.FLAG_RESTRICT_QUERIES or AppsScope.FLAG_RESTRICT_SHARED_CERT
                        2 -> AppsScope.FLAG_RESTRICT_SYSTEM
                        else -> 0
                    }

                    builder.addFlag(flagsToEnable)

                    val pm = context.packageManager
                    val allApps = pm.getInstalledApplicationsAsUser(AppsScopeConstants.QUERY_FLAGS, userId)

                    val relevantApps = allApps.filter { app ->
                        if (app.packageName == packageName) return@filter false // Exclude self
                        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        when (option) {
                            0 -> true       // All
                            1 -> !isSystem  // Only user apps
                            2 -> isSystem   // Only system apps
                            else -> false
                        }
                    }

                    val settingsStoragePkg = "com.android.providers.settings"

                    val visibleAppsAsync = relevantApps.map { app ->
                        async {
                            val pkg = app.packageName
                            if (pkg == settingsStoragePkg) return@async app
                            val isVisible = try { // Check Natural Visibility
                                pm.canPackageQuery(packageName + ".unfiltered", pkg)
                            } catch (e: PackageManager.NameNotFoundException) {
                                false
                            }
                            if (isVisible) app else null
                        }
                    }.awaitAll().filterNotNull()

                    for (app in visibleAppsAsync) {
                        val pkg = app.packageName
                        if (pkg == settingsStoragePkg) {
                            builder.addPackage(pkg, true)
                            continue
                        }

                        builder.removePackage(pkg)
                    }

                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }
}

/**
 * Bridge activity that receives the `android.settings.APPS_SCOPE_DETAILS` intent
 * from Launcher3 and navigates to the SPA-based Apps Scope settings page.
 *
 * Expected intent data: `package:<packageName>`
 * Optional extra: [Intent.EXTRA_USER] (UserHandle)
 */
class AppsScopeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentData: Uri? = intent?.data
        if (intentData == null) {
            finish()
            return
        }

        val packageName = intentData.schemeSpecificPart
        if (packageName.isNullOrEmpty()) {
            finish()
            return
        }

        // Validate packageName format to reject malformed input from external intents
        val packageNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        if (!packageNamePattern.matches(packageName)) {
            finish()
            return
        }

        val userHandle = intent?.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        val userId = userHandle?.identifier ?: UserHandle.myUserId()

        val launchedFromUid = getLaunchedFromUid()
        val launchedFromUserId = UserHandle.getUserId(launchedFromUid)

        if (launchedFromUserId != userId && launchedFromUid != android.os.Process.SYSTEM_UID && launchedFromUid != 0) {
            val um = getSystemService(UserManager::class.java)
            if (um == null || !um.isSameProfileGroup(launchedFromUserId, userId)) {
                finish()
                return
            }
        }

        try {
            packageManager.getPackageInfoAsUser(packageName, 0, userId)
        } catch (e: PackageManager.NameNotFoundException) {
            finish()
            return
        }

        val destination = "${AppAppsScopesPageProvider.name}/$packageName/$userId"
        startSpaActivity(destination)
        finish()
    }
}
