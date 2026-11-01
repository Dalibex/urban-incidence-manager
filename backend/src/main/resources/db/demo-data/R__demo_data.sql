-- Demo dataset (#54). Loaded only by the demo profile (application-demo.yaml), never in production.
-- It is the curated story that docs/demo.md walks through, separate from the development dataset:
-- never activate both profiles at once, because each one empties the incident data of the other.
-- Flyway runs it again whenever this file changes (docs/development.md, "Demo Dataset").
--
-- Running it again gives the same rows without duplicates:
--   - users are inserted or updated by their fixed id and never deleted, because other records
--     refer to them;
--   - incident data is emptied and inserted again.
-- Categories and departments come from V7 and are referred to by their fixed ids.
--
-- Fixed ids: the first group says the table (e0 users, e1 incident, e2 status_change, e3 location,
-- e4 assignment). In the last group, the rows of one incident carry its number, for example
-- e2000000-0000-4000-8000-000000006002 is the second history entry of incident 6.
--
-- The users' password is the DEMO_PASSWORD environment variable, which Flyway passes as the
-- demo_password placeholder: no password or hash is written in this file. PostgreSQL hashes it with
-- bcrypt (pgcrypto), a format the application's PasswordEncoder reads.
-- Coordinates are WGS 84, longitude first (ST_MakePoint(longitude, latitude)).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

TRUNCATE incident, location, status_change, assignment, comment, attachment, urban_context,
    external_observation, incident_asset, notification CASCADE;

-- Dollar quoting keeps the password intact whatever characters it has.
INSERT INTO users (id, name, email, password_hash, role, enabled)
SELECT id, name, email, '{bcrypt}' || crypt($demo$${demo_password}$demo$, gen_salt('bf', 10)), role, true
FROM (VALUES
    ('e0000000-0000-4000-8000-000000000001'::uuid, 'Ana Fernández', 'demo.citizen@example.com', 'CITIZEN'),
    ('e0000000-0000-4000-8000-000000000002'::uuid, 'Raúl Ortega', 'demo.operator@example.com', 'MUNICIPAL_OPERATOR'),
    ('e0000000-0000-4000-8000-000000000003'::uuid, 'Sergio Molina', 'demo.technician@example.com', 'TECHNICIAN'),
    ('e0000000-0000-4000-8000-000000000004'::uuid, 'Isabel Romero', 'demo.admin@example.com', 'ADMINISTRATOR'),
    ('e0000000-0000-4000-8000-000000000005'::uuid, 'Diego Herrera', 'demo.analyst@example.com', 'ANALYST')
) AS demo_user (id, name, email, role)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    email = EXCLUDED.email,
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    enabled = EXCLUDED.enabled;

-- The citizen (e0…01) reported every incident, so their list on the phone tells the whole story.
-- Every status appears; every priority was set by the operator (e0…02).
INSERT INTO incident (id, reporter_id, priority_setter_id, category_id, title, description, status,
                      priority, priority_reason, priority_source, reported_at, updated_at, closed_at) VALUES
    -- 1: the newest report, without category; its address is not resolved yet.
    ('e1000000-0000-4000-8000-000000000001', 'e0000000-0000-4000-8000-000000000001', NULL, NULL,
     'Street light flickering on Calle Larios', 'The street light in front of the bookshop switches on and off all night.',
     'REPORTED', NULL, NULL, NULL, '2026-11-01T19:30:00Z', '2026-11-01T19:30:00Z', NULL),
    -- 2: the operator asked for more information.
    ('e1000000-0000-4000-8000-000000000002', 'e0000000-0000-4000-8000-000000000001', NULL,
     '265293f4-456f-4a2d-b637-1c3a263815e4',
     'Loose paving stones in Plaza de la Merced', 'Several paving stones move when you step on them.',
     'REPORTED', NULL, NULL, NULL, '2026-10-31T10:00:00Z', '2026-10-31T12:15:00Z', NULL),
    -- 3: just reported, with category and address.
    ('e1000000-0000-4000-8000-000000000003', 'e0000000-0000-4000-8000-000000000001', NULL,
     'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee',
     'Overflowing recycling containers in El Palo', 'The paper and plastic containers have been full since Thursday.',
     'REPORTED', NULL, NULL, NULL, '2026-11-01T08:45:00Z', '2026-11-01T08:45:00Z', NULL),
    -- 4: validated, waiting for an assignment.
    ('e1000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', '265293f4-456f-4a2d-b637-1c3a263815e4',
     'Pothole on Avenida de Andalucía', 'A deep pothole in the right lane, next to the bus stop.',
     'VALIDATED', 'HIGH', 'Main road with heavy traffic and buses', 'MANUAL',
     '2026-10-30T07:40:00Z', '2026-10-30T09:00:00Z', NULL),
    -- 5: validated, waiting for an assignment.
    ('e1000000-0000-4000-8000-000000000005', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', '16fff6f7-e8ab-4841-ae3e-9dff80314c01',
     'Broken bench in Paseo del Parque', 'One of the wooden slats of the bench is broken.',
     'VALIDATED', 'LOW', 'Cosmetic damage; the bench can still be used', 'MANUAL',
     '2026-10-30T16:20:00Z', '2026-10-31T09:10:00Z', NULL),
    -- 6: rejected, because another service handles it.
    ('e1000000-0000-4000-8000-000000000006', 'e0000000-0000-4000-8000-000000000001', NULL,
     '474f62e6-521a-4958-ba99-e0d46b420233',
     'Loud music from a bar terrace', 'A bar on Calle Comedias plays loud music after midnight.',
     'REJECTED', NULL, NULL, NULL, '2026-10-29T23:10:00Z', '2026-10-30T08:30:00Z', NULL),
    -- 7: rejected as a duplicate.
    ('e1000000-0000-4000-8000-000000000007', 'e0000000-0000-4000-8000-000000000001', NULL,
     '3a73f376-34c4-4112-b85a-89f104c0f1f4',
     'Fallen sign on the seafront promenade', 'A no-parking sign is lying on the promenade.',
     'REJECTED', NULL, NULL, NULL, '2026-10-29T09:00:00Z', '2026-10-29T11:00:00Z', NULL),
    -- 8: assigned to the technician.
    ('e1000000-0000-4000-8000-000000000008', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', 'edaddd74-d0d9-4185-adc8-e278c468eb29',
     'Broken sprinkler in Jardines de Pedro Luis Alonso', 'A sprinkler has been spraying water onto the path since this morning.',
     'ASSIGNED', 'MEDIUM', 'Water is being wasted', 'MANUAL',
     '2026-10-28T08:00:00Z', '2026-10-28T10:00:00Z', NULL),
    -- 9: assigned to a department, without a technician yet.
    ('e1000000-0000-4000-8000-000000000009', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', '474f62e6-521a-4958-ba99-e0d46b420233',
     'Graffiti on a shop shutter in Calle Carretería', 'The shutter of a closed shop is covered in graffiti.',
     'ASSIGNED', 'LOW', 'Cosmetic damage only', 'MANUAL',
     '2026-10-28T15:00:00Z', '2026-10-29T08:30:00Z', NULL),
    -- 10: accepted by the technician.
    ('e1000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', '3a73f376-34c4-4112-b85a-89f104c0f1f4',
     'Traffic light out on Alameda Principal', 'The pedestrian traffic light at the crossing does not turn on.',
     'IN_PROGRESS', 'HIGH', 'Busy crossing without a traffic light', 'MANUAL',
     '2026-10-27T07:30:00Z', '2026-10-27T08:40:00Z', NULL),
    -- 11: accepted by the technician.
    ('e1000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', 'edaddd74-d0d9-4185-adc8-e278c468eb29',
     'Tree roots lifting the pavement in Calle Victoria', 'The roots of a ficus have lifted the paving and people trip over it.',
     'IN_PROGRESS', 'MEDIUM', 'Trip hazard on a narrow pavement', 'MANUAL',
     '2026-10-26T10:00:00Z', '2026-10-27T09:00:00Z', NULL),
    -- 12: resolved, waiting for the operator to close it.
    ('e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', 'edaddd74-d0d9-4185-adc8-e278c468eb29',
     'Broken swing in Parque del Oeste', 'One of the swing chains in the play area is broken.',
     'RESOLVED', 'MEDIUM', 'Risk for children', 'MANUAL',
     '2026-10-24T17:00:00Z', '2026-10-25T13:30:00Z', NULL),
    -- 13: resolved, waiting for the operator to close it.
    ('e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', 'fd4a9835-80be-4b73-bc9f-a042bed62b61',
     'Street lights off on Calle Héroe de Sostoa', 'Every street light of the block is off at night.',
     'RESOLVED', 'HIGH', 'Dark street at night', 'MANUAL',
     '2026-10-23T21:00:00Z', '2026-10-24T12:40:00Z', NULL),
    -- 14: resolved and reopened.
    ('e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', '16fff6f7-e8ab-4841-ae3e-9dff80314c01',
     'Leaking fountain in Plaza de la Constitución', 'Water is leaking from the base of the fountain.',
     'REOPENED', 'MEDIUM', 'Water is being wasted in a busy square', 'MANUAL',
     '2026-10-20T11:00:00Z', '2026-10-23T09:15:00Z', NULL),
    -- 15: closed by the operator.
    ('e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000001',
     'e0000000-0000-4000-8000-000000000002', 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee',
     'Old furniture left next to the containers in Teatinos', 'A sofa and two chairs were left next to the containers.',
     'CLOSED', 'LOW', 'It does not block the pavement', 'MANUAL',
     '2026-10-18T09:30:00Z', '2026-10-19T09:00:00Z', '2026-10-19T09:00:00Z');

INSERT INTO location (id, incident_id, coordinates, accuracy, address, neighborhood, district) VALUES
    ('e3000000-0000-4000-8000-000000000001', 'e1000000-0000-4000-8000-000000000001',
     ST_SetSRID(ST_MakePoint(-4.42163, 36.71960), 4326)::geography, 25.0, NULL, NULL, NULL),
    ('e3000000-0000-4000-8000-000000000002', 'e1000000-0000-4000-8000-000000000002',
     ST_SetSRID(ST_MakePoint(-4.41660, 36.72370), 4326)::geography, 9.0, 'Plaza de la Merced', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000003', 'e1000000-0000-4000-8000-000000000003',
     ST_SetSRID(ST_MakePoint(-4.36440, 36.72110), 4326)::geography, 6.0, 'Calle Bolivia, 120', 'El Palo', 'Este'),
    ('e3000000-0000-4000-8000-000000000004', 'e1000000-0000-4000-8000-000000000004',
     ST_SetSRID(ST_MakePoint(-4.43520, 36.71690), 4326)::geography, 5.0, 'Avenida de Andalucía, 12', 'La Aurora', 'Cruz de Humilladero'),
    ('e3000000-0000-4000-8000-000000000005', 'e1000000-0000-4000-8000-000000000005',
     ST_SetSRID(ST_MakePoint(-4.41620, 36.71870), 4326)::geography, 12.0, 'Paseo del Parque, 3', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000006', 'e1000000-0000-4000-8000-000000000006',
     ST_SetSRID(ST_MakePoint(-4.42110, 36.72180), 4326)::geography, 15.0, 'Calle Comedias, 9', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000007', 'e1000000-0000-4000-8000-000000000007',
     ST_SetSRID(ST_MakePoint(-4.39950, 36.71950), 4326)::geography, 8.0, 'Paseo Marítimo Pablo Ruiz Picasso, 40', 'La Malagueta', 'Este'),
    ('e3000000-0000-4000-8000-000000000008', 'e1000000-0000-4000-8000-000000000008',
     ST_SetSRID(ST_MakePoint(-4.41470, 36.71980), 4326)::geography, 10.0, 'Jardines de Pedro Luis Alonso', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000009', 'e1000000-0000-4000-8000-000000000009',
     ST_SetSRID(ST_MakePoint(-4.42430, 36.72420), 4326)::geography, 7.0, 'Calle Carretería, 50', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000010', 'e1000000-0000-4000-8000-000000000010',
     ST_SetSRID(ST_MakePoint(-4.42340, 36.71770), 4326)::geography, 4.0, 'Alameda Principal, 21', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000011', 'e1000000-0000-4000-8000-000000000011',
     ST_SetSRID(ST_MakePoint(-4.41530, 36.72550), 4326)::geography, 6.0, 'Calle Victoria, 60', 'Cristo de la Epidemia', 'Centro'),
    ('e3000000-0000-4000-8000-000000000012', 'e1000000-0000-4000-8000-000000000012',
     ST_SetSRID(ST_MakePoint(-4.44830, 36.69700), 4326)::geography, 11.0, 'Parque del Oeste', 'Parque del Oeste', 'Carretera de Cádiz'),
    ('e3000000-0000-4000-8000-000000000013', 'e1000000-0000-4000-8000-000000000013',
     ST_SetSRID(ST_MakePoint(-4.44000, 36.70640), 4326)::geography, 5.0, 'Calle Héroe de Sostoa, 95', 'Princesa', 'Carretera de Cádiz'),
    ('e3000000-0000-4000-8000-000000000014', 'e1000000-0000-4000-8000-000000000014',
     ST_SetSRID(ST_MakePoint(-4.42080, 36.72100), 4326)::geography, 8.0, 'Plaza de la Constitución', 'Centro Histórico', 'Centro'),
    ('e3000000-0000-4000-8000-000000000015', 'e1000000-0000-4000-8000-000000000015',
     ST_SetSRID(ST_MakePoint(-4.47780, 36.71720), 4326)::geography, 6.0, 'Calle Navarro Ledesma, 200', 'Teatinos', 'Teatinos-Universidad');

-- Only transitions of the ADR-0020 table (IncidentTransition), made by who may make them. The
-- associated data has the format Incident.changeStatus writes.
INSERT INTO status_change (id, incident_id, actor_id, instant, previous_status, new_status, reason,
                           associated_data, visible_to_citizen) VALUES
    ('e2000000-0000-4000-8000-000000001001', 'e1000000-0000-4000-8000-000000000001', 'e0000000-0000-4000-8000-000000000001',
     '2026-11-01T19:30:00Z', NULL, 'REPORTED', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000002001', 'e1000000-0000-4000-8000-000000000002', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-31T10:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000002002', 'e1000000-0000-4000-8000-000000000002', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-31T12:15:00Z', 'REPORTED', 'REPORTED',
     'Which part of the square is it? Please add a photo so the crew can find the stones.',
     '{"action": "INFORMATION_REQUESTED"}', true),

    ('e2000000-0000-4000-8000-000000003001', 'e1000000-0000-4000-8000-000000000003', 'e0000000-0000-4000-8000-000000000001',
     '2026-11-01T08:45:00Z', NULL, 'REPORTED', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000004001', 'e1000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-30T07:40:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000004002', 'e1000000-0000-4000-8000-000000000004', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-30T09:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000005001', 'e1000000-0000-4000-8000-000000000005', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-30T16:20:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000005002', 'e1000000-0000-4000-8000-000000000005', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-31T09:10:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000006001', 'e1000000-0000-4000-8000-000000000006', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-29T23:10:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000006002', 'e1000000-0000-4000-8000-000000000006', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-30T08:30:00Z', 'REPORTED', 'REJECTED',
     'Noise from bars is handled by the Municipal Police (092). Please contact them directly.', NULL, true),

    ('e2000000-0000-4000-8000-000000007001', 'e1000000-0000-4000-8000-000000000007', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-29T09:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000007002', 'e1000000-0000-4000-8000-000000000007', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-29T11:00:00Z', 'REPORTED', 'REJECTED',
     'Duplicate of a report about the same sign that is already being handled.', NULL, true),

    ('e2000000-0000-4000-8000-000000008001', 'e1000000-0000-4000-8000-000000000008', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-28T08:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000008002', 'e1000000-0000-4000-8000-000000000008', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-28T09:30:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000008003', 'e1000000-0000-4000-8000-000000000008', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-28T10:00:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "45e862fd-d303-4128-961d-a135e3831aa4", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),

    ('e2000000-0000-4000-8000-000000009001', 'e1000000-0000-4000-8000-000000000009', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-28T15:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000009002', 'e1000000-0000-4000-8000-000000000009', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-29T08:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000009003', 'e1000000-0000-4000-8000-000000000009', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-29T08:30:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "58998fe5-1698-4410-b0d0-780ae78361a6"}', true),

    ('e2000000-0000-4000-8000-000000010001', 'e1000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-27T07:30:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000010002', 'e1000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-27T07:50:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000010003', 'e1000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-27T08:00:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "d4f68abd-56f1-49c2-9de4-16af258b1bb9", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000010004', 'e1000000-0000-4000-8000-000000000010', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-27T08:40:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000011001', 'e1000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-26T10:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000011002', 'e1000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-26T12:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000011003', 'e1000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-26T12:30:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "45e862fd-d303-4128-961d-a135e3831aa4", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000011004', 'e1000000-0000-4000-8000-000000000011', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-27T09:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),

    ('e2000000-0000-4000-8000-000000012001', 'e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-24T17:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000012002', 'e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-25T09:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000012003', 'e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-25T09:15:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "45e862fd-d303-4128-961d-a135e3831aa4", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000012004', 'e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-25T11:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000012005', 'e1000000-0000-4000-8000-000000000012', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-25T13:30:00Z', 'IN_PROGRESS', 'RESOLVED', 'Chain replaced and the swing checked for safety.', NULL, true),

    ('e2000000-0000-4000-8000-000000013001', 'e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-23T21:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000013002', 'e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-24T08:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000013003', 'e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-24T08:10:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "502a9777-6043-40b4-afd6-2b57cc0f055a", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000013004', 'e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-24T10:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000013005', 'e1000000-0000-4000-8000-000000000013', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-24T12:40:00Z', 'IN_PROGRESS', 'RESOLVED', 'Faulty fuse in the street cabinet replaced; every light works.', NULL, true),

    ('e2000000-0000-4000-8000-000000014001', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-20T11:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000014002', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-20T12:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000014003', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-20T12:20:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "0d339d5b-7a6f-4004-a51a-943eb7d79ee3", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000014004', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-21T08:30:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000014005', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-21T12:00:00Z', 'IN_PROGRESS', 'RESOLVED', 'Valve seal replaced.', NULL, true),
    ('e2000000-0000-4000-8000-000000014006', 'e1000000-0000-4000-8000-000000000014', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-23T09:15:00Z', 'RESOLVED', 'REOPENED', 'The citizen reports that the fountain is leaking again.', NULL, true),

    ('e2000000-0000-4000-8000-000000015001', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000001',
     '2026-10-18T09:30:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000015002', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-18T10:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000015003', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-18T10:15:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "58998fe5-1698-4410-b0d0-780ae78361a6", "technicianId": "e0000000-0000-4000-8000-000000000003"}', true),
    ('e2000000-0000-4000-8000-000000015004', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-18T12:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('e2000000-0000-4000-8000-000000015005', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000003',
     '2026-10-18T15:30:00Z', 'IN_PROGRESS', 'RESOLVED', 'Furniture collected and taken to the recycling point.', NULL, true),
    ('e2000000-0000-4000-8000-000000015006', 'e1000000-0000-4000-8000-000000000015', 'e0000000-0000-4000-8000-000000000002',
     '2026-10-19T09:00:00Z', 'RESOLVED', 'CLOSED', NULL, NULL, true);

-- One row per ASSIGNMENT entry above.
INSERT INTO assignment (id, incident_id, department_id, technician_id, team, assigned_at, is_active) VALUES
    ('e4000000-0000-4000-8000-000000008001', 'e1000000-0000-4000-8000-000000000008',
     '45e862fd-d303-4128-961d-a135e3831aa4', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-28T10:00:00Z', true),
    ('e4000000-0000-4000-8000-000000009001', 'e1000000-0000-4000-8000-000000000009',
     '58998fe5-1698-4410-b0d0-780ae78361a6', NULL, 'Graffiti removal crew', '2026-10-29T08:30:00Z', true),
    ('e4000000-0000-4000-8000-000000010001', 'e1000000-0000-4000-8000-000000000010',
     'd4f68abd-56f1-49c2-9de4-16af258b1bb9', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-27T08:00:00Z', true),
    ('e4000000-0000-4000-8000-000000011001', 'e1000000-0000-4000-8000-000000000011',
     '45e862fd-d303-4128-961d-a135e3831aa4', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-26T12:30:00Z', true),
    ('e4000000-0000-4000-8000-000000012001', 'e1000000-0000-4000-8000-000000000012',
     '45e862fd-d303-4128-961d-a135e3831aa4', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-25T09:15:00Z', true),
    ('e4000000-0000-4000-8000-000000013001', 'e1000000-0000-4000-8000-000000000013',
     '502a9777-6043-40b4-afd6-2b57cc0f055a', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-24T08:10:00Z', true),
    ('e4000000-0000-4000-8000-000000014001', 'e1000000-0000-4000-8000-000000000014',
     '0d339d5b-7a6f-4004-a51a-943eb7d79ee3', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-20T12:20:00Z', true),
    ('e4000000-0000-4000-8000-000000015001', 'e1000000-0000-4000-8000-000000000015',
     '58998fe5-1698-4410-b0d0-780ae78361a6', 'e0000000-0000-4000-8000-000000000003', NULL, '2026-10-18T10:15:00Z', true);
