import { Client, Databases, Query } from 'node-appwrite';

/**
 * Keeps `likeCount` / `dislikeCount` on `audio_metadata` in step with `audio_reactions`.
 *
 * The counts are recalculated by counting the reaction documents, never by incrementing or
 * decrementing. That is the whole point: two users reacting at the same moment would both read
 * the same old value and write the same new one, losing a vote. Counting is idempotent, so
 * running this twice for the same event is harmless.
 *
 * Trigger, all three events:
 *   databases.*.collections.<audio_reactions>.documents.*.create
 *   databases.*.collections.<audio_reactions>.documents.*.update
 *   databases.*.collections.<audio_reactions>.documents.*.delete
 *
 * This Function is also the enforcement point for the unique (audioId, userId) pair: if a race
 * leaves more than one reaction document for a pair, the extras are pruned before counting.
 */

const PAGE_SIZE = 100;

export default async ({ req, res, log, error }) => {
  const {
    APPWRITE_FUNCTION_API_ENDPOINT,
    APPWRITE_FUNCTION_PROJECT_ID,
    APPWRITE_API_KEY,
    APPWRITE_DATABASE_ID,
    APPWRITE_AUDIO_METADATA_COLLECTION_ID = 'audio_metadata',
    APPWRITE_AUDIO_REACTIONS_COLLECTION_ID = 'audio_reactions',
  } = process.env;

  const client = new Client()
    .setEndpoint(APPWRITE_FUNCTION_API_ENDPOINT)
    .setProject(APPWRITE_FUNCTION_PROJECT_ID)
    .setKey(APPWRITE_API_KEY);

  const databases = new Databases(client);

  let doc;
  try {
    doc = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
  } catch (e) {
    error(`Could not parse the event payload: ${e.message}`);
    return res.json({ ok: false, reason: 'bad-payload' }, 400);
  }

  // On a delete event the payload is the document as it was, so audioId is still present.
  const audioId = doc?.audioId;
  if (!audioId) {
    return res.json({ ok: false, reason: 'no-audio-id' }, 400);
  }

  // Read every reaction for this clip.
  const reactions = [];
  let offset = 0;
  try {
    for (;;) {
      const page = await databases.listDocuments(
        APPWRITE_DATABASE_ID,
        APPWRITE_AUDIO_REACTIONS_COLLECTION_ID,
        [Query.equal('audioId', audioId), Query.limit(PAGE_SIZE), Query.offset(offset)],
      );
      reactions.push(...page.documents);
      offset += PAGE_SIZE;
      if (page.documents.length < PAGE_SIZE) break;
    }
  } catch (e) {
    error(`Could not list reactions for ${audioId}: ${e.message}`);
    return res.json({ ok: false, reason: 'list-failed' }, 500);
  }

  // Enforce one reaction per (audioId, userId). Newest wins, since that is the user's latest
  // intent; anything older is a leftover from a race and is removed.
  const newestByUser = new Map();
  const stale = [];

  for (const reaction of reactions) {
    const existing = newestByUser.get(reaction.userId);
    if (!existing) {
      newestByUser.set(reaction.userId, reaction);
      continue;
    }
    const older = reaction.$createdAt > existing.$createdAt ? existing : reaction;
    const newer = reaction.$createdAt > existing.$createdAt ? reaction : existing;
    newestByUser.set(reaction.userId, newer);
    stale.push(older);
  }

  for (const duplicate of stale) {
    try {
      await databases.deleteDocument(
        APPWRITE_DATABASE_ID,
        APPWRITE_AUDIO_REACTIONS_COLLECTION_ID,
        duplicate.$id,
      );
      log(`Pruned duplicate reaction ${duplicate.$id} for user ${duplicate.userId}.`);
    } catch (e) {
      error(`Could not prune duplicate ${duplicate.$id}: ${e.message}`);
    }
  }

  let likeCount = 0;
  let dislikeCount = 0;
  for (const reaction of newestByUser.values()) {
    if (reaction.type === 'like') likeCount += 1;
    else if (reaction.type === 'dislike') dislikeCount += 1;
  }

  try {
    await databases.updateDocument(
      APPWRITE_DATABASE_ID,
      APPWRITE_AUDIO_METADATA_COLLECTION_ID,
      audioId,
      { likeCount, dislikeCount },
    );
  } catch (e) {
    // A clip deleted while its reactions were being tallied is an expected race, not a fault.
    error(`Could not update counts on ${audioId}: ${e.message}`);
    return res.json({ ok: false, reason: 'update-failed' }, 500);
  }

  log(`${audioId}: ${likeCount} likes, ${dislikeCount} dislikes.`);
  return res.json({ ok: true, likeCount, dislikeCount });
};
