package io.appium.espressoserver.test.ultron

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class UltronActionActivity : Activity() {
    lateinit var button: Button
    lateinit var input: EditText
    var clicks = 0
    var lastTouchX = 0f
    var lastTouchY = 0f
    val keyEvents = mutableListOf<KeyEvent>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyEvents.add(KeyEvent(event))
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button = Button(this).apply {
            id = BUTTON_ID
            isAllCaps = false
            text = "Click"
            setOnClickListener { clicks++ }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                false
            }
        }
        input = EditText(this).apply { id = INPUT_ID }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button)
            addView(input)
        })
    }

    companion object {
        const val BUTTON_ID = 0x0100ff01
        const val INPUT_ID = 0x0100ff02
    }
}
