-- funding accounts represent money entering the system, so they are meant to sit
-- negative. everything else has to stay solvent.
ALTER TABLE accounts ADD COLUMN allows_overdraft BOOLEAN NOT NULL DEFAULT FALSE;
