-- Each person's look for the app, from Settings → Theme — added 2026-11-11.
-- A small JSON object (mode, palette, radius, font, card frame). Null means the
-- standard look. Kept per account so it follows the person to any device.
ALTER TABLE users ADD COLUMN appearance VARCHAR(500);
