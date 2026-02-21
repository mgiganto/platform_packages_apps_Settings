/*
 * Copyright (C) 2026 GrapheneOS
 * Copyright (C) 2024-2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.n * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.spa.app.appinfo

import android.content.pm.ApplicationInfo
import android.content.pm.GosPackageState
import android.content.pm.GosPackageStateFlag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.android.settings.R
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.app.installed
import com.android.settingslib.spaprivileged.model.app.userId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppAppsScopesPreference(app: ApplicationInfo) {
    if (!app.installed) {
        return
    }

    val appsScopeEnabled = produceState(initialValue = false, key1 = app.packageName, key2 = app.userId) {
        value = withContext(Dispatchers.IO) {
            GosPackageState.get(app.packageName, app.userId)
                .hasFlag(GosPackageStateFlag.APPS_SCOPES_ENABLED)
        }
    }

    val route = remember(app) { AppAppsScopesPageProvider.getRoute(app.packageName, app.userId) }
    val onNavigate = navigator(route)
    val summaryText = stringResource(if (appsScopeEnabled.value) R.string.apps_scopes_summary_enabled else R.string.apps_scopes_summary_disabled)

    val titleText = stringResource(R.string.apps_scope)

    Preference(remember(appsScopeEnabled.value) {
        object : PreferenceModel {
            override val title = titleText
            override val summary = { summaryText }
            override val onClick = onNavigate
        }
    })
}
