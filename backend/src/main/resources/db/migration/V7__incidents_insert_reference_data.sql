-- Initial reference data, so every environment starts with the same categories and departments
-- (#34, Twelve-Factor X and XII). Ids are fixed so that the demo dataset (#54) and the tests can
-- refer to them. Administrators manage these rows afterwards; a change in the initial list is a new
-- migration, never an edit of this one (ADR-0014). Demo users and incidents do not belong here.

-- The description is the help text the citizen sees in the report form (CategoryResponse).
INSERT INTO category (id, name, description) VALUES
    ('fd4a9835-80be-4b73-bc9f-a042bed62b61', 'Street lighting', 'Broken, flickering or always-on street lights.'),
    ('265293f4-456f-4a2d-b637-1c3a263815e4', 'Pavement', 'Potholes, cracks or loose paving stones on roads and pavements.'),
    ('cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee', 'Waste', 'Overflowing or damaged bins, rubbish dumped in the street.'),
    ('16fff6f7-e8ab-4841-ae3e-9dff80314c01', 'Urban furniture', 'Broken benches, fountains, bollards or bus shelters.'),
    ('edaddd74-d0d9-4185-adc8-e278c468eb29', 'Parks and green areas', 'Damaged trees, play areas or irrigation in parks and gardens.'),
    ('3a73f376-34c4-4112-b85a-89f104c0f1f4', 'Signage', 'Missing, damaged or faded traffic signs and road markings.'),
    ('474f62e6-521a-4958-ba99-e0d46b420233', 'Other', 'Anything that does not fit the other categories.');

INSERT INTO department (id, name) VALUES
    ('502a9777-6043-40b4-afd6-2b57cc0f055a', 'Public Lighting'),
    ('0d339d5b-7a6f-4004-a51a-943eb7d79ee3', 'Roads and Works'),
    ('58998fe5-1698-4410-b0d0-780ae78361a6', 'Cleaning and Waste'),
    ('45e862fd-d303-4128-961d-a135e3831aa4', 'Parks and Gardens'),
    ('d4f68abd-56f1-49c2-9de4-16af258b1bb9', 'Mobility');
