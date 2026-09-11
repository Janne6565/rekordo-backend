-- Reopens the cover question for every album that was probed at a pressing's address.
--
-- A copy whose owner never chose a pressing is mirrored under its album's own id, so the
-- row's mbid is a release group's. The palette sampler asked the Cover Art Archive for
-- `/release/<that mbid>`, which is a pressing that does not exist, and wrote the 404 down
-- as a definite "no cover". That is served as a null coverArtUrl, so a friend opening the
-- shelf saw a blank sleeve while the owner's phone, which keeps a cover it already has,
-- still drew the picture. Reported from the field: two of a sibling's nine records blank.
--
-- The sampler now asks the release-group address for these rows. This clears the answers
-- the wrong address gave, and only for album rows: a pressing's false came from its own
-- address and stands. Rows that really have no cover are probed once and marked again.

UPDATE releases r
SET has_cover_art = NULL,
    dominant_color = NULL,
    accent_color = NULL,
    lightness = NULL
FROM release_groups g
WHERE g.id = r.release_group_id
  AND g.external_id = r.external_id
  AND r.external_id LIKE 'musicbrainz:%'
  AND r.has_cover_art = FALSE;
