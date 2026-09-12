-- Where a copy sits once a shelf has been arranged by hand.
--
-- The same field, with the same rules, that V21 gave a wishlist entry -- and for the same
-- reason. The order somebody drags their shelf into is data, not a device preference:
-- putting the records you actually reach for at the front is a statement about the
-- collection, and an order that only existed on the phone would be gone the moment they
-- opened the web app. So it is an ordinary mergeable field, stamped and reconciled like
-- every other one.
--
-- NULL means "never placed by hand", which is not position 0 -- a record filed since the
-- last arranging sorts after the placed ones rather than jumping to the front of an order
-- it was never part of.
--
-- No index. The shelf is ordered by this on the *clients*, out of their own local stores;
-- the server never sorts copies, it hands back whatever changed since a cursor.

ALTER TABLE copies ADD COLUMN sort_index INTEGER;
