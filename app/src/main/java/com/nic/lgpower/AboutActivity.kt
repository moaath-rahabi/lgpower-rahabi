package com.nic.lgpower

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * AboutActivity: Displays Rahabi LG Power community edition attribution,
 * official branding, original open-source credits, licenses, and links.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageManager.init(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        applyTheme()
        initViews()
    }

    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.about_root), theme)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btn_about_back).setOnClickListener { finish() }

        // Dynamic version
        findViewById<TextView>(R.id.tv_about_version).text =
            getString(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // Links
        findViewById<View>(R.id.row_link_upstream).setOnClickListener {
            openUrl("https://github.com/Nicsilver/LGPower")
        }
        findViewById<View>(R.id.row_link_repo).setOnClickListener {
            openUrl("https://github.com/moaath-rahabi/lgpower-rahabi")
        }
        findViewById<View>(R.id.row_link_release)?.setOnClickListener {
            openUrl("https://github.com/moaath-rahabi/lgpower-rahabi/releases/latest")
        }

        // Release notes
        findViewById<View>(R.id.row_about_release_notes).setOnClickListener {
            showReleaseNotes()
        }

        // License
        findViewById<View>(R.id.row_about_license).setOnClickListener {
            showLicenseDialog()
        }

        // Third-party notices
        findViewById<View>(R.id.row_about_third_party).setOnClickListener {
            showThirdPartyNotices()
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun showReleaseNotes() {
        val releases = ReleaseNotes.all
        val sb = StringBuilder()
        for (rel in releases) {
            sb.append("• ${rel.name} (${rel.date}):\n")
            for (note in rel.notes) {
                sb.append("   - $note\n")
            }
            sb.append("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("Release Notes")
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLicenseDialog() {
        val licenseSummary = """
GNU AFFERO GENERAL PUBLIC LICENSE
Version 3, 19 November 2007

Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>

Rahabi LG Power is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

Original Project:
Copyright (C) 2024–2026 Nicsilver and contributors.

Rahabi Community Enhancements:
Copyright (C) 2026 Eng. Moaath Rahabi.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

Full license text is available in the LICENSE file in the source repository.
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("GNU AGPL-3.0 License")
            .setMessage(licenseSummary)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showThirdPartyNotices() {
        val notices = """
Rahabi LG Power incorporates open-source components under permissive licenses:

• Android Jetpack (AndroidX)
  Copyright (C) The Android Open Source Project
  Licensed under the Apache License, Version 2.0

• OkHttp
  Copyright (C) Square, Inc.
  Licensed under the Apache License, Version 2.0

• Kotlin Standard Library & Coroutines
  Copyright 2010-2026 JetBrains s.r.o.
  Licensed under the Apache License, Version 2.0

• Android Palette
  Copyright (C) The Android Open Source Project
  Licensed under the Apache License, Version 2.0

• Google Play Billing Library
  Copyright (C) Google LLC
  Licensed under the Android Software Development Kit License
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Third-Party Notices")
            .setMessage(notices)
            .setPositiveButton("Close", null)
            .show()
    }
}
