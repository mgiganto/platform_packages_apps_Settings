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

import android.app.AppsScope
import android.content.pm.ApplicationInfo
import android.content.pm.GosPackageState
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settings.R
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.scaffold.SearchScaffold
import com.android.settingslib.spa.widget.ui.SpinnerOption
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.template.app.AppList
import com.android.settingslib.spaprivileged.template.app.AppListConfig
import com.android.settingslib.spaprivileged.template.app.AppListInput
import com.android.settingslib.spaprivileged.template.app.AppListItemModel
import com.android.settingslib.spaprivileged.template.app.AppListState
import com.android.settingslib.spaprivileged.template.common.UserProfilePager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object AppAppsScopesPickerPageProvider : SettingsPageProvider {
    override val name = "AppAppsScopesPicker"

    private const val PACKAGE_NAME = "packageName"
    private const val USER_ID = "userId"

    override val parameter = listOf(
        navArgument(PACKAGE_NAME) { type = NavType.StringType },
        navArgument(USER_ID) { type = NavType.IntType },
    )

    fun getRoute(packageName: String, userId: Int): String =
        "$name/$packageName/$userId"

    @Composable
    override fun Page(arguments: Bundle?) {
        val packageName = arguments?.getString(PACKAGE_NAME) ?: return
        val userId = arguments.getInt(USER_ID)
        val context = LocalContext.current

        val scope = rememberCoroutineScope()
        val listModel = remember { AppAppsScopePickerListModel(context, packageName, userId, scope) }
        val counts = listModel.countsFlow.collectAsStateWithLifecycle().value

        var showFilterDialog by remember { mutableStateOf(false) }
        val showOnlyVisible = listModel.showOnlyVisibleAppsFlow.collectAsStateWithLifecycle().value
        val restrictRules = listModel.restrictRulesToVisibleFlow.collectAsStateWithLifecycle().value

        SearchScaffold(
            title = stringResource(R.string.apps_scopes_visibility_title) + " (${counts.first}/${counts.second})",
            actions = {
                IconButton(onClick = { showFilterDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.apps_scopes_filter_options),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                MoreOptionsAction {
                    MenuItem(text = stringResource(R.string.apps_scopes_select_visible)) {
                        listModel.toggleAll(true)
                    }
                    MenuItem(text = stringResource(R.string.apps_scopes_deselect_visible)) {
                        listModel.toggleAll(false)
                    }
                }
            }
        ) { bottomPadding, searchQuery ->
            // Filter options dialog
            if (showFilterDialog) {
                FilterOptionsDialog(
                    showOnlyVisible = showOnlyVisible,
                    onShowOnlyVisibleChange = listModel::setShowOnlyVisibleApps,
                    restrictRules = restrictRules,
                    onRestrictRulesChange = listModel::setRestrictRulesToVisible,
                    onDismiss = { showFilterDialog = false }
                )
            }

            UserProfilePager { userGroup ->
                Column(modifier = Modifier.fillMaxSize()) {
                    val filterState = listModel.filterStateFlow.collectAsStateWithLifecycle().value

                    // -- Segmented Filter: All | Enabled | Disabled --
                    SegmentedFilterBar(
                        selectedIndex = filterState,
                        onSelect = listModel::setFilterState
                    )

                    // -- App List area (stable size, no flickering) --
                    val appListInput = AppListInput(
                        config = AppListConfig(
                            userIds = userGroup.userInfos.map { it.id },
                            showInstantApps = false,
                            matchAnyUserForAdmin = false,
                        ),
                        listModel = listModel,
                        state = AppListState(
                            showSystem = { true },
                            searchQuery = searchQuery,
                        ),
                        header = {},
                        noItemMessage = context.getString(R.string.apps_scopes_no_apps_match),
                        bottomPadding = bottomPadding,
                    )

                    val loading = listModel.loadingFlow.collectAsStateWithLifecycle().value
                    if (loading) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            with(appListInput) {
                                val sq = this.state.searchQuery
                                LaunchedEffect(sq) {
                                    listModel.updateState(sq)
                                }
                                AppList()
                            }
                        }
                    }
                }
            }
        }
    }

    // -- Segmented Filter Bar ----------------------------------------------

    @Composable
    private fun SegmentedFilterBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
        val options = listOf(
            R.string.apps_scopes_filter_all,
            R.string.apps_scopes_filter_enabled,
            R.string.apps_scopes_filter_disabled
        )
        val shape = RoundedCornerShape(8.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { index, resId ->
                val selected = selectedIndex == index
                val bgColor = if (selected)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh

                val textColor = if (selected)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onSurface

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(shape)
                        .background(bgColor)
                        .clickable(role = Role.Tab) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(resId),
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // -- Filter Options Dialog ----------------------------------------------

    @Composable
    private fun FilterOptionsDialog(
        showOnlyVisible: Boolean,
        onShowOnlyVisibleChange: (Boolean) -> Unit,
        restrictRules: Boolean,
        onRestrictRulesChange: (Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(context.getString(R.string.apps_scopes_filter_options))
            },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowOnlyVisibleChange(!showOnlyVisible) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = context.getString(R.string.apps_scopes_show_only_visible),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = showOnlyVisible,
                            onCheckedChange = null
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRestrictRulesChange(!restrictRules) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = context.getString(R.string.apps_scopes_restrict_rules_to_visible),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = restrictRules,
                            onCheckedChange = null
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(context.getString(android.R.string.ok))
                }
            }
        )
    }
}

// -- Data Model ------------------------------------------------------------

private data class AppAppsScopePickerRecord(
    override val app: ApplicationInfo,
    val isSharedCert: Boolean,
    val isNaturallyVisible: Boolean,
    val label: String,
) : AppRecord

// -- List Model ------------------------------------------------------------

private class AppAppsScopePickerListModel(
    private val context: android.content.Context,
    private val packageName: String,
    private val userId: Int,
    private val scope: CoroutineScope,
) : AppListModel<AppAppsScopePickerRecord> {

    private val writeMutex = Mutex()
    private val flagsFlow = MutableStateFlow(0)
    private val specificRulesFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val filterStateFlow = MutableStateFlow(0)
    val showOnlyVisibleAppsFlow = MutableStateFlow(true)
    val restrictRulesToVisibleFlow = MutableStateFlow(true)
    private val _loadingFlow = MutableStateFlow(false)
    val loadingFlow: StateFlow<Boolean> = _loadingFlow
    val countsFlow = MutableStateFlow(0 to 0)
    private var allRecords = emptyList<AppAppsScopePickerRecord>()
    private val recordsCache = mutableMapOf<Int, List<AppAppsScopePickerRecord>>()
    private var currentOption = 0
    private var searchQuery: (() -> String)? = null

    init {
        refresh()
    }

    fun updateState(searchQuery: () -> String) {
        this.searchQuery = searchQuery
    }

    override fun getSpinnerOptions(recordList: List<AppAppsScopePickerRecord>): List<SpinnerOption> {
        return listOf(
            SpinnerOption(id = 0, text = context.getString(R.string.apps_scopes_visible_user_apps)),
            SpinnerOption(id = 1, text = context.getString(R.string.apps_scopes_visible_system_apps)),
            SpinnerOption(id = 2, text = context.getString(R.string.apps_scopes_visible_shared_cert_apps)),
        )
    }


    override fun transform(
        userIdFlow: Flow<Int>,
        appListFlow: Flow<List<ApplicationInfo>>,
    ): Flow<List<AppAppsScopePickerRecord>> {
        return userIdFlow.map { userId ->
            _loadingFlow.value = true
            try {
                recordsCache.getOrPut(userId) {
                    val pm = context.packageManager

                    val apps = withContext(Dispatchers.IO) {
                        pm.getInstalledApplicationsAsUser(
                            AppsScopeConstants.QUERY_FLAGS, userId
                        )
                    }

                    withContext(Dispatchers.IO) {
                        apps.map { app ->
                            val isSharedCert = pm.checkSignatures(
                                packageName, app.packageName
                            ) == PackageManager.SIGNATURE_MATCH
                            val isNaturallyVisible = try {
                                pm.canPackageQuery(packageName + ".unfiltered", app.packageName)
                            } catch (_: PackageManager.NameNotFoundException) {
                                false
                            }
                            AppAppsScopePickerRecord(
                                app = app,
                                isSharedCert = isSharedCert,
                                isNaturallyVisible = isNaturallyVisible,
                                label = app.loadLabel(pm).toString(),
                            )
                        }
                    }
                }
            } finally {
                _loadingFlow.value = false
            }
        }.onEach { allRecords = it }
    }

    override fun filter(
        userIdFlow: Flow<Int>,
        option: Int,
        recordListFlow: Flow<List<AppAppsScopePickerRecord>>,
    ): Flow<List<AppAppsScopePickerRecord>> {
        currentOption = option
        return combine(
            recordListFlow, filterStateFlow, showOnlyVisibleAppsFlow, flagsFlow, specificRulesFlow
        ) { recordList, filterState, showOnlyVisible, flags, rules ->
            val categoryList = recordList.filter { matchesCategory(it, option) }

            val naturallyVisibleList = if (showOnlyVisible) {
                categoryList.filter { it.isNaturallyVisible }
            } else {
                categoryList
            }

            val displayedList = when (filterState) {
                1 -> naturallyVisibleList.filter { isVisibilityEnabled(it, flags, rules) }
                2 -> naturallyVisibleList.filter { !isVisibilityEnabled(it, flags, rules) }
                else -> naturallyVisibleList
            }

            val total = displayedList.size
            val enabled = displayedList.count { isVisibilityEnabled(it, flags, rules) }
            countsFlow.value = enabled to total

            displayedList
        }
    }

    override fun getGroupTitle(option: Int, record: AppAppsScopePickerRecord): String? = null

    @Composable
    override fun AppListItemModel<AppAppsScopePickerRecord>.AppItem() {
        val flags = flagsFlow.collectAsStateWithLifecycle().value
        val rules = specificRulesFlow.collectAsStateWithLifecycle().value
        val isVisible = isVisibilityEnabled(record, flags, rules)

        AppListSwitchItem(
            record = record,
            checked = isVisible,
            onCheckedChange = { newChecked -> togglePackage(record, newChecked) }
        )
    }

    @Composable
    private fun AppListSwitchItem(
        record: AppAppsScopePickerRecord,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val backgroundColor = if (record.isNaturallyVisible) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        }

        val context = LocalContext.current
        val icon = produceState<Drawable?>(initialValue = null, key1 = record.app.packageName) {
            withContext(Dispatchers.IO) {
                value = try { record.app.loadIcon(context.packageManager) } catch (_: PackageManager.NameNotFoundException) { null }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(icon.value),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(40.dp),
                contentScale = ContentScale.Fit
            )

            Text(
                text = record.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Switch(
                checked = checked,
                onCheckedChange = null
            )
        }
    }

    // -- Business Logic ------------------------------------------------

    private fun togglePackage(record: AppAppsScopePickerRecord, visible: Boolean) {
        val pkg = record.app.packageName
        if (restrictRulesToVisibleFlow.value && !record.isNaturallyVisible) return

        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val flags = config?.flags ?: 0
                    val builder = AppsScope.Builder.from(config)

                    val flag = getRestrictionFlag(record)
                    val isWhitelistMode = (flags and flag) != 0

                    if (visible) {
                        if (isWhitelistMode) builder.addPackage(pkg, true)
                        else builder.removePackage(pkg)
                    } else {
                        if (isWhitelistMode) builder.removePackage(pkg)
                        else builder.addPackage(pkg, false)
                    }

                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }

    private fun refresh() {
        recordsCache.clear()
        scope.launch {
            withContext(Dispatchers.IO) {
                val config = GosPackageState.get(packageName, userId).appsScope
                val newFlags = config?.flags ?: 0
                val newRules = config?.specificRules ?: emptyMap<String, Boolean>()

                withContext(Dispatchers.Main) {
                    flagsFlow.value = newFlags
                    specificRulesFlow.value = newRules
                }
            }
        }
    }

    fun setFilterState(state: Int) {
        filterStateFlow.value = state
    }

    fun setShowOnlyVisibleApps(show: Boolean) {
        showOnlyVisibleAppsFlow.value = show
    }

    fun setRestrictRulesToVisible(restrict: Boolean) {
        restrictRulesToVisibleFlow.value = restrict
    }

    fun toggleAll(visible: Boolean) {
        scope.launch {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val query = searchQuery?.invoke() ?: ""
                    val gps = GosPackageState.get(packageName, userId)
                    val ed = gps.createEditor(packageName, userId)
                    val config = gps.appsScope
                    val flags = config?.flags ?: 0
                    val builder = AppsScope.Builder.from(config)

                    val targetRecords = allRecords.filter { record ->
                        matchesCategory(record, currentOption) &&
                            (query.isEmpty() || record.label.contains(query, ignoreCase = true))
                    }

                    val flag = when (currentOption) {
                        1 -> AppsScope.FLAG_RESTRICT_SYSTEM
                        2 -> AppsScope.FLAG_RESTRICT_SHARED_CERT
                        else -> AppsScope.FLAG_RESTRICT_QUERIES
                    }
                    val isWhitelistMode = (flags and flag) != 0
                    val restrictRules = restrictRulesToVisibleFlow.value

                    for (record in targetRecords) {
                        val pkg = record.app.packageName
                        if (restrictRules && !record.isNaturallyVisible) continue

                        if (visible) {
                            if (isWhitelistMode) builder.addPackage(pkg, true)
                            else builder.removePackage(pkg)
                        } else {
                            if (isWhitelistMode) builder.removePackage(pkg)
                            else builder.addPackage(pkg, false)
                        }
                    }
                    ed.setAppsScopeConfig(AppsScope.serialize(builder.build()))
                    ed.apply()
                }
            }
            refresh()
        }
    }

    private fun matchesCategory(record: AppAppsScopePickerRecord, option: Int): Boolean =
        when (option) {
            0 -> (record.app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !record.isSharedCert
            1 -> (record.app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && !record.isSharedCert
            2 -> record.isSharedCert
            else -> true
        }

    private fun getRestrictionFlag(record: AppAppsScopePickerRecord): Int =
        when {
            record.isSharedCert -> AppsScope.FLAG_RESTRICT_SHARED_CERT
            (record.app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 -> AppsScope.FLAG_RESTRICT_SYSTEM
            else -> AppsScope.FLAG_RESTRICT_QUERIES
        }

    private fun isVisibilityEnabled(
        record: AppAppsScopePickerRecord, flags: Int, rules: Map<String, Boolean>
    ): Boolean {
        val isWhitelistMode = (flags and getRestrictionFlag(record)) != 0
        val currentRule = rules[record.app.packageName]
        return if (isWhitelistMode) currentRule == true else currentRule != false
    }
}
