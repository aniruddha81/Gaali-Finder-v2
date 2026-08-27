import { Client, Databases, Storage, Query } from 'node-appwrite';

/**
 * Server-side enforcement of the upload limits.
 *
 * The client checks both limits before uploading, but a client check is only a courtesy — anyone
 * can call the API directly with a session token. This Function is the actual enforcement: it
 * re-validates the per-file size and the tier-dependent total independently, and undoes any
 * upload that breaches them.
 *
 * Trigger: `databases.*.collections.<audio_metadata>.documents.*.create`
 *
 * Because Appwrite events fire *after* the document is written, a violation is handled by
 * deleting both the offending document and its Storage file, rather than by refusing the write.
 * The client surfaces this as a rejected upload when the document does not come back.
 */

// Must match StorageQuota in the Android app. If you change one, change the other.
const MAX_FILE_BYTES = 204800; // 200 KB
const FREE_TOTAL_BYTES = 10485760; // 10 MB
const DEFAULT_PREMIUM_TOTAL_BYTES = 104857600; // 100 MB

const PAGE_SIZE = 100;

export default async ({ req, res, log, error }) => {
  const {
    APPWRITE_FUNCTION_API_ENDPOINT,
    APPWRITE_FUNCTION_PROJECT_ID,
    APPWRITE_API_KEY,
    APPWRITE_DATABASE_ID,
    APPWRITE_AUDIO_METADATA_COLLECTION_ID = 'audio_metadata',
    APPWRITE_USER_PROFILES_COLLECTION_ID = 'user_profiles',
    APPWRITE_BUCKET_ID,
  } = process.env;

  const client = new Client()
    .setEndpoint(APPWRITE_FUNCTION_API_ENDPOINT)
    .setProject(APPWRITE_FUNCTION_PROJECT_ID)
    .setKey(APPWRITE_API_KEY);

  const databases = new Databases(client);
  const storage = new Storage(client);

  // The triggering document arrives as the request body.
  let doc;
  try {
    doc = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
  } catch (e) {
    error(`Could not parse the event payload: ${e.message}`);
    return res.json({ ok: false, reason: 'bad-payload' }, 400);
  }

  if (!doc || !doc.$id) {
    return res.json({ ok: false, reason: 'not-a-document' }, 400);
  }

  const documentId = doc.$id;
  const fileId = doc.fileId;
  const uploaderId = doc.uploaderId;
  const fileSizeBytes = Number(doc.fileSizeBytes ?? 0);

  /** Undoes an upload that failed validation, so it cannot occupy the user's quota. */
  const reject = async (reason) => {
    log(`Rejecting ${documentId}: ${reason}`);
    try {
      await databases.deleteDocument(
        APPWRITE_DATABASE_ID,
        APPWRITE_AUDIO_METADATA_COLLECTION_ID,
        documentId,
      );
    } catch (e) {
      error(`Could not delete the rejected document ${documentId}: ${e.message}`);
    }
    if (fileId) {
      try {
        await storage.deleteFile(APPWRITE_BUCKET_ID, fileId);
      } catch (e) {
        error(`Could not delete the rejected file ${fileId}: ${e.message}`);
      }
    }
    return res.json({ ok: false, reason });
  };

  // A document with no uploader cannot be attributed to a quota, so it is never valid.
  if (!uploaderId) return reject('missing-uploader');

  // 1. Per-file cap. Applies to every user regardless of tier.
  if (!Number.isFinite(fileSizeBytes) || fileSizeBytes <= 0) {
    return reject('invalid-size');
  }
  if (fileSizeBytes > MAX_FILE_BYTES) {
    return reject(`file-too-large:${fileSizeBytes}`);
  }

  // The recorded size is what the quota is computed from, so a client that under-reports it
  // would otherwise get free storage. Check it against the actual stored file.
  try {
    const file = await storage.getFile(APPWRITE_BUCKET_ID, fileId);
    if (file.sizeOriginal > MAX_FILE_BYTES) {
      return reject(`stored-file-too-large:${file.sizeOriginal}`);
    }
    if (file.sizeOriginal !== fileSizeBytes) {
      return reject(`size-mismatch:${fileSizeBytes}!=${file.sizeOriginal}`);
    }
  } catch (e) {
    return reject(`missing-file:${e.message}`);
  }

  // 2. Tier lookup. No profile document means free tier — premium is never assumed.
  let limitBytes = FREE_TOTAL_BYTES;
  try {
    const profiles = await databases.listDocuments(
      APPWRITE_DATABASE_ID,
      APPWRITE_USER_PROFILES_COLLECTION_ID,
      [Query.equal('userId', uploaderId), Query.limit(1)],
    );
    const profile = profiles.documents[0];
    if (profile?.isPremium === true) {
      const configured = Number(profile.premiumStorageLimitBytes ?? 0);
      limitBytes = configured > 0 ? configured : DEFAULT_PREMIUM_TOTAL_BYTES;
    }
  } catch (e) {
    // Falling back to the free limit on a failed lookup is the safe direction: it can only
    // ever be stricter than the user's real entitlement.
    error(`Profile lookup failed for ${uploaderId}, assuming free tier: ${e.message}`);
  }

  // 3. Total across every clip this user has, including the one that just landed.
  let total = 0;
  let offset = 0;
  try {
    for (;;) {
      const page = await databases.listDocuments(
        APPWRITE_DATABASE_ID,
        APPWRITE_AUDIO_METADATA_COLLECTION_ID,
        [
          Query.equal('uploaderId', uploaderId),
          Query.select(['fileSizeBytes']),
          Query.limit(PAGE_SIZE),
          Query.offset(offset),
        ],
      );
      total += page.documents.reduce((sum, d) => sum + Number(d.fileSizeBytes ?? 0), 0);
      offset += PAGE_SIZE;
      if (page.documents.length < PAGE_SIZE) break;
    }
  } catch (e) {
    error(`Quota sum failed for ${uploaderId}: ${e.message}`);
    return res.json({ ok: false, reason: 'quota-check-failed' }, 500);
  }

  if (total > limitBytes) {
    return reject(`quota-exceeded:${total}>${limitBytes}`);
  }

  log(`Accepted ${documentId}: ${fileSizeBytes} bytes, ${total}/${limitBytes} total.`);
  return res.json({ ok: true, usedBytes: total, limitBytes });
};
