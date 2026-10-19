-- A public ID for every account, like NC-7K4F-92QX — decided 2026-10-19.
--
-- The numeric id was shown on screen (#0001) and used in addresses
-- (/users/2). That told anybody how many accounts exist, and invited trying the
-- next number. The public ID is random, so it says nothing and cannot be
-- guessed. The numeric id stays as the primary key; it just never leaves the
-- database any more.
--
-- The alphabet leaves out 0/O and 1/I/L so a code can be read out loud or
-- copied by hand without mistakes. 31 symbols, 8 of them: about 850 billion
-- codes. New ones are made by the application (User.onCreate) with a secure
-- random generator; these, for accounts that already exist, by the database.

ALTER TABLE users ADD COLUMN public_id VARCHAR(20);

DO $$
DECLARE
    alphabet CONSTANT TEXT := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    account  RECORD;
    code     TEXT;
BEGIN
    FOR account IN SELECT id FROM users WHERE public_id IS NULL LOOP
        LOOP
            code := 'NC-';
            FOR i IN 1..8 LOOP
                code := code || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
                IF i = 4 THEN
                    code := code || '-';
                END IF;
            END LOOP;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM users WHERE public_id = code);
        END LOOP;
        UPDATE users SET public_id = code WHERE id = account.id;
    END LOOP;
END
$$;

ALTER TABLE users ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_public_id UNIQUE (public_id);
