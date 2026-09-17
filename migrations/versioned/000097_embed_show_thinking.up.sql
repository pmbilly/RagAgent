-- Migration 000097: embed channel show-thinking toggle.
--
-- Controls whether the embed widget renders the model's thinking process to
-- visitors. false (default) shows only a blinking-dots indicator while
-- thinking is in progress; true renders the collapsible thinking card.
ALTER TABLE embed_channels ADD COLUMN IF NOT EXISTS show_thinking BOOLEAN NOT NULL DEFAULT false;
