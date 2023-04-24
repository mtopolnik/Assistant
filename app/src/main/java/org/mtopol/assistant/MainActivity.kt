/*
 * Copyright (C) 2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.mtopol.assistant

import android.content.pm.ActivityInfo.*
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import org.mtopol.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("lifecycle", "onCreate MainActivity")
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)
        setContentView(ActivityMainBinding.inflate(layoutInflater).root)
        resetOpenAi(this)
        if (savedInstanceState != null) {
            return
        }
        // Continue here only on the first time since app startup.
        val openaiApiKey = mainPrefs.openaiApiKey
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navHostFragment.navController.navigate(
            if (openaiApiKey == "") {
                R.id.fragment_api_key
            } else {
                Log.i("lifecycle", "Navigate to chat fragment")
                R.id.fragment_chat
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("lifecycle", "onDestroy MainActivity")
    }

    fun lockOrientation() {
        requestedOrientation = currentOrientation
    }

    fun unlockOrientation() {
        requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
    }

    private val currentOrientation: Int get() {
        val display =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION") windowManager.defaultDisplay
            }
                ?: return SCREEN_ORIENTATION_UNSPECIFIED
        val rotation = display.rotation
        return when (resources.configuration.orientation) {
            ORIENTATION_PORTRAIT -> {
                when (rotation) {
                    ROTATION_0, ROTATION_90 -> SCREEN_ORIENTATION_PORTRAIT
                    else -> SCREEN_ORIENTATION_REVERSE_PORTRAIT
                }
            }
            else -> {
                when (rotation) {
                    ROTATION_90, ROTATION_180 -> SCREEN_ORIENTATION_LANDSCAPE
                    else -> SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                }
            }
        }
    }

}
