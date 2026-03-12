package com.innosence.disastersos.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.innosence.disastersos.MainActivity
import com.innosence.disastersos.R
import com.innosence.disastersos.data.PreferencesHelper

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var prefsHelper: PreferencesHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_selection)

        prefsHelper = PreferencesHelper(this)

        // Intentionally NOT skipping to MainActivity anymore
        // The user must pick a role on every launch.

        val btnRoleVictim = findViewById<LinearLayout>(R.id.btnRoleVictim)
        val btnRoleRescuer = findViewById<LinearLayout>(R.id.btnRoleRescuer)

        btnRoleVictim.setOnClickListener {
            prefsHelper.saveUserRole(PreferencesHelper.ROLE_VICTIM)
            startMainActivity()
        }

        btnRoleRescuer.setOnClickListener {
            prefsHelper.saveUserRole(PreferencesHelper.ROLE_RESCUER)
            startMainActivity()
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        // Clear this activity from the back stack so the user can't press 'Back' to return to it
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
