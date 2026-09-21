package com.uzairansar.hermex.data.repository

import android.content.Context
import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.encodeToString

/** Same private-preferences persistence mechanism as stream recovery; no Room migration.
 * Unlike a draft's apply(), commit() on application IO is required before network mutation.
 */
class PreferencesArchiveJournal(context: Context, preferencesName: String = "hermex_archive_journal") : ArchiveJournal {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    override fun read(): List<ArchiveRequest> = preferences.getString("records", null)
        ?.let { HermesJson.decodeFromString<List<ArchiveRequest>>(it) }.orEmpty()
    override fun write(records: List<ArchiveRequest>) {
        check(preferences.edit().putString("records", HermesJson.encodeToString(records)).commit()) {
            "Could not save archive request. Retry."
        }
    }
}
