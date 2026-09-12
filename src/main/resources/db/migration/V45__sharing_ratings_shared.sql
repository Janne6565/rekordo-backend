-- Sharing your ratings with the people who may see the shelf.
--
-- Off for everyone who already has a row, and off for every new one: a grade describes the
-- record, a rating describes what its owner thinks of it, so it is opted into rather than
-- out of. It rides on the collection's visibility the way prices do -- turning it on does
-- not open a shelf that is closed.

ALTER TABLE sharing_settings
    ADD COLUMN ratings_shared BOOLEAN NOT NULL DEFAULT FALSE;
