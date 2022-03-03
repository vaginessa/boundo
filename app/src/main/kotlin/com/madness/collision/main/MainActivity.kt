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

package com.madness.collision.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.madness.collision.base.BaseActivity
import com.madness.collision.databinding.ActivityMainBinding
import com.madness.collision.diy.WindowInsets
import com.madness.collision.main.updates.UpdatesFragment
import com.madness.collision.misc.MiscMain
import com.madness.collision.unit.Unit
import com.madness.collision.unit.api_viewing.AccessAV
import com.madness.collision.util.*
import com.madness.collision.util.controller.systemUi
import com.madness.collision.util.notice.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : BaseActivity() {
    companion object {
        /**
         * the activity to launch
         */
        const val LAUNCH_ACTIVITY = "launchActivity"
        /**
         * the target(fragment) to launch
         */
        const val LAUNCH_ITEM = "launchItem"
        const val LAUNCH_ITEM_ARGS = "launchItemArgs"

        const val ACTION_RECREATE = "mainRecreate"
        /**
         * update exterior after background has changed
         */
        const val ACTION_EXTERIOR = "mainExterior"
        const val ACTION_EXTERIOR_THEME = "mainExteriorTheme"

        fun forItem(name: String, args: Bundle? = null): Bundle {
            val extras = Bundle()
            extras.putString(LAUNCH_ITEM, name)
            if (args != null) extras.putParcelable(LAUNCH_ITEM_ARGS, args)
            return extras
        }
    }

    // session data
    private var launchItem: String? = null
    private val viewModel: MainViewModel by viewModels()
    // ui appearance data
    private var primaryStatusBarConfig: SystemBarConfig? = null
    private var primaryNavBarConfig: SystemBarConfig? = null
    // views
    private lateinit var viewBinding: ActivityMainBinding
    // android
    private lateinit var mContext: Context
    private lateinit var mWindow: Window

    private val background: View get() = viewBinding.mainContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this
        mContext = context

//        val elapsingSplash = ElapsingTime()
//        installSplashScreen().run {
//            setKeepVisibleCondition {
//                elapsingSplash.elapsed() < 3000
//            }
//        }

        val prefSettings = getSharedPreferences(P.PREF_SETTINGS, Context.MODE_PRIVATE)

        // this needs to be invoked before update fragments are rendered
        viewModel.updateTimestamp()

        mWindow = window
        launchItem = intent?.getStringExtra(LAUNCH_ITEM)

        inflateLayout(context)

        lifecycleScope.launch(Dispatchers.Default) {
            initApplication(context, prefSettings)
            MiscMain.ensureUpdate(context, prefSettings)
            Unit.loadUnitClasses(context)

            withContext(Dispatchers.Main) {
                setupLayout(context, prefSettings, savedInstanceState)
            }

            checkMisc(context, prefSettings)
            checkTarget(context)
        }
    }

    private suspend fun initApplication(context: Context, prefSettings: SharedPreferences) {
        val app = mainApplication
        if (!app.dead) return
        app.debug = prefSettings.getBoolean(P.ADVANCED, false)
        if (!app.isDarkTheme) MiscMain.updateExteriorBackgrounds(context)
        app.dead = false
    }

    private fun inflateLayout(context: Context) {
        systemUi { fullscreen() }

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
    }

    private fun setupLayout(context: Context, prefSettings: SharedPreferences, savedState: Bundle?) {
        setupNav(savedState)
        setupViewModel(context, prefSettings)
        // invocation will clear stack
        setupUi(context)

        val insetBottom = viewModel.insetBottom.value ?: 0
        val configs = SystemUtil.applyDefaultSystemUiVisibility(mContext, mWindow, insetBottom)
        primaryStatusBarConfig = configs.first
        primaryNavBarConfig = configs.second
    }

    private fun setupNav(savedState: Bundle?) {
        if (savedState != null) return  // fragment is restored automatically
        val startArgs = if (launchItem != null) {
            Bundle().apply { putInt(UpdatesFragment.ARG_MODE, UpdatesFragment.MODE_NO_UPDATES) }
        } else null
        val fragment = MainFragment().apply { startArgs?.let { arguments = it } }
        val containerId = viewBinding.mainFragmentWrapper.id
        supportFragmentManager.beginTransaction().add(containerId, fragment).commit()
    }

    private fun setupViewModel(context: Context, prefSettings: SharedPreferences) {
        viewModel.democratic
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { clearDemocratic() }
            .launchIn(lifecycleScope)
        viewModel.page
            .flowWithLifecycle(lifecycle)
            .onEach {
                showPage(it.fragment)
                val shouldExit = it.args[1]
                if (shouldExit) finish()
            }
            .launchIn(lifecycleScope)
        viewModel.action.observe(this) {
            it ?: return@observe
            if (it.first.isBlank()) return@observe
            if (it.first == ACTION_EXTERIOR) updateExterior()
            mainApplication.setAction(it)
            viewModel.action.value = "" to null
        }
        viewModel.background.observe(this) {
            applyExterior()
        }
    }

    private fun setupUi(context: Context) {
        checkTargetItem()

        background.setOnApplyWindowInsetsListener { v, insets ->
            val isRtl = if (v.isLayoutDirectionResolved) v.layoutDirection == View.LAYOUT_DIRECTION_RTL else false
            consumeInsets(WindowInsets(insets, isRtl))
            insets
        }

        if (!mainApplication.dead) {
            viewModel.background.value = mainApplication.background
        }
    }

    private fun checkTargetItem() {
        val launchItemName = launchItem
        if (launchItemName.isNullOrEmpty()) return
        val itemArgs = intent?.getBundleExtra(LAUNCH_ITEM_ARGS)
        val hasArgs = itemArgs != null
        lifecycleScope.launch(Dispatchers.Default) {
            // wait for Unit init
            delay(200)
            launchItem = null
            val itemUnitDesc = Unit.getDescription(launchItemName) ?: return@launch
            withContext(Dispatchers.Main) {
                if (hasArgs) {
                    viewModel.displayUnit(itemUnitDesc.unitName, false, true, itemArgs)
                } else {
                    viewModel.displayUnit(itemUnitDesc.unitName, shouldExitAppAfterBack = true)
                }
            }
        }
    }

    /**
     * update notification availability, notification channels and check app update
     */
    private suspend fun checkMisc(context: Context, prefSettings: SharedPreferences) {
        val app = mainApplication
        // enable notification
        app.notificationAvailable = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!app.notificationAvailable && Random(System.currentTimeMillis()).nextInt(10) == 0) {
            withContext(Dispatchers.Main) {
                ToastUtils.popRequestNotification(this@MainActivity)
            }
        }
        MiscMain.registerNotificationChannels(context, prefSettings)

//        prHandler = PermissionRequestHandler(this)
//        SettingsFunc.check4Update(this, null, prHandler)
    }

    private suspend fun checkTarget(context: Context) {
        val activityName = intent.getStringExtra(LAUNCH_ACTIVITY) ?: ""
        if (activityName.isEmpty()) return
        val target = try {
            Class.forName(activityName)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            return
        }
        val startIntent = Intent(context, target)
        startIntent.putExtras(intent)
        delay(100)
        withContext(Dispatchers.Main) {
            startActivity(startIntent)
        }
    }

    override fun onDestroy() {
        val context = mContext
        AccessAV.clearContext()
        AccessAV.clearTags()
        AccessAV.clearSeals()
        MiscMain.clearCache(context)
        super.onDestroy()
    }

    private fun clearDemocratic() {
        // Low profile mode is used in ApiDecentFragment. Deprecated since Android 11.
        if (X.belowOff(X.R)) disableLowProfileModeLegacy(window)
        primaryStatusBarConfig?.let {
            SystemUtil.applyStatusBarConfig(mContext, mWindow, it)
        }
        primaryNavBarConfig?.let {
            SystemUtil.applyNavBarConfig(mContext, mWindow, it)
        }
    }

    @Suppress("deprecation")
    private fun disableLowProfileModeLegacy(window: Window) {
        val decorView = window.decorView
        decorView.systemUiVisibility = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LOW_PROFILE.inv()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (SystemUtil.isSystemTvUi(this).not()) return super.onKeyUp(keyCode, event)
        val navKeyCode = SystemUtil.unifyTvNavKeyCode(keyCode)
        when(navKeyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> kotlin.Unit
            KeyEvent.KEYCODE_DPAD_DOWN -> kotlin.Unit
            KeyEvent.KEYCODE_DPAD_LEFT -> kotlin.Unit
            KeyEvent.KEYCODE_DPAD_RIGHT -> kotlin.Unit
            KeyEvent.KEYCODE_ENTER -> kotlin.Unit
            KeyEvent.KEYCODE_BACK -> kotlin.Unit
            KeyEvent.KEYCODE_HOME -> kotlin.Unit
            KeyEvent.KEYCODE_MENU -> kotlin.Unit
        }
        return super.onKeyUp(navKeyCode, event)
    }

    private fun consumeInsets(insets: WindowInsets) {
        val app = mainApplication
        app.insetTop = insets.top
        app.insetBottom = insets.bottom
        app.insetStart = insets.start
        app.insetEnd = insets.end
        viewModel.insetTop.value = app.insetTop
        viewModel.insetBottom.value = app.insetBottom
        viewModel.insetStart.value = app.insetStart
        viewModel.insetEnd.value = app.insetEnd
    }

    private fun applyExterior() {
        val app = mainApplication
        if (!app.exterior) return
        if (app.background == null) {
            background.background = null
            return
        }
        val back = mainApplication.background ?: return
        val size = SystemUtil.getRuntimeWindowSize(mContext)
        val bitmap = BackgroundUtil.getBackground(back, size.x, size.y)
        background.background = BitmapDrawable(mContext.resources, bitmap)
    }

    /**
     * for app background changing
     */
    private fun updateExterior() {
        primaryNavBarConfig = SystemBarConfig(mainApplication.isPaleTheme, isTransparentBar = mainApplication.exterior)
        SystemUtil.applyNavBarConfig(mContext, mWindow, primaryNavBarConfig!!)
        if (!mainApplication.exterior) background.background = null
        viewModel.background.value = mainApplication.background
    }
}
