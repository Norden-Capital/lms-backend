-- Google Analytics per brand — decided 2026-10-20, the oneBase way.
--
-- The SEO Overview reads each brand's GA4 property live; this is the number
-- that says which one (digits only, the "123456789" in properties/123456789).
-- Null until it is set on Configuration → Brands.
ALTER TABLE brands ADD COLUMN ga4_property_id VARCHAR(15);
ALTER TABLE brands ADD CONSTRAINT ck_brands_ga4_property
    CHECK (ga4_property_id IS NULL OR ga4_property_id ~ '^[0-9]{6,15}$');
