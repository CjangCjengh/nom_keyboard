package com.nomkeyboard.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.nomkeyboard.app.NomInputMethodService
import com.nomkeyboard.app.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .remove(NomInputMethodService.PREF_RECENT)
                .apply()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_user_dict).setOnClickListener {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        }
        // Focus the test EditText so the keyboard pops up for quick trial.
        val etTest = findViewById<EditText>(R.id.et_test)
        try {
            etTest.typeface = Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (_: Throwable) {
            // Fall back to the default typeface silently if the font asset is missing.
        }
        etTest.requestFocus()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
        }
    }
}
