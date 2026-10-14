package com.ultrawidecam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PaymentRecord(
    val payeeName: String,
    val payeeVpa: String,
    val amount: String,
    val timestamp: Long
)

object PaymentHistoryHelper {
    private const val PREFS_NAME = "once_pay_history"
    private const val KEY_HISTORY = "history_records"
    private const val MAX_RECORDS = 40

    fun addRecord(context: Context, name: String, vpa: String, amount: String) {
        if (vpa.isEmpty()) return
        val list = getRecords(context).toMutableList()
        list.add(0, PaymentRecord(name, vpa, amount, System.currentTimeMillis()))
        if (list.size > MAX_RECORDS) {
            list.removeAt(list.size - 1)
        }
        saveRecords(context, list)
    }

    fun getRecords(context: Context): List<PaymentRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<PaymentRecord>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PaymentRecord(
                        payeeName = obj.optString("name", "Merchant"),
                        payeeVpa = obj.optString("vpa", ""),
                        amount = obj.optString("amount", ""),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun saveRecords(context: Context, list: List<PaymentRecord>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("name", item.payeeName)
                put("vpa", item.payeeVpa)
                put("amount", item.amount)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, jsonArray.toString())
            .apply()
    }

    fun clearRecords(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY)
            .apply()
    }
}

// Fix: Preserve distinct transactions with unique timestamps
