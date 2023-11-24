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

import android.content.Intent
import android.content.pm.ActivityInfo.*
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import org.mtopol.assistant.databinding.ActivityMainBinding


class SharedImageViewModel : ViewModel() {
    val imgUriLiveData = MutableLiveData<List<Uri>>()
}

class MainActivity : AppCompatActivity() {

    private val sharedImageViewModel: SharedImageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("lifecycle", "onCreate MainActivity")
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)
        setContentView(ActivityMainBinding.inflate(layoutInflater).root)
        resetOpenAi()
        if (mainPrefs.openaiApiKey.isBlank()) {
            if (savedInstanceState == null) {
                navigateToApiKeyFragment()
            }
            return
        }
        // This point reached only when API key is present
        handleImageIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("lifecycle", "onDestroy MainActivity")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i("lifecycle", "onNewIntent MainActivity")
        handleImageIntent(intent)
    }

    private val navController get() =
        (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController

    fun navigateToApiKeyFragment() {
        navController.navigate(R.id.fragment_api_key, null,
            NavOptions.Builder().setPopUpTo(R.id.fragment_chat, true).build())
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

    private fun handleImageIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val type = intent.type ?: return
        if (!type.startsWith("image/")) {
            return
        }
        val imageUris: List<Uri>? = when (action) {
            Intent.ACTION_SEND -> {
                val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                imageUri?.let { listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }
        if (imageUris != null) {
            sharedImageViewModel.imgUriLiveData.value = imageUris
            navController.popBackStack(R.id.fragment_chat, false)
        }
    }
}
