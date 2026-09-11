-- Offers the album's cover to every pressing that was told it has none of its own.
--
-- Plenty of pressings in the Cover Art Archive carry no artwork while their album does.
-- Those rows were marked `has_cover_art = false`, which is served as a null coverArtUrl,
-- so the record drew a blank sleeve everywhere. Reported from the field: three Kate Bush
-- pressings blank on a friend's shelf, each of whose albums has a cover.
--
-- The sampler now falls back to the album when the pressing's own address says 404. This
-- points the rows already marked at their album's address and reopens the question, so
-- the cover shows at once and the next lookup confirms it. An album with no cover either
-- is probed once and marked false again. Album rows themselves are left alone (V43).

UPDATE releases r
SET cover_art_url = regexp_replace(
        r.cover_art_url,
        '/release/[^/]+/front-500$',
        '/release-group/' || substring(g.external_id FROM length('musicbrainz:') + 1) || '/front-500'),
    has_cover_art = NULL,
    dominant_color = NULL,
    accent_color = NULL,
    lightness = NULL
FROM release_groups g
WHERE g.id = r.release_group_id
  AND g.external_id <> r.external_id
  AND g.external_id LIKE 'musicbrainz:%'
  AND r.external_id LIKE 'musicbrainz:%'
  AND r.has_cover_art = FALSE
  AND r.cover_art_url ~ '/release/[^/]+/front-500$';
