package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.db.HistoryRepo
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 방문 기록 화면: 검색 필터 + 전체 삭제 + 도메인 아바타 목록 (Chrome 스타일) */
class HistoryActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(newBase))
    }

    private lateinit var adapter: HistoryAdapter
    private var all = listOf<Triple<String, String, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnClearAll).setOnClickListener { confirmClear() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter { url ->
            setResult(RESULT_OK, Intent().putExtra("url", url))
            finish()
        }
        list.adapter = adapter

        findViewById<EditText>(R.id.editFilter).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        reload()
    }

    private fun reload() {
        all = HistoryRepo.all(this)
        filter(findViewById<EditText>(R.id.editFilter).text?.toString() ?: "")
    }

    private fun filter(q: String) {
        val query = q.lowercase()
        adapter.submit(
            if (query.isBlank()) all
            else all.filter { it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }
        )
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("방문 기록 삭제")
            .setMessage("모든 방문 기록을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                HistoryRepo.clear(this)
                reload()
                com.example.streambrowser.util.JcToast.show(this, "삭제되었습니다.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 어댑터 ----------------

    class HistoryAdapter(
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private var rows = listOf<Triple<String, String, Long>>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<Triple<String, String, Long>>) {
            rows = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: TextView = v.findViewById(R.id.avatar)
            val title: TextView = v.findViewById(R.id.rowTitle)
            val sub: TextView = v.findViewById(R.id.rowSub)
            val time: TextView = v.findViewById(R.id.rowTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (t, u, timeMs) = rows[position]
            holder.title.text = t.ifEmpty { u }
            holder.sub.text = u
            holder.time.text = fmt.format(Date(timeMs))

            val domain = runCatching { URI(u).host ?: u }.getOrElse { u }
            val letter = domain.removePrefix("www.").firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            holder.avatar.text = letter
            val hue = (domain.hashCode() and 0x7FFFFFFF) % 360
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.75f)))
            holder.avatar.background = gd

            holder.itemView.setOnClickListener { onClick(u) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setMessage("이 기록을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        HistoryRepo.remove(holder.itemView.context, u)
                        rows = rows.filterNot { it.second == u }
                        notifyDataSetChanged()
                    }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }
        }
    }
}
