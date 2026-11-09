package com.yiddishvtt.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class KeyboardSetupActivity : AppCompatActivity() {

    private data class Step(val title: String, val body: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_setup)

        findViewById<MaterialToolbar>(R.id.keyboardSetupAppBar).setNavigationOnClickListener { finish() }

        val tabs = findViewById<TabLayout>(R.id.tabsKeyboard)
        val container = findViewById<LinearLayout>(R.id.llSteps)

        val keyboards = listOf(
            "Gboard" to gboardSteps(),
            "Samsung" to samsungSteps(),
            "SwiftKey" to swiftKeySteps(),
            getString(R.string.keyboard_setup_tab_other) to otherSteps()
        )

        keyboards.forEach { (label, _) -> tabs.addTab(tabs.newTab().setText(label)) }

        renderSteps(container, keyboards[0].second)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                renderSteps(container, keyboards[tab.position].second)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<MaterialButton>(R.id.btnOpenSystemSettings).setOnClickListener {
            // Opens the system input methods picker so the user can install / pick the keyboard.
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun renderSteps(container: LinearLayout, steps: List<Step>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        steps.forEachIndexed { index, step ->
            val row = inflater.inflate(R.layout.row_keyboard_step, container, false)
            row.findViewById<TextView>(R.id.tvStepNumber).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.tvStepTitle).text = step.title
            row.findViewById<TextView>(R.id.tvStepBody).text = step.body
            container.addView(row)
            if (index < steps.size - 1) {
                val divider = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@KeyboardSetupActivity, R.color.heimish_hairline))
                }
                container.addView(divider)
            }
        }
    }

    private fun gboardSteps() = listOf(
        Step(getString(R.string.kb_gboard_step1_title), getString(R.string.kb_gboard_step1_body)),
        Step(getString(R.string.kb_gboard_step2_title), getString(R.string.kb_gboard_step2_body)),
        Step(getString(R.string.kb_gboard_step3_title), getString(R.string.kb_gboard_step3_body)),
        Step(getString(R.string.kb_gboard_step4_title), getString(R.string.kb_gboard_step4_body))
    )

    private fun samsungSteps() = listOf(
        Step(getString(R.string.kb_samsung_step1_title), getString(R.string.kb_samsung_step1_body)),
        Step(getString(R.string.kb_samsung_step2_title), getString(R.string.kb_samsung_step2_body)),
        Step(getString(R.string.kb_samsung_step3_title), getString(R.string.kb_samsung_step3_body))
    )

    private fun swiftKeySteps() = listOf(
        Step(getString(R.string.kb_swiftkey_step1_title), getString(R.string.kb_swiftkey_step1_body)),
        Step(getString(R.string.kb_swiftkey_step2_title), getString(R.string.kb_swiftkey_step2_body)),
        Step(getString(R.string.kb_swiftkey_step3_title), getString(R.string.kb_swiftkey_step3_body))
    )

    private fun otherSteps() = listOf(
        Step(getString(R.string.kb_other_step1_title), getString(R.string.kb_other_step1_body)),
        Step(getString(R.string.kb_other_step2_title), getString(R.string.kb_other_step2_body))
    )
}
