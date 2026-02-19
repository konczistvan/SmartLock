package com.example.smartlock.data.mapper

import com.example.smartlock.data.model.LockModel

object LockMapper {

    fun mapStatusToDisplayText(status: String): String {
        return when (status.uppercase()) {
            "LOCKED"   -> "🔒 Zárva"
            "UNLOCKED" -> "🔓 Nyitva"
            "OPENING"  -> "⏳ Nyitás folyamatban..."
            "CLOSING"  -> "⏳ Zárás folyamatban..."
            "ERROR"    -> "⚠️ Hiba"
            else       -> "❓ Ismeretlen állapot: $status"
        }
    }

    fun mapToDisplayPair(lock: LockModel): Pair<String, String> {
        val displayName = mapLockIdToName(lock.id)
        val displayStatus = mapStatusToDisplayText(lock.status)
        return Pair(displayName, displayStatus)
    }

    fun mapLockIdToName(lockId: String): String {
        return when (lockId) {
            "LOCK_0CDC7E614160" -> "Zár #1"
            "LOCK_0CDC7E5D076C" -> "Zár #2"
            "LOCK_B8F862E0BCBC" -> "Zár #3"
            else                -> lockId
        }
    }
}