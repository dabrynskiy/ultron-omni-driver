package io.appium.espressoserver.test.ultron

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CustomFeedView(context: Context) : RecyclerView(context)

class UltronRecyclerActivity : Activity() {
    lateinit var recycler: CustomFeedView
    var selected = -1
    var clicks = 0
    var longClicks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recycler = CustomFeedView(this).apply {
            id = LIST_ID
            layoutManager = LinearLayoutManager(this@UltronRecyclerActivity)
            adapter = Rows()
        }
        setContentView(recycler)
    }

    inner class Rows(private val count: Int = 80) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = count
        override fun getItemId(position: Int) = 9007199254740993L + position
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 240)
                addView(TextView(context).apply { id = LABEL_ID })
                addView(Button(context).apply { id = BUTTON_ID; text = "Open"; isAllCaps = false })
                addView(EditText(context).apply { id = INPUT_ID; isSingleLine = true })
            }
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            row.findViewById<TextView>(LABEL_ID).text = "Item $position"
            row.findViewById<EditText>(INPUT_ID).setText("Value $position")
            row.setOnClickListener { selected = position; clicks++ }
            row.findViewById<Button>(BUTTON_ID).setOnClickListener { selected = position; clicks++ }
            row.findViewById<Button>(BUTTON_ID).setOnLongClickListener { selected = position; longClicks++; true }
            row.setOnLongClickListener { selected = position; longClicks++; true }
        }
    }

    companion object {
        const val LIST_ID = 0x0100fe01
        const val LABEL_ID = 0x0100fe02
        const val BUTTON_ID = 0x0100fe03
        const val INPUT_ID = 0x0100fe04
    }
}
