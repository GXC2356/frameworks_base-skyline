/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.getRuntimeArguments
import com.android.settingslib.spa.framework.util.mergeArguments
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.gallery.createSettingsPage
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object ArgumentPageProvider : SettingsPageProvider {
    // Defines all entry name in this page.
    // Note that entry name would be used in log. DO NOT change it once it is set.
    // One can still change the display name for better readability if necessary.
    private enum class EntryEnum(val displayName: String) {
        STRING_PARAM("string_param"),
        INT_PARAM("int_param"),
    }

    private fun createEntry(owner: SettingsPage, entry: EntryEnum): SettingsEntryBuilder {
        return SettingsEntryBuilder.create(owner, entry.name, entry.displayName)
    }

    override val name = SettingsPageProviderEnum.ARGUMENT.name

    override val parameter = ArgumentPageModel.parameter

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!ArgumentPageModel.isValidArgument(arguments)) return emptyList()

        val owner = createSettingsPage(SettingsPageProviderEnum.ARGUMENT, parameter, arguments)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            createEntry(owner, EntryEnum.STRING_PARAM)
                // Set attributes
                .setIsAllowSearch(true)
                .setSearchDataFn { ArgumentPageModel.genStringParamSearchData() }
                .setUiLayoutFn {
                    // Set ui rendering
                    Preference(ArgumentPageModel.create(it).genStringParamPreferenceModel())
                }.build()
        )

        entryList.add(
            createEntry(owner, EntryEnum.INT_PARAM)
                // Set attributes
                .setIsAllowSearch(true)
                .setSearchDataFn { ArgumentPageModel.genIntParamSearchData() }
                .setUiLayoutFn {
                    // Set ui rendering
                    Preference(ArgumentPageModel.create(it).genIntParamPreferenceModel())
                }.build()
        )

        entryList.add(buildInjectEntry("foo")!!.setLink(fromPage = owner).build())
        entryList.add(buildInjectEntry("bar")!!.setLink(fromPage = owner).build())

        return entryList
    }

    fun buildInjectEntry(stringParam: String): SettingsEntryBuilder? {
        val arguments = ArgumentPageModel.buildArgument(stringParam)
        if (!ArgumentPageModel.isValidArgument(arguments)) return null

        return SettingsEntryBuilder.createInject(
            owner = createSettingsPage(SettingsPageProviderEnum.ARGUMENT, parameter, arguments),
            displayName = "${name}_$stringParam",
        )
            // Set attributes
            .setIsAllowSearch(false)
            .setSearchDataFn { ArgumentPageModel.genInjectSearchData() }
            .setUiLayoutFn {
                // Set ui rendering
                Preference(ArgumentPageModel.create(it).genInjectPreferenceModel())
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val globalRuntimeArgs = remember { getRuntimeArguments(arguments) }
        RegularScaffold(title = ArgumentPageModel.create(arguments).genPageTitle()) {
            for (entry in buildEntry(arguments)) {
                if (entry.toPage != null) {
                    entry.UiLayout(
                        mergeArguments(
                            listOf(
                                globalRuntimeArgs,
                                ArgumentPageModel.buildNextArgument(arguments)
                            )
                        )
                    )
                } else {
                    entry.UiLayout(globalRuntimeArgs)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ArgumentPagePreview() {
    SettingsTheme {
        ArgumentPageProvider.Page(
            ArgumentPageModel.buildArgument(stringParam = "foo", intParam = 0)
        )
    }
}
