-- Migration 000096: embed channel launcher icon.
--
-- Optional custom launcher image for the website-embed widget, stored as a
-- base64 data URL (e.g. "data:image/png;base64,..."). Empty string means the
-- default 💬 launcher is rendered.
ALTER TABLE embed_channels ADD COLUMN IF NOT EXISTS launcher_icon TEXT NOT NULL DEFAULT '';
