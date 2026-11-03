package com.vansaska.wattbike.app

import android.content.Context
import com.vansaska.wattbike.core.RideRecord
import com.vansaska.wattbike.core.Workout
import com.vansaska.wattbike.core.WorkoutFile
import com.vansaska.wattbike.core.WorkoutJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class LibraryEntry(
    val workout: Workout,
    /** True if it came from the workouts file in the GitHub repo. */
    val fromRepo: Boolean,
    /** True once you've edited it on the phone; sync then leaves it alone. */
    val edited: Boolean = false,
)

class WorkoutRepository(private val context: Context, private val http: OkHttpClient) {
    private val file = File(context.filesDir, "library.json")
    private val serializer = ListSerializer(LibraryEntry.serializer())

    fun load(): List<LibraryEntry> {
        if (file.exists()) {
            runCatching { return WorkoutJson.decodeFromString(serializer, file.readText()) }
        }
        // First run: start with the workouts bundled into the app.
        val bundled = context.assets.open("workouts.json").bufferedReader().use { it.readText() }
        val entries = WorkoutJson.decodeFromString(WorkoutFile.serializer(), bundled)
            .workouts.map { LibraryEntry(it, fromRepo = true) }
        save(entries)
        return entries
    }

    fun save(entries: List<LibraryEntry>) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(WorkoutJson.encodeToString(serializer, entries))
        tmp.renameTo(file)
    }

    data class SyncResult(val entries: List<LibraryEntry>, val message: String)

    /** Downloads the workouts file and merges it into the library. */
    suspend fun sync(url: String, current: List<LibraryEntry>): SyncResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
            resp.body!!.string()
        }
        val remote = WorkoutJson.decodeFromString(WorkoutFile.serializer(), body).workouts
        val remoteIds = remote.map { it.id }.toSet()
        val byId = current.associateBy { it.workout.id }
        var added = 0
        var updated = 0
        val keptEdited = mutableListOf<String>()
        val result = mutableListOf<LibraryEntry>()
        for (w in remote) {
            val existing = byId[w.id]
            when {
                existing == null -> { result += LibraryEntry(w, fromRepo = true); added++ }
                existing.edited -> { result += existing; keptEdited += existing.workout.name }
                else -> { if (existing.workout != w) updated++; result += LibraryEntry(w, fromRepo = true) }
            }
        }
        var removed = 0
        for (e in current) {
            if (e.workout.id in remoteIds) continue
            if (e.fromRepo && !e.edited) removed++ else result += e
        }
        save(result)
        val parts = mutableListOf<String>()
        if (added > 0) parts += "$added new"
        if (updated > 0) parts += "$updated updated"
        if (removed > 0) parts += "$removed removed"
        if (parts.isEmpty()) parts += "No changes"
        var msg = parts.joinToString(", ")
        if (keptEdited.isNotEmpty()) msg += ". Kept your edited version of: " + keptEdited.joinToString(", ")
        SyncResult(result, msg)
    }
}

class RideRepository(context: Context) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }

    fun save(r: RideRecord) {
        File(dir, "${r.id}.json").writeText(WorkoutJson.encodeToString(RideRecord.serializer(), r))
    }

    fun all(): List<RideRecord> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { WorkoutJson.decodeFromString(RideRecord.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAtMs }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
