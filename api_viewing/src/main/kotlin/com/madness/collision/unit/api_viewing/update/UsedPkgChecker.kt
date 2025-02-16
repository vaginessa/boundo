/*
 * Copyright 2022 Clifford Liu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.madness.collision.unit.api_viewing.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.madness.collision.BuildConfig
import com.madness.collision.unit.api_viewing.util.AppUsage
import com.madness.collision.util.os.OsUtils

class UsedPkgChecker {
    private var exclusions: Set<String>? = null

    private fun getExclusions(context: Context): Set<String> {
        exclusions?.let { return it }
        val known = arrayOf(
            // self usually appears at the top, unless other apps are used on top of this one
            BuildConfig.APPLICATION_ID,
            "android",
            "com.google.android.permissioncontroller",  // Google permission dialog
        )
        val launcher = getDefaultLauncher(context)
        val set = buildSet(known.size + (launcher?.let { 1 } ?: 0)) {
            addAll(known)
            launcher?.let { add(it) }
        }
        exclusions = set
        return set
    }

    private fun getDefaultLauncher(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveFlag = PackageManager.MATCH_DEFAULT_ONLY
        val info = if (OsUtils.satisfy(OsUtils.T)) {
            val flags = PackageManager.ResolveInfoFlags.of(resolveFlag.toLong())
            context.packageManager.resolveActivity(intent, flags)
        } else {
            context.packageManager.resolveActivityLegacy(intent, resolveFlag)
        }
        return info?.activityInfo?.packageName
    }

    @Suppress("deprecation")
    private fun PackageManager.resolveActivityLegacy(intent: Intent, flags: Int) =
        resolveActivity(intent, flags)

    fun get(context: Context): List<String> {
        val currentTime = System.currentTimeMillis()
        val rawList = AppUsage.getUsed(context, currentTime - 60_000 * 5, currentTime)
        val x = getExclusions(context)
        return rawList.asSequence().filterNot { it in x }.take(10).toList()
    }
}