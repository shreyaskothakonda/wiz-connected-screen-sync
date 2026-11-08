package com.shreyas.wizsync.tv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shreyas.wizsync.tv.R
import com.shreyas.wizsync.tv.databinding.ItemBulbBinding
import com.shreyas.wizsync.tv.store.Bulb

/**
 * Bulb tiles. Select toggles the tick; long-press or MENU opens the bulb menu.
 * Item animations are off so a tick toggle or rename rebinds in place and never drops D-pad focus.
 */
class BulbAdapter(
    private val onToggle: (Bulb) -> Unit,
    private val onMenu: (Bulb) -> Unit,
) : RecyclerView.Adapter<BulbAdapter.Holder>() {

    init {
        setHasStableIds(true)
    }

    private val items = mutableListOf<Bulb>()
    private var unhealthy: Set<String> = emptySet()

    fun submit(bulbs: List<Bulb>, unhealthyIps: Set<String>) {
        items.clear()
        items.addAll(bulbs)
        unhealthy = unhealthyIps.toSet()
        notifyDataSetChanged()
    }

    /** Refreshes only the warning badges, in place. */
    fun setUnhealthy(ips: Set<String>) {
        if (ips == unhealthy) return
        unhealthy = ips.toSet()
        notifyItemRangeChanged(0, items.size, PAYLOAD_BADGE)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].mac.lowercase().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBulbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(items[position]) else holder.bindBadge(items[position])
    }

    inner class Holder(private val b: ItemBulbBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnFocusChangeListener { v, focused ->
                val s = if (focused) FOCUS_SCALE else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_ANIM_MS).start()
            }
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val toggled = items[pos].copy(enabled = !items[pos].enabled)
                items[pos] = toggled
                bindTick(toggled)
                onToggle(toggled)
            }
            b.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMenu(items[pos])
                true
            }
            b.root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onMenu(items[pos])
                    true
                } else {
                    keyCode == KeyEvent.KEYCODE_MENU
                }
            }
        }

        fun bind(bulb: Bulb) {
            val s = if (b.root.hasFocus()) FOCUS_SCALE else 1f
            b.root.scaleX = s
            b.root.scaleY = s
            b.name.text = bulb.name
            b.ip.text = bulb.ip
            bindTick(bulb)
            bindBadge(bulb)
        }

        private fun bindTick(bulb: Bulb) {
            b.tick.setBackgroundResource(if (bulb.enabled) R.drawable.tick_on else R.drawable.tick_off)
            b.tickGlyph.visibility = if (bulb.enabled) View.VISIBLE else View.GONE
        }

        fun bindBadge(bulb: Bulb) {
            b.badge.visibility = if (bulb.ip in unhealthy) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
        const val FOCUS_ANIM_MS = 120L
        const val PAYLOAD_BADGE = "badge"
    }
}
