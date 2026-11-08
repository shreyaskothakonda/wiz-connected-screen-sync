package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.sync.BulbCommand
import org.json.JSONException
import org.json.JSONObject

/** JSON payloads for the WiZ local UDP protocol; mirrors app/bulb.py. */
object WizJson {
    fun setPilot(cmd: BulbCommand): String = JSONObject()
        .put("method", "setPilot")
        .put(
            "params", JSONObject()
                .put("state", true)
                .put("r", cmd.r)
                .put("g", cmd.g)
                .put("b", cmd.b)
                .put("dimming", cmd.dimming.coerceIn(10, 100)),
        )
        .toString()

    fun setState(on: Boolean): String = JSONObject()
        .put("method", "setState")
        .put("params", JSONObject().put("state", on))
        .toString()

    val getPilot: String = JSONObject().put("method", "getPilot").put("params", JSONObject()).toString()

    /** The bulb's MAC from a getPilot reply, or null when the reply is malformed or has no `result.mac`. */
    fun parseMac(reply: String): String? = try {
        JSONObject(reply).optJSONObject("result")?.optString("mac", "")?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }
}
