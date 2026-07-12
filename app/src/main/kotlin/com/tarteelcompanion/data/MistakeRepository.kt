package com.tarteelcompanion.data

import com.tarteelcompanion.data.entity.ImportScreenshotEntity
import com.tarteelcompanion.data.entity.OccurrenceEntity
import com.tarteelcompanion.data.entity.SpotEntity
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.SpotState
import com.tarteelcompanion.quran.WordRef

/** One detected (or manually tagged) mistake handed over by the import flow. */
data class Detection(val ref: WordRef, val type: MistakeType)

sealed interface ImportResult {
    /** The exact same image was imported before — nothing recorded (flow-analysis I1). */
    data object DuplicateImage : ImportResult

    data class Saved(
        val importId: Long,
        /** Spots created for the first time by this import. */
        val newSpots: List<WordRef>,
        /** Existing spots that received a new occurrence (lapse signal for the scheduler). */
        val lapsedSpots: List<WordRef>,
        /** Graduated spots reactivated by this import (R20). */
        val reactivatedSpots: List<WordRef>,
    ) : ImportResult
}

/**
 * Owns the mistake-spot lifecycle semantics (plan U5): hash dedup, occurrence collapse
 * by (position, day), per-occurrence typing with latest-wins emphasis, and
 * lapse/reactivation signaling consumed by the U7 scheduler.
 */
class MistakeRepository(private val db: AppDatabase) {

    /**
     * Records one confirmed screenshot's detections. Same-position same-day detections
     * collapse into a single occurrence with an incremented hit count; a detection with
     * a different type on the same day overwrites the occurrence type (latest wins —
     * flow-analysis C5/M-series defaults, adopted in the plan).
     */
    suspend fun recordImport(
        contentHash: String,
        pageNumber: Int?,
        detections: List<Detection>,
        epochDay: Long,
        nowEpochMillis: Long,
        thumbnailPath: String? = null,
    ): ImportResult {
        if (db.importDao().byHash(contentHash) != null) return ImportResult.DuplicateImage

        val importId = db.importDao().insert(
            ImportScreenshotEntity(
                contentHash = contentHash,
                pageNumber = pageNumber,
                importedAtEpochMillis = nowEpochMillis,
                thumbnailPath = thumbnailPath,
            ),
        )

        val newSpots = mutableListOf<WordRef>()
        val lapsedSpots = mutableListOf<WordRef>()
        val reactivatedSpots = mutableListOf<WordRef>()

        for (detection in detections) {
            val spotId = encodeWordRef(detection.ref)
            val existing = db.spotDao().byId(spotId)

            when {
                existing == null -> {
                    db.spotDao().insertIgnore(
                        SpotEntity(
                            id = spotId,
                            surah = detection.ref.surah,
                            ayah = detection.ref.ayah,
                            word = detection.ref.word,
                            createdAtEpochDay = epochDay,
                        ),
                    )
                    newSpots += detection.ref
                }

                existing.state == SpotState.SUSPENDED -> {
                    // Occurrence still recorded below; suspension is a user decision
                    // and is not overridden by imports (plan U5).
                }

                existing.state == SpotState.GRADUATED -> {
                    db.spotDao().update(
                        existing.copy(
                            state = SpotState.ACTIVE,
                            pendingLapse = true,
                            graduatedAtEpochDay = null,
                            dueEpochDay = epochDay,
                        ),
                    )
                    reactivatedSpots += detection.ref
                }

                else -> { // ACTIVE: new real-world evidence → lapse signal (plan U7 invariant 4)
                    db.spotDao().update(existing.copy(pendingLapse = true, dueEpochDay = epochDay))
                    lapsedSpots += detection.ref
                }
            }

            val sameDay = db.occurrenceDao().forSpotOnDay(spotId, epochDay)
            if (sameDay == null) {
                db.occurrenceDao().insert(
                    OccurrenceEntity(
                        spotId = spotId,
                        epochDay = epochDay,
                        type = detection.type,
                        importId = importId,
                    ),
                )
            } else {
                db.occurrenceDao().update(
                    sameDay.copy(hitCount = sameDay.hitCount + 1, type = detection.type),
                )
            }
        }

        return ImportResult.Saved(importId, newSpots, lapsedSpots, reactivatedSpots)
    }

    /** Card emphasis follows the most recent occurrence's type (R14). */
    suspend fun effectiveType(ref: WordRef): MistakeType? =
        db.occurrenceDao().latestForSpot(encodeWordRef(ref))?.type

    suspend fun spot(ref: WordRef): SpotEntity? = db.spotDao().byId(encodeWordRef(ref))

    suspend fun suspend(ref: WordRef) = setState(ref, SpotState.SUSPENDED)

    suspend fun reactivate(ref: WordRef) = setState(ref, SpotState.ACTIVE)

    /** Permanent removal — the UI guards this behind confirmation/undo (plan U12). */
    suspend fun delete(ref: WordRef) {
        val id = encodeWordRef(ref)
        db.reviewLogDao().deleteForSpot(id)
        db.occurrenceDao().deleteForSpot(id)
        db.spotDao().delete(id)
    }

    private suspend fun setState(ref: WordRef, state: SpotState) {
        val spot = db.spotDao().byId(encodeWordRef(ref)) ?: return
        db.spotDao().update(spot.copy(state = state))
    }
}
