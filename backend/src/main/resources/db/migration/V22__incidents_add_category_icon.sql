-- The icon of each category on the map and in the dashboard (#267). It names a meaning from a fixed
-- set, the CategoryIcon of the contract, and each client draws it with its own icon set; NULL means
-- the generic icon, the same as an incident without category. The check keeps out a value the
-- clients would not know how to draw.
ALTER TABLE category
    ADD COLUMN icon varchar,
    ADD CONSTRAINT ck_category_icon
        CHECK (icon IN ('STREET_LIGHTING', 'PAVEMENT', 'WASTE', 'URBAN_FURNITURE', 'PARKS', 'SIGNAGE'));

-- The initial categories of V7, by their fixed ids. "Other" keeps the generic icon.
UPDATE category SET icon = 'STREET_LIGHTING' WHERE id = 'fd4a9835-80be-4b73-bc9f-a042bed62b61';
UPDATE category SET icon = 'PAVEMENT'        WHERE id = '265293f4-456f-4a2d-b637-1c3a263815e4';
UPDATE category SET icon = 'WASTE'           WHERE id = 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee';
UPDATE category SET icon = 'URBAN_FURNITURE' WHERE id = '16fff6f7-e8ab-4841-ae3e-9dff80314c01';
UPDATE category SET icon = 'PARKS'           WHERE id = 'edaddd74-d0d9-4185-adc8-e278c468eb29';
UPDATE category SET icon = 'SIGNAGE'         WHERE id = '3a73f376-34c4-4112-b85a-89f104c0f1f4';
