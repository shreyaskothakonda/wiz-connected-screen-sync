package com.shreyas.wizsync.tv.ui

import android.app.Activity
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.shreyas.wizsync.tv.App
import com.shreyas.wizsync.tv.R
import com.shreyas.wizsync.tv.store.Bulb
import com.shreyas.wizsync.tv.store.Settings
import com.shreyas.wizsync.tv.sync.SyncEngine
import com.shreyas.wizsync.tv.sync.WARM_WHITE

/** Rename / Identify / Remove actions for one bulb. [engine] is the live engine while syncing, else null. */
class BulbMenuDialog(
    private val activity: Activity,
    private val settings: Settings,
    private val engine: () -> SyncEngine?,
    private val onChanged: () -> Unit,
) {
    fun show(bulb: Bulb) {
        val labels = arrayOf(
            activity.getString(R.string.menu_rename),
            activity.getString(R.string.menu_identify),
            activity.getString(R.string.menu_remove),
        )
        AlertDialog.Builder(activity)
            .setTitle(bulb.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> rename(bulb)
                    1 -> identify(bulb)
                    else -> {
                        settings.remove(bulb.mac)
                        onChanged()
                    }
                }
            }
            .show()
    }

    private fun rename(bulb: Bulb) {
        val input = EditText(activity).apply {
            setText(bulb.name)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    settings.rename(bulb.mac, name)
                    onChanged()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    /** Blinks the bulb white off the main thread, then hands it back to sync (or warm white when idle). */
    private fun identify(bulb: Bulb) {
        val eng = engine()
        Thread({
            try {
                eng?.muted?.add(bulb.ip)
                App.wiz.identify(bulb.ip)
                if (eng != null) {
                    // Forget the last sent colour so the next tick resends the sync colour.
                    eng.forget(bulb.ip)
                } else {
                    App.wiz.send(bulb.ip, WARM_WHITE)
                }
            } catch (_: Exception) {
                // A bulb that does not answer simply does not blink.
            } finally {
                eng?.muted?.remove(bulb.ip)
            }
        }, "wizsync-identify").start()
    }
}
