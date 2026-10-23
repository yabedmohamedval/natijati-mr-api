-- Official BEPC XML has up to 18 decimal places, including genuine precision
-- beyond 12. Preserve it without business rounding. C1AS/BAC remain (18,12).
ALTER TABLE bepc_result ALTER COLUMN score TYPE NUMERIC(24,18);

-- Numeric lookup/uniqueness remains unchanged. Preserve the exact source text
-- (including leading zeros); historical imports cannot be reconstructed safely.
ALTER TABLE bepc_result ADD COLUMN candidate_number_original VARCHAR(255);
ALTER TABLE c1as_result ADD COLUMN candidate_number_original VARCHAR(255);
ALTER TABLE c1as_result ADD COLUMN dossier_number_original VARCHAR(255);
