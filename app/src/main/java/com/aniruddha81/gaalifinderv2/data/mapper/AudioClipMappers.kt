package com.aniruddha81.gaalifinderv2.data.mapper

import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin

/**
 * Translates between the storage row and the domain model.
 *
 * The `source` column predates the typed [ClipOrigin] and is the only place that convention is
 * understood — see [AudioFileEntity] for why it is still a string.
 */
fun AudioFileEntity.toDomain(): AudioClip = AudioClip(
    id = id,
    fileName = fileName,
    filePath = path,
    origin = source.toClipOrigin(),
    isNew = isNew,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    addedAt = addedAt,
)

fun List<AudioFileEntity>.toDomain(): List<AudioClip> = map(AudioFileEntity::toDomain)

fun String.toClipOrigin(): ClipOrigin =
    if (isBlank() || equals(AudioFileEntity.LOCAL_SOURCE, ignoreCase = true)) {
        ClipOrigin.Local
    } else {
        ClipOrigin.Remote(this)
    }

fun ClipOrigin.toSourceColumn(): String = when (this) {
    ClipOrigin.Local -> AudioFileEntity.LOCAL_SOURCE
    is ClipOrigin.Remote -> remoteId
}
