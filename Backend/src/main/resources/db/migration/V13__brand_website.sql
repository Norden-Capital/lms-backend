-- Brands from a website — decided 2026-10-20.
--
-- A brand is now added by its website alone: the app reads the brand's name
-- and logo from the site, and its address is support@<domain>. A brand can be
-- switched off without being deleted; an inactive brand is left out of the
-- inbox's brand filter and From list.
ALTER TABLE brands ADD COLUMN domain   VARCHAR(255);
ALTER TABLE brands ADD COLUMN logo_url VARCHAR(1000);
ALTER TABLE brands ADD COLUMN active   BOOLEAN NOT NULL DEFAULT TRUE;

-- Brands added before this were address-only: their domain is the address's.
UPDATE brands SET domain = lower(split_part(email, '@', 2)) WHERE domain IS NULL;

ALTER TABLE brands ALTER COLUMN domain SET NOT NULL;
CREATE UNIQUE INDEX uq_brands_domain ON brands (domain);
