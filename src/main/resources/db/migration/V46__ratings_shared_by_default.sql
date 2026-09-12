-- Ratings travel with the shelf by default, for everyone.
--
-- V45 introduced the switch a day earlier and defaulted it to off, on the reasoning that a
-- rating is an opinion rather than a description of the record. In use that turned out to be
-- the wrong default: whoever is already allowed to see the collection is exactly who the
-- stars are for, and an opinion nobody can read is not much of a shelf. The switch stays --
-- anyone who wants their stars to themselves can still turn it off.
--
-- The existing rows are flipped too, not just new ones. Nobody had ever chosen "off" here:
-- the column was one day old and every row in it carried V45's default rather than an
-- answer anyone gave.

ALTER TABLE sharing_settings
    ALTER COLUMN ratings_shared SET DEFAULT TRUE;

UPDATE sharing_settings
SET ratings_shared = TRUE
WHERE ratings_shared = FALSE;
