-- The text search q of the incident lists, the map and the dashboard (#266): the title or the
-- description contains the text, ignoring case and accents.
--
-- unaccent() is only STABLE, because it reads its dictionary through the search_path, so an index
-- cannot use it. search_text names the schema of the dictionary and is IMMUTABLE, the usual way to
-- index it; the backend calls it on both sides of LIKE, so the column and the text fold the same.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION search_text(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN lower(public.unaccent('public.unaccent'::regdictionary, value));

-- Without them every search reads the whole table: about 115 ms with 100,000 incidents, twice for
-- a page of the list (its rows and its count). With one trigram index per column the count takes
-- about 30 ms, and a text no incident has under 0.1 ms (docs/datasets.md, "Text Search"). One index
-- per column, not one on both joined, so a match never runs from the title into the description.
CREATE INDEX ix_incident_title_search ON incident USING gin (search_text(title) gin_trgm_ops);
CREATE INDEX ix_incident_description_search
    ON incident USING gin (search_text(description) gin_trgm_ops);
