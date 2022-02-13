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
package com.madness.collision.util.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import androidx.annotation.Px
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.UserHandleCompat
import coil.bitmap.BitmapPool
import coil.decode.DataSource
import coil.decode.Options
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.size.Size
import me.zhanghai.android.appiconloader.iconloaderlib.BaseIconFactory
import me.zhanghai.android.appiconloader.iconloaderlib.BitmapInfo
import java.util.concurrent.ConcurrentLinkedQueue

private object UserSerialNumberCache {
    private const val CACHE_MILLIS: Long = 1000
    private val sCache = ArrayMap<UserHandle, LongArray>()

    fun getSerialNumber(user: UserHandle, context: Context): Long {
        synchronized(sCache) {
            val serialNoWithTime = sCache[user] ?: LongArray(2).also { sCache[user] = it }
            val time = System.currentTimeMillis()
            if (serialNoWithTime[1] + CACHE_MILLIS <= time) {
                serialNoWithTime[0] = user.getSerialNo(context)
                serialNoWithTime[1] = time
            }
            return serialNoWithTime[0]
        }
    }

    private fun UserHandle.getSerialNo(context: Context): Long {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.getSerialNumberForUser(this)
    }
}

private class IconFactory(
    @Px iconBitmapSize: Int, context: Context,
    dpi: Int = context.resources.configuration.densityDpi
) : BaseIconFactory(context, dpi, iconBitmapSize, true) {
    fun createBadgedIconBitmap(
        icon: Drawable, user: UserHandle?, shrinkNonAdaptiveIcons: Boolean, isInstantApp: Boolean): BitmapInfo {
        return super.createBadgedIconBitmap(icon, user, shrinkNonAdaptiveIcons, isInstantApp, null)
    }
}

class AppIconLoader(
    @Px private val iconSize: Int,
    private val shrinkNonAdaptiveIcons: Boolean,
    private val context: Context) {
    private val mIconFactoryPool = ConcurrentLinkedQueue<IconFactory>()

    fun loadIcon(applicationInfo: ApplicationInfo, isInstantApp: Boolean = false): Bitmap {
        val unbadgedIcon = applicationInfo.loadUnbadgedIcon(context.packageManager)
        val user = UserHandleCompat.getUserHandleForUid(applicationInfo.uid)
        // poll-use-offer strategy to ensure iconFactory is thread-safe
        val iconFactory = mIconFactoryPool.poll() ?: IconFactory(iconSize, context)
        try {
            return iconFactory.createBadgedIconBitmap(unbadgedIcon, user, shrinkNonAdaptiveIcons, isInstantApp).icon
        } finally {
            mIconFactoryPool.offer(iconFactory)
        }
    }

    companion object {
        fun getIconKey(applicationInfo: ApplicationInfo, versionCode: Long, context: Context): String {
            val user = UserHandleCompat.getUserHandleForUid(applicationInfo.uid)
            val serialNo = UserSerialNumberCache.getSerialNumber(user, context)
            return applicationInfo.packageName + ":" + versionCode + ":" + serialNo
        }

        fun getIconKey(packageInfo: PackageInfo, context: Context): String {
            return getIconKey(packageInfo.applicationInfo, packageInfo.verCode, context)
        }
    }
}

class AppIconFetcher(@Px iconSize: Int, shrinkNonAdaptiveIcons: Boolean, context: Context) :
    Fetcher<PackageInfo> {
    private val context = context.applicationContext
    private val iconLoader = AppIconLoader(iconSize, shrinkNonAdaptiveIcons, this.context)

    override suspend fun fetch(pool: BitmapPool, data: PackageInfo, size: Size, options: Options): FetchResult {
        val icon = iconLoader.loadIcon(data.applicationInfo)
        return DrawableResult(BitmapDrawable(context.resources, icon), true, DataSource.DISK)
    }

    override fun handles(data: PackageInfo): Boolean = data.handleable

    override fun key(data: PackageInfo): String = AppIconLoader.getIconKey(data, context)
}

interface PackageInfo {
    val handleable: Boolean
    val verCode: Long
    val applicationInfo: ApplicationInfo
}

interface ApplicationInfo {
    val uid: Int
    val packageName: String
    fun loadUnbadgedIcon(pm: PackageManager): Drawable
}

interface CompactPackageInfo : PackageInfo, ApplicationInfo {
    override val applicationInfo: ApplicationInfo get() = this
}

class AppIconPackageInfo(pack: android.content.pm.PackageInfo): CompactPackageInfo {
    private val app = pack.applicationInfo
    override val handleable: Boolean = true
    override val verCode: Long = PackageInfoCompat.getLongVersionCode(pack)
    override val uid: Int = app.uid
    override val packageName: String = app.packageName
    override fun loadUnbadgedIcon(pm: PackageManager): Drawable = app.loadUnbadgedIcon(pm)
}
