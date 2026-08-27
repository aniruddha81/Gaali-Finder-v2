package com.aniruddha81.gaalifinderv2.data.mapper

import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import com.aniruddha81.gaalifinderv2.data.remote.RemoteAudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType

/**
 * Translates between the cached row, the wire model and the domain model.
 *
 * The cache is the only layer that speaks all three, which keeps Appwrite's `$id`/attribute
 * conventions out of the ViewModel and the UI entirely.
 */
fun AudioFileEntity.toDomain(): AudioClip = AudioClip(
    id = documentId,
    fileId = fileId,
    fileName = fileName,
    uploaderId = uploaderId,
    uploaderName = uploaderName,
    isNew = isNew,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    likeCount = likeCount,
    dislikeCount = dislikeCount,
    myReaction = ReactionType.fromWire(myReaction),
    cachedPath = cachedPath,
)

fun List<AudioFileEntity>.toDomain(): List<AudioClip> = map(AudioFileEntity::toDomain)

/**
 * A freshly fetched catalogue entry, as a cache row.
 *
 * [isNew] is true here because anything arriving from a sync is new *until* the merge in
 * `replaceCatalogue` carries the previous badge state over for rows already known.
 */
fun RemoteAudioClip.toEntity(myReaction: ReactionType): AudioFileEntity = AudioFileEntity(
    documentId = documentId,
    fileId = fileId,
    fileName = fileName,
    uploaderId = uploaderId,
    uploaderName = uploaderName,
    isNew = true,
    durationMs = 0,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    likeCount = likeCount,
    dislikeCount = dislikeCount,
    myReaction = myReaction.wireValue,
    cachedPath = null,
)
