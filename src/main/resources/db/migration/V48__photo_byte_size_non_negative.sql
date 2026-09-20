-- The size of a photo is what the storage allowance is counted from, so it may not be
-- negative.
--
-- Until now `byte_size` was written from whatever a sync push asserted for a row the server
-- had not seen before. `storageKey` and `contentType` were taken back from the client in
-- V38's wake; this one was not, and it is the field the quota arithmetic actually reads.
-- A push carrying a negative figure made `sum(byte_size)` read the whole account as owing
-- nothing, and `StorageUsageService.requireRoom` then let it upload without limit.
--
-- SyncService now answers for the field as it already answers for the other two. This is the
-- other half: any row already poisoned is normalised, and the constraint is what stops the
-- question being reopened by a future writer.

-- Nothing legitimate is in here -- the upload endpoint has only ever written a real file
-- length -- so this either touches no rows or it cleans up after an abuse.
UPDATE photos SET byte_size = 0 WHERE byte_size < 0;

ALTER TABLE photos
    ADD CONSTRAINT photos_byte_size_non_negative CHECK (byte_size >= 0);
