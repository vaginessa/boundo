/*
 * Copyright 2021 Clifford Liu
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

package com.madness.collision.unit.api_viewing.list

import android.content.Context
import android.content.Intent
import android.content.pm.*
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.madness.collision.R
import com.madness.collision.misc.MiscApp
import com.madness.collision.unit.api_viewing.Utils
import com.madness.collision.unit.api_viewing.data.ApiViewingApp
import com.madness.collision.unit.api_viewing.data.VerInfo
import com.madness.collision.util.SystemUtil
import com.madness.collision.util.X
import com.madness.collision.util.os.OsUtils
import java.text.SimpleDateFormat
import java.util.*
import javax.security.cert.CertificateException
import javax.security.cert.X509Certificate
import com.madness.collision.unit.api_viewing.R as RAv

internal class AppListService {
    private var regexFields: MutableMap<String, String> = HashMap()

    fun getAppDetails(context: Context, appInfo: ApiViewingApp): CharSequence {
        val builder = SpannableStringBuilder()
        val reOne = retrieveOn(context, appInfo, 0, "")
        if (!reOne.first) return ""
        var pi = reOne.second!!
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", SystemUtil.getLocaleApp())
        val spanFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        builder.append(context.getString(R.string.apiDetailsPackageName), StyleSpan(Typeface.BOLD), spanFlags)
                .append(pi.packageName ?: "").append('\n')
        builder.append(context.getString(RAv.string.apiDetailsVerName), StyleSpan(Typeface.BOLD), spanFlags)
                .append(pi.versionName ?: "")
                .append('\n')
        builder.append(context.getString(RAv.string.apiDetailsVerCode), StyleSpan(Typeface.BOLD), spanFlags)
                .append(PackageInfoCompat.getLongVersionCode(pi).toString()).append('\n')

        val sdkInfo = { ver: VerInfo ->
            val sdk = "Android ${ver.sdk}"
            val codeName = ver.codeName(context)
            val sdkDetails = if (codeName != ver.sdk) "$sdk, $codeName" else sdk
            ver.api.toString() + context.getString(R.string.textParentheses, sdkDetails)
        }

        val targetVer = VerInfo(appInfo.targetAPI, true)
        builder.append(context.getString(R.string.apiSdkTarget), StyleSpan(Typeface.BOLD), spanFlags)
                .append(context.getString(R.string.textColon), StyleSpan(Typeface.BOLD), spanFlags)
                .append(sdkInfo.invoke(targetVer))
                .append('\n')

        val minVer = VerInfo(appInfo.minAPI, true)
        builder.append(context.getString(R.string.apiSdkMin), StyleSpan(Typeface.BOLD), spanFlags)
                .append(context.getString(R.string.textColon), StyleSpan(Typeface.BOLD), spanFlags)
                .append(sdkInfo.invoke(minVer))
                .append('\n')

        if (appInfo.isNotArchive) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = pi.firstInstallTime
            builder.append(context.getString(RAv.string.apiDetailsFirstInstall), StyleSpan(Typeface.BOLD), spanFlags)
                    .append(format.format(cal.time))
                    .append('\n')
            cal.timeInMillis = pi.lastUpdateTime
            builder.append(context.getString(RAv.string.apiDetailsLastUpdate), StyleSpan(Typeface.BOLD), spanFlags)
                    .append(format.format(cal.time))
                    .append('\n')
            val installer: String? = if (X.aboveOn(X.R)) {
                val si = try {
                    context.packageManager.getInstallSourceInfo(appInfo.packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    e.printStackTrace()
                    null
                }
                if (si != null) {
                    // todo initiatingPackageName
//                        builder.append("InitiatingPackageName: ", StyleSpan(Typeface.BOLD), spanFlags)
                    // If the package that requested the install has been uninstalled,
                    // only this app's own install information can be retrieved
//                        builder.append(si.initiatingPackageName ?: "Unknown").append('\n')
//                        builder.append("OriginatingPackageName: ", StyleSpan(Typeface.BOLD), spanFlags)
                    // If not holding the INSTALL_PACKAGES permission then the result will always return null
//                        builder.append(si.originatingPackageName ?: "Unknown").append('\n')
                    si.installingPackageName
                } else {
                    null
                }
            } else {
                getInstallerLegacy(context, appInfo)
            }
            builder.append(context.getString(RAv.string.apiDetailsInsatllFrom), StyleSpan(Typeface.BOLD), spanFlags)
            if (installer != null) {
                val installerName = MiscApp.getApplicationInfo(context, packageName = installer)
                        ?.loadLabel(context.packageManager)?.toString() ?: ""
                if (installerName.isNotEmpty()) {
                    builder.append(installerName)
                } else {
                    val installerAndroid = ApiViewingApp.packagePackageInstaller
                    val installerGPlay = ApiViewingApp.packagePlayStore
                    when (installer) {
                        installerGPlay ->
                            builder.append(context.getString(RAv.string.apiDetailsInstallGP))
                        installerAndroid ->
                            builder.append(context.getString(RAv.string.apiDetailsInstallPI))
                        "null" ->
                            builder.append(context.getString(RAv.string.apiDetailsInstallUnknown))
                        else ->
                            builder.append(installer)
                    }
                }
            } else {
                builder.append(context.getString(RAv.string.apiDetailsInstallUnknown))
            }
            builder.append('\n')
        }

        if (!appInfo.isNativeLibrariesRetrieved) appInfo.retrieveNativeLibraries()
        val nls = appInfo.nativeLibraries
        builder.append(context.getString(R.string.av_details_native_libs), StyleSpan(Typeface.BOLD), spanFlags)
                .append("armeabi-v7a ").append(if (nls[0]) '✓' else '✗').append("  ")
                .append("arm64-v8a ").append(if (nls[1]) '✓' else '✗').append("  ")
                .append("x86 ").append(if (nls[2]) '✓' else '✗').append("  ")
                .append("x86_64 ").append(if (nls[3]) '✓' else '✗').append("  ")
                .append("Flutter ").append(if (nls[4]) '✓' else '✗').append("  ")
                .append("React Native ").append(if (nls[5]) '✓' else '✗').append("  ")
                .append("Xamarin ").append(if (nls[6]) '✓' else '✗').append("  ")
                .append("Kotlin ").append(if (nls[7]) '✓' else '✗')
                .append('\n')

        var permissions: Array<String> = emptyArray()
        var activities: Array<ActivityInfo> = emptyArray()
        var receivers: Array<ActivityInfo> = emptyArray()
        var services: Array<ServiceInfo> = emptyArray()
        var providers: Array<ProviderInfo> = emptyArray()

        val flagGetDisabled = if (OsUtils.satisfy(OsUtils.N)) PackageManager.MATCH_DISABLED_COMPONENTS
        else flagGetDisabledLegacy
        val flagSignature = if (X.aboveOn(X.P)) PackageManager.GET_SIGNING_CERTIFICATES
        else getSigFlagLegacy
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or flagGetDisabled or flagSignature
        val reDetails = retrieveOn(context, appInfo, flags, "details")
        if (reDetails.first) {
            pi = reDetails.second!!
            permissions = pi.requestedPermissions ?: emptyArray()
            activities = pi.activities ?: emptyArray()
            receivers = pi.receivers ?: emptyArray()
            services = pi.services ?: emptyArray()
            providers = pi.providers ?: emptyArray()
        } else {
            retrieveOn(context, appInfo, PackageManager.GET_PERMISSIONS, "permissions").let {
                if (it.first) permissions = it.second!!.requestedPermissions ?: emptyArray()
            }
            retrieveOn(context, appInfo, PackageManager.GET_ACTIVITIES or flagGetDisabled, "activities").let {
                if (it.first) activities = it.second!!.activities ?: emptyArray()
            }
            retrieveOn(context, appInfo, PackageManager.GET_RECEIVERS or flagGetDisabled, "receivers").let {
                if (it.first) receivers = it.second!!.receivers ?: emptyArray()
            }
            retrieveOn(context, appInfo, PackageManager.GET_SERVICES or flagGetDisabled, "services").let {
                if (it.first) services = it.second!!.services ?: emptyArray()
            }
            retrieveOn(context, appInfo, flagSignature, "signing").let {
                if (it.first) pi = it.second!!
            }
        }

        var signatures: Array<Signature> = emptyArray()
        if (X.aboveOn(X.P)) {
            if (pi.signingInfo != null) {
                signatures = if (pi.signingInfo.hasMultipleSigners()) {
                    pi.signingInfo.apkContentsSigners
                } else {
                    pi.signingInfo.signingCertificateHistory
                }
            }
        } else {
            val piSignature = pi.sigLegacy
            if (piSignature != null) signatures = piSignature
        }
        if (regexFields.isEmpty()) {
            Utils.principalFields(context, regexFields)
        }
        for (s in signatures) {
            val cert: X509Certificate? = try {
                X509Certificate.getInstance(s.toByteArray())
            } catch (e: CertificateException) {
                e.printStackTrace()
                null
            }
            if (cert != null) {
                val issuerInfo = Utils.getDesc(regexFields, cert.issuerDN)
                val subjectInfo = Utils.getDesc(regexFields, cert.subjectDN)
                val formerPart = "\n\nX.509 " +
                        context.getString(RAv.string.apiDetailsCert) +
                        "\nNo." + cert.serialNumber.toString(16).toUpperCase(SystemUtil.getLocaleApp()) +
                        " v" +
                        (cert.version + 1).toString() +
                        '\n' + context.getString(RAv.string.apiDetailsValiSince)
                builder.append(formerPart, StyleSpan(Typeface.BOLD), spanFlags)
                        .append(format.format(cert.notBefore)).append('\n')
                        .append(context.getString(RAv.string.apiDetailsValiUntil), StyleSpan(Typeface.BOLD), spanFlags)
                        .append(format.format(cert.notAfter)).append('\n')
                        .append(context.getString(RAv.string.apiDetailsIssuer), StyleSpan(Typeface.BOLD), spanFlags)
                        .append(issuerInfo).append('\n')
                        .append(context.getString(RAv.string.apiDetailsSubject), StyleSpan(Typeface.BOLD), spanFlags)
                        .append(subjectInfo).append('\n')
                        .append(context.getString(RAv.string.apiDetailsSigAlg), StyleSpan(Typeface.BOLD), spanFlags)
                        .append(cert.sigAlgName).append('\n')
                        .append(context.getString(RAv.string.apiDetailsSigAlgOID), StyleSpan(Typeface.BOLD), spanFlags)
                        .append(cert.sigAlgOID)
                        .append('\n')
            }
        }

        builder.appendSection(context, RAv.string.apiDetailsPermissions)
        if (permissions.isNotEmpty()) {
            Arrays.sort(permissions)
            for (permission in permissions) {
                builder.append(permission).append('\n')
            }
        } else {
            builder.append(context.getString(R.string.text_no_content)).append('\n')
        }

        builder.run {
            appendCompSection(context, RAv.string.apiDetailsActivities, activities)
            appendCompSection(context, RAv.string.apiDetailsReceivers, receivers)
            appendCompSection(context, RAv.string.apiDetailsServices, services)
            appendCompSection(context, RAv.string.apiDetailsProviders, providers)
        }

        return SpannableString.valueOf(builder)
    }

    private fun SpannableStringBuilder.appendSection(context: Context, titleId: Int) {
        append("\n\n")
        append(context.getString(titleId), StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        append('\n')
    }

    private fun SpannableStringBuilder.appendComp(context: Context, components: Array<out ComponentInfo>) {
        if (components.isNotEmpty()) {
            Arrays.sort(components) { o1, o2 -> o1.name.compareTo(o2.name) }
            for (p in components) append(p.name).append('\n')
        } else {
            append(context.getString(R.string.text_no_content)).append('\n')
        }
    }

    private fun SpannableStringBuilder.appendCompSection(
            context: Context, titleId: Int, components: Array<out ComponentInfo>) {
        appendSection(context, titleId)
        appendComp(context, components)
    }

    @Suppress("deprecation")
    private fun getInstallerLegacy(context: Context, app: ApiViewingApp): String? {
        return try {
            context.packageManager.getInstallerPackageName(app.packageName)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            null
        }
    }

    @Suppress("deprecation")
    private val getSigFlagLegacy = PackageManager.GET_SIGNATURES

    @Suppress("deprecation")
    private val flagGetDisabledLegacy = PackageManager.GET_DISABLED_COMPONENTS

    @Suppress("deprecation")
    private val PackageInfo.sigLegacy: Array<Signature>?
        get() = signatures

    private fun retrieveOn(context: Context, appInfo: ApiViewingApp, extraFlags: Int, subject: String): Pair<Boolean, PackageInfo?> {
        var pi: PackageInfo? = null
        return try {
            pi = if (appInfo.isArchive) {
                context.packageManager.getPackageArchiveInfo(appInfo.appPackage.basePath, extraFlags)
            } else {
                context.packageManager.getPackageInfo(appInfo.packageName, extraFlags)
            }
            true to pi
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("APIAdapter", String.format("failed to retrieve %s of %s", subject, appInfo.packageName))
            false to pi
        }
    }

    fun getLaunchIntent(context: Context, app: ApiViewingApp): Intent? {
        return context.packageManager.getLaunchIntentForPackage(app.packageName)
    }
}