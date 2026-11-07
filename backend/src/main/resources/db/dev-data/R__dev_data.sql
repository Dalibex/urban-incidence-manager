-- Development dataset (#83). Loaded only by the dev profile (application-dev.yaml), never in
-- production. Flyway runs it again whenever this file changes (docs/development.md).
--
-- Running it again gives the same rows without duplicates:
--   - users and urban assets are inserted or updated by their fixed id and never deleted, because
--     other records refer to them; a user that is no longer needed is disabled, not removed;
--   - incident data is emptied and inserted again.
-- Categories and departments come from V7 and are referred to by their fixed ids.
--
-- Fixed ids: the first group says the table (d0 users, d1 incident, d2 status_change, d3 location,
-- d4 assignment, d5 comment, d6 attachment, d7 urban_context, d8 external_observation,
-- d9 urban_asset, da incident_asset, db notification). In the last group, the rows of one incident
-- carry its number, for example d2000000-0000-4000-8000-000000006003 is the third history entry
-- of incident 6.
--
-- Every user's password is "urbanpulse-dev", hashed with the application's PasswordEncoder.
-- Coordinates are WGS 84, longitude first (ST_MakePoint(longitude, latitude)).

TRUNCATE incident, location, status_change, assignment, comment, attachment, urban_context,
    external_observation, incident_asset, notification CASCADE;

INSERT INTO users (id, name, email, password_hash, role, enabled) VALUES
    ('d0000000-0000-4000-8000-000000000001', 'Carmen Ruiz', 'dev.citizen@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'CITIZEN', true),
    ('d0000000-0000-4000-8000-000000000002', 'Javier Moreno', 'dev.citizen2@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'CITIZEN', true),
    ('d0000000-0000-4000-8000-000000000003', 'Lucía Torres', 'dev.operator@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'MUNICIPAL_OPERATOR', true),
    ('d0000000-0000-4000-8000-000000000004', 'Antonio Gómez', 'dev.technician@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'TECHNICIAN', true),
    ('d0000000-0000-4000-8000-000000000005', 'Pablo Navarro', 'dev.technician2@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'TECHNICIAN', true),
    ('d0000000-0000-4000-8000-000000000006', 'Elena Castro', 'dev.admin@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'ADMINISTRATOR', true),
    ('d0000000-0000-4000-8000-000000000007', 'Marta Vidal', 'dev.analyst@example.com',
     '{bcrypt}$2a$10$wYwdfaZhrN/Wu0rQWhFwYeJpwRhrLBLykizo9mJWLx6btJqIBUxMW', 'ANALYST', true)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    email = EXCLUDED.email,
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    enabled = EXCLUDED.enabled;

INSERT INTO urban_asset (id, external_id, type, source, geometry, metadata) VALUES
    ('d9000000-0000-4000-8000-000000000001', 'MLG-FOUNTAIN-0412', 'FOUNTAIN', 'malaga-open-data',
     ST_SetSRID(ST_MakePoint(-4.4484, 36.6972), 4326)::geography, '{"name": "Main fountain, Parque del Oeste"}'),
    ('d9000000-0000-4000-8000-000000000002', 'MLG-FOUNTAIN-0413', 'FOUNTAIN', 'malaga-open-data',
     ST_SetSRID(ST_MakePoint(-4.4479, 36.6965), 4326)::geography, '{"name": "Drinking fountain, Parque del Oeste"}'),
    ('d9000000-0000-4000-8000-000000000003', 'EMT-STOP-1287', 'BUS_STOP', 'malaga-open-data',
     ST_SetSRID(ST_MakePoint(-4.44003, 36.70642), 4326)::geography, '{"name": "Héroe de Sostoa (Princesa)", "lines": ["1", "15"]}'),
    ('d9000000-0000-4000-8000-000000000004', 'MLG-PARK-0087', 'GREEN_ZONE', 'malaga-open-data',
     ST_SetSRID(ST_MakePoint(-4.4613, 36.6892), 4326)::geography, '{"name": "Parque Litoral"}')
ON CONFLICT (id) DO UPDATE SET
    external_id = EXCLUDED.external_id,
    type = EXCLUDED.type,
    source = EXCLUDED.source,
    geometry = EXCLUDED.geometry,
    metadata = EXCLUDED.metadata;

-- One incident per status. Citizen 1 (d0…01) reported 1, 3, 5, 7, 9 and 10; citizen 2 (d0…02)
-- reported 2, 4, 6 and 8. Every priority was set by the operator (d0…03).
INSERT INTO incident (id, reporter_id, priority_setter_id, category_id, title, description, status,
                      priority, priority_reason, priority_source, reported_at, updated_at, closed_at) VALUES
    -- 1: just reported, without category; its address is not resolved yet.
    ('d1000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-000000000001', NULL, NULL,
     'Strange noise from a manhole cover', 'The cover rattles loudly every time a car drives over it.',
     'REPORTED', NULL, NULL, NULL, '2026-10-20T09:15:00Z', '2026-10-20T09:15:00Z', NULL),
    -- 2: reported, the operator asked for more information.
    ('d1000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000002', NULL,
     '16fff6f7-e8ab-4841-ae3e-9dff80314c01',
     'Broken bench next to a fountain', 'One of the benches near the fountain has a broken backrest.',
     'REPORTED', NULL, NULL, NULL, '2026-10-19T18:40:00Z', '2026-10-20T08:30:00Z', NULL),
    -- 3: validated, waiting for an assignment.
    ('d1000000-0000-4000-8000-000000000003', 'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000003', '265293f4-456f-4a2d-b637-1c3a263815e4',
     'Deep pothole in a pedestrian crossing', 'A deep pothole in the middle of the zebra crossing.',
     'VALIDATED', 'MEDIUM', 'Pedestrian crossing used by a school', 'MANUAL',
     '2026-10-17T08:05:00Z', '2026-10-17T11:20:00Z', NULL),
    -- 4: rejected as a duplicate.
    ('d1000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-000000000002', NULL,
     'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee',
     'Overflowing bins', 'The bins at the corner have been full for three days.',
     'REJECTED', NULL, NULL, NULL, '2026-10-16T12:30:00Z', '2026-10-16T15:00:00Z', NULL),
    -- 5: assigned to a department, without a technician yet.
    ('d1000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000003', 'fd4a9835-80be-4b73-bc9f-a042bed62b61',
     'Several street lights off in a park', 'The lights along the main path are all off at night.',
     'ASSIGNED', 'HIGH', 'Dark path used at night', 'MANUAL',
     '2026-10-14T07:50:00Z', '2026-10-14T09:45:00Z', NULL),
    -- 6: assigned to technician 1 and then reassigned to technician 2.
    ('d1000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000002',
     'd0000000-0000-4000-8000-000000000003', '16fff6f7-e8ab-4841-ae3e-9dff80314c01',
     'Graffiti on a bus shelter', 'The glass panels of the bus shelter are covered in graffiti.',
     'ASSIGNED', 'LOW', 'Cosmetic damage only', 'MANUAL',
     '2026-10-12T10:10:00Z', '2026-10-13T09:00:00Z', NULL),
    -- 7: accepted by technician 1.
    ('d1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000003', 'edaddd74-d0d9-4185-adc8-e278c468eb29',
     'Fallen tree branch blocking a path', 'A large branch fell after the wind and blocks the path.',
     'IN_PROGRESS', 'HIGH', 'It blocks the whole path', 'MANUAL',
     '2026-10-10T16:20:00Z', '2026-10-11T08:15:00Z', NULL),
    -- 8: resolved by technician 2, waiting for the operator to close it.
    ('d1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000002',
     'd0000000-0000-4000-8000-000000000003', '16fff6f7-e8ab-4841-ae3e-9dff80314c01',
     'Broken swing in a play area', 'One of the swing chains is broken.',
     'RESOLVED', 'MEDIUM', 'Risk for children', 'MANUAL',
     '2026-10-08T11:00:00Z', '2026-10-09T13:30:00Z', NULL),
    -- 9: resolved and reopened.
    ('d1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000003', '3a73f376-34c4-4112-b85a-89f104c0f1f4',
     'Traffic sign knocked down', 'A give-way sign is lying on the pavement.',
     'REOPENED', 'MEDIUM', 'Missing sign at a junction', 'MANUAL',
     '2026-09-30T09:00:00Z', '2026-10-02T09:00:00Z', NULL),
    -- 10: closed by the administrator.
    ('d1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000003', '265293f4-456f-4a2d-b637-1c3a263815e4',
     'Pothole on Paseo del Parque', 'A small pothole in the bike lane.',
     'CLOSED', 'LOW', 'Small pothole, little traffic', 'MANUAL',
     '2026-09-25T08:30:00Z', '2026-09-27T09:00:00Z', '2026-09-27T09:00:00Z');

INSERT INTO location (id, incident_id, coordinates, accuracy, address, neighborhood, district) VALUES
    ('d3000000-0000-4000-8000-000000000001', 'd1000000-0000-4000-8000-000000000001',
     ST_SetSRID(ST_MakePoint(-4.4189, 36.7213), 4326)::geography, 35.0, NULL, NULL, NULL),
    ('d3000000-0000-4000-8000-000000000002', 'd1000000-0000-4000-8000-000000000002',
     ST_SetSRID(ST_MakePoint(-4.4482, 36.6969), 4326)::geography, 8.0, 'Parque del Oeste', 'Parque del Oeste', 'Carretera de Cádiz'),
    ('d3000000-0000-4000-8000-000000000003', 'd1000000-0000-4000-8000-000000000003',
     ST_SetSRID(ST_MakePoint(-4.4775, 36.7166), 4326)::geography, 5.0, 'Avenida Plutarco, 22', 'Teatinos', 'Teatinos-Universidad'),
    ('d3000000-0000-4000-8000-000000000004', 'd1000000-0000-4000-8000-000000000004',
     ST_SetSRID(ST_MakePoint(-4.4290, 36.7228), 4326)::geography, 12.0, 'Calle Mármoles, 30', 'El Perchel', 'Centro'),
    ('d3000000-0000-4000-8000-000000000005', 'd1000000-0000-4000-8000-000000000005',
     ST_SetSRID(ST_MakePoint(-4.4247, 36.7393), 4326)::geography, 20.0, 'Parque de Ciudad Jardín', 'Ciudad Jardín', 'Ciudad Jardín'),
    ('d3000000-0000-4000-8000-000000000006', 'd1000000-0000-4000-8000-000000000006',
     ST_SetSRID(ST_MakePoint(-4.4400, 36.7064), 4326)::geography, 6.0, 'Calle Héroe de Sostoa, 120', 'Princesa', 'Carretera de Cádiz'),
    ('d3000000-0000-4000-8000-000000000007', 'd1000000-0000-4000-8000-000000000007',
     ST_SetSRID(ST_MakePoint(-4.4385, 36.6955), 4326)::geography, 15.0, 'Parque de Huelin', 'Huelin', 'Carretera de Cádiz'),
    ('d3000000-0000-4000-8000-000000000008', 'd1000000-0000-4000-8000-000000000008',
     ST_SetSRID(ST_MakePoint(-4.4615, 36.6890), 4326)::geography, 10.0, 'Parque Litoral', 'Sacaba Beach', 'Carretera de Cádiz'),
    ('d3000000-0000-4000-8000-000000000009', 'd1000000-0000-4000-8000-000000000009',
     ST_SetSRID(ST_MakePoint(-4.4395, 36.7155), 4326)::geography, 7.0, 'Avenida de Andalucía, 40', 'La Trinidad', 'Cruz de Humilladero'),
    ('d3000000-0000-4000-8000-000000000010', 'd1000000-0000-4000-8000-000000000010',
     ST_SetSRID(ST_MakePoint(-4.4166, 36.7189), 4326)::geography, 4.0, 'Paseo del Parque, 10', 'Centro Histórico', 'Centro');

-- Only transitions of the ADR-0020 table (IncidentTransition), made by who may make them. The
-- associated data has the format Incident.changeStatus writes.
INSERT INTO status_change (id, incident_id, actor_id, instant, previous_status, new_status, reason,
                           associated_data, visible_to_citizen) VALUES
    ('d2000000-0000-4000-8000-000000001001', 'd1000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-000000000001',
     '2026-10-20T09:15:00Z', NULL, 'REPORTED', NULL, NULL, true),

    ('d2000000-0000-4000-8000-000000002001', 'd1000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000002',
     '2026-10-19T18:40:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000002002', 'd1000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-20T08:30:00Z', 'REPORTED', 'REPORTED',
     'Which bench is it? There are several around the fountain; please add a photo.',
     '{"action": "INFORMATION_REQUESTED"}', true),

    ('d2000000-0000-4000-8000-000000003001', 'd1000000-0000-4000-8000-000000000003', 'd0000000-0000-4000-8000-000000000001',
     '2026-10-17T08:05:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000003002', 'd1000000-0000-4000-8000-000000000003', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-17T11:20:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),

    ('d2000000-0000-4000-8000-000000004001', 'd1000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-000000000002',
     '2026-10-16T12:30:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000004002', 'd1000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-16T15:00:00Z', 'REPORTED', 'REJECTED', 'Duplicate of a report that is already being handled.', NULL, true),

    ('d2000000-0000-4000-8000-000000005001', 'd1000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-000000000001',
     '2026-10-14T07:50:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000005002', 'd1000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-14T09:30:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000005003', 'd1000000-0000-4000-8000-000000000005', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-14T09:45:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "502a9777-6043-40b4-afd6-2b57cc0f055a"}', true),

    ('d2000000-0000-4000-8000-000000006001', 'd1000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000002',
     '2026-10-12T10:10:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000006002', 'd1000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-12T12:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000006003', 'd1000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-12T12:10:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "58998fe5-1698-4410-b0d0-780ae78361a6", "technicianId": "d0000000-0000-4000-8000-000000000004"}', true),
    ('d2000000-0000-4000-8000-000000006004', 'd1000000-0000-4000-8000-000000000006', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-13T09:00:00Z', 'ASSIGNED', 'ASSIGNED', 'Antonio is on leave this week.',
     '{"departmentId": "58998fe5-1698-4410-b0d0-780ae78361a6", "technicianId": "d0000000-0000-4000-8000-000000000005"}', true),

    ('d2000000-0000-4000-8000-000000007001', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000001',
     '2026-10-10T16:20:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000007002', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-10T17:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000007003', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-10T17:05:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "45e862fd-d303-4128-961d-a135e3831aa4", "technicianId": "d0000000-0000-4000-8000-000000000004"}', true),
    ('d2000000-0000-4000-8000-000000007004', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000004',
     '2026-10-11T08:15:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),

    ('d2000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000002',
     '2026-10-08T11:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000008002', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-08T12:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000008003', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-08T12:05:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "45e862fd-d303-4128-961d-a135e3831aa4", "technicianId": "d0000000-0000-4000-8000-000000000005"}', true),
    ('d2000000-0000-4000-8000-000000008004', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000005',
     '2026-10-09T08:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000008005', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000005',
     '2026-10-09T13:30:00Z', 'IN_PROGRESS', 'RESOLVED', 'Chain replaced and swing checked.', NULL, true),

    ('d2000000-0000-4000-8000-000000009001', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000001',
     '2026-09-30T09:00:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000009002', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000003',
     '2026-09-30T10:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000009003', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000003',
     '2026-09-30T10:05:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "d4f68abd-56f1-49c2-9de4-16af258b1bb9", "technicianId": "d0000000-0000-4000-8000-000000000004"}', true),
    ('d2000000-0000-4000-8000-000000009004', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000004',
     '2026-10-01T08:30:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000009005', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000004',
     '2026-10-01T12:00:00Z', 'IN_PROGRESS', 'RESOLVED', 'Sign put back and anchored.', NULL, true),
    ('d2000000-0000-4000-8000-000000009006', 'd1000000-0000-4000-8000-000000000009', 'd0000000-0000-4000-8000-000000000003',
     '2026-10-02T09:00:00Z', 'RESOLVED', 'REOPENED', 'The citizen reports that the sign has fallen again.', NULL, true),

    ('d2000000-0000-4000-8000-000000010001', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000001',
     '2026-09-25T08:30:00Z', NULL, 'REPORTED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000010002', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000003',
     '2026-09-25T10:00:00Z', 'REPORTED', 'VALIDATED', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000010003', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000003',
     '2026-09-25T10:10:00Z', 'VALIDATED', 'ASSIGNED', NULL,
     '{"departmentId": "0d339d5b-7a6f-4004-a51a-943eb7d79ee3", "technicianId": "d0000000-0000-4000-8000-000000000004"}', true),
    ('d2000000-0000-4000-8000-000000010004', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000004',
     '2026-09-26T08:00:00Z', 'ASSIGNED', 'IN_PROGRESS', NULL, NULL, true),
    ('d2000000-0000-4000-8000-000000010005', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000004',
     '2026-09-26T11:00:00Z', 'IN_PROGRESS', 'RESOLVED', 'Pothole filled with cold asphalt.', NULL, true),
    ('d2000000-0000-4000-8000-000000010006', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000006',
     '2026-09-27T09:00:00Z', 'RESOLVED', 'CLOSED', NULL, NULL, true);

-- One row per ASSIGNMENT entry above; a reassignment deactivates the previous one.
INSERT INTO assignment (id, incident_id, department_id, technician_id, team, assigned_at, is_active) VALUES
    ('d4000000-0000-4000-8000-000000005001', 'd1000000-0000-4000-8000-000000000005',
     '502a9777-6043-40b4-afd6-2b57cc0f055a', NULL, 'Night maintenance crew', '2026-10-14T09:45:00Z', true),
    ('d4000000-0000-4000-8000-000000006001', 'd1000000-0000-4000-8000-000000000006',
     '58998fe5-1698-4410-b0d0-780ae78361a6', 'd0000000-0000-4000-8000-000000000004', NULL, '2026-10-12T12:10:00Z', false),
    ('d4000000-0000-4000-8000-000000006002', 'd1000000-0000-4000-8000-000000000006',
     '58998fe5-1698-4410-b0d0-780ae78361a6', 'd0000000-0000-4000-8000-000000000005', NULL, '2026-10-13T09:00:00Z', true),
    ('d4000000-0000-4000-8000-000000007001', 'd1000000-0000-4000-8000-000000000007',
     '45e862fd-d303-4128-961d-a135e3831aa4', 'd0000000-0000-4000-8000-000000000004', NULL, '2026-10-10T17:05:00Z', true),
    ('d4000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008',
     '45e862fd-d303-4128-961d-a135e3831aa4', 'd0000000-0000-4000-8000-000000000005', NULL, '2026-10-08T12:05:00Z', true),
    ('d4000000-0000-4000-8000-000000009001', 'd1000000-0000-4000-8000-000000000009',
     'd4f68abd-56f1-49c2-9de4-16af258b1bb9', 'd0000000-0000-4000-8000-000000000004', NULL, '2026-09-30T10:05:00Z', true),
    ('d4000000-0000-4000-8000-000000010001', 'd1000000-0000-4000-8000-000000000010',
     '0d339d5b-7a6f-4004-a51a-943eb7d79ee3', 'd0000000-0000-4000-8000-000000000004', NULL, '2026-09-25T10:10:00Z', true);

INSERT INTO comment (id, incident_id, author_id, text, created_at, visible_to_citizen) VALUES
    ('d5000000-0000-4000-8000-000000002001', 'd1000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000003',
     'The photo shows the second bench on the left of the main fountain.', '2026-10-20T09:10:00Z', false),
    ('d5000000-0000-4000-8000-000000007001', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000004',
     'The branch is too heavy for one person: we need the crane truck tomorrow morning.', '2026-10-11T08:20:00Z', false),
    ('d5000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000003',
     'The swing has been repaired. We will close the incident after a final check.', '2026-10-09T14:00:00Z', false);

-- Metadata only (ADR-0013): no real file exists behind the server-generated storage_key, and
-- content_hash is the SHA-256 of the file name, not of any content.
INSERT INTO attachment (id, incident_id, comment_id, uploader_id, file_name, file_type, file_size,
                        storage_key, content_hash, uploaded_at) VALUES
    ('d6000000-0000-4000-8000-000000002001', 'd1000000-0000-4000-8000-000000000002', NULL,
     'd0000000-0000-4000-8000-000000000002', 'bench.jpg', 'image/jpeg', 482133,
     '6f1c2b0e-3d4a-4c5b-9e8f-1a2b3c4d5e6f',
     '01c6738912c82aa26209927da67e7c8371494b8539e3f34a0492ebc2707e1f5d', '2026-10-20T09:10:00Z'),
    ('d6000000-0000-4000-8000-000000003001', 'd1000000-0000-4000-8000-000000000003', NULL,
     'd0000000-0000-4000-8000-000000000001', 'pothole.jpg', 'image/jpeg', 1204551,
     '0b7e4f6a-8c2d-4e1f-a3b5-c7d9e1f3a5b7',
     'ad9d47932ee60fa9846afd347abdf2588ff91cc22e0e30233c77926223dd2289', '2026-10-17T08:05:00Z'),
    ('d6000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008', NULL,
     'd0000000-0000-4000-8000-000000000005', 'swing-repaired.jpg', 'image/jpeg', 905220,
     '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d',
     '5c3d77e55158f0ee3902c58d267d42cd608c394a809e35fee34a41c76099a604', '2026-10-09T13:30:00Z');

-- Every status of the context, so missing or failed data is never shown as current.
INSERT INTO urban_context (id, incident_id, weather, traffic_status, event, status, captured_at) VALUES
    ('d7000000-0000-4000-8000-000000000001', 'd1000000-0000-4000-8000-000000000001',
     NULL, NULL, NULL, 'PENDING', NULL),
    ('d7000000-0000-4000-8000-000000000003', 'd1000000-0000-4000-8000-000000000003',
     'SUNNY', 'MEDIUM', NULL, 'COMPLETE', '2026-10-17T08:06:00Z'),
    ('d7000000-0000-4000-8000-000000000005', 'd1000000-0000-4000-8000-000000000005',
     'RAINY', NULL, NULL, 'PARTIAL', '2026-10-14T07:51:00Z'),
    ('d7000000-0000-4000-8000-000000000006', 'd1000000-0000-4000-8000-000000000006',
     NULL, NULL, NULL, 'ERROR', NULL),
    ('d7000000-0000-4000-8000-000000000007', 'd1000000-0000-4000-8000-000000000007',
     NULL, NULL, NULL, 'UNAVAILABLE', NULL),
    ('d7000000-0000-4000-8000-000000000009', 'd1000000-0000-4000-8000-000000000009',
     'WINDY', 'HIGH', 'Football match at La Rosaleda', 'COMPLETE', '2026-09-30T09:01:00Z');

-- A current value, a pending and a failed one without value (unknown is not zero), and a stale one
-- whose expiration date has passed.
INSERT INTO external_observation (id, urban_context_id, source, measurement_type, value, unit, quality,
                                  observation_instant, ingestion_date, expiration_date, status) VALUES
    ('d8000000-0000-4000-8000-000000000001', 'd7000000-0000-4000-8000-000000000003', 'malaga-open-data',
     'TRAFFIC_INTENSITY', '640', 'vehicles/h', 'GOOD',
     '2026-10-17T07:55:00Z', '2026-10-17T08:06:00Z', '2026-10-17T09:55:00Z', 'VALID'),
    ('d8000000-0000-4000-8000-000000000002', 'd7000000-0000-4000-8000-000000000005', 'malaga-open-data',
     'TRAFFIC_INTENSITY', NULL, 'vehicles/h', NULL,
     NULL, '2026-10-14T07:51:00Z', NULL, 'PENDING'),
    ('d8000000-0000-4000-8000-000000000003', 'd7000000-0000-4000-8000-000000000006', 'malaga-open-data',
     'TRAFFIC_INTENSITY', NULL, 'vehicles/h', NULL,
     NULL, '2026-10-12T10:11:00Z', NULL, 'ERROR'),
    ('d8000000-0000-4000-8000-000000000004', 'd7000000-0000-4000-8000-000000000009', 'malaga-open-data',
     'TRAFFIC_INTENSITY', '1350', 'vehicles/h', 'GOOD',
     '2026-09-30T08:00:00Z', '2026-09-30T09:01:00Z', '2026-09-30T10:00:00Z', 'EXPIRED');

-- Incident 2 has two candidate fountains, because neither is certain; the others are confirmed.
INSERT INTO incident_asset (id, incident_id, urban_asset_id, distance_to_incident, confidence_level, status) VALUES
    ('da000000-0000-4000-8000-000000002001', 'd1000000-0000-4000-8000-000000000002',
     'd9000000-0000-4000-8000-000000000001', 18.5, 0.62, 'CANDIDATE'),
    ('da000000-0000-4000-8000-000000002002', 'd1000000-0000-4000-8000-000000000002',
     'd9000000-0000-4000-8000-000000000002', 24.0, 0.48, 'CANDIDATE'),
    ('da000000-0000-4000-8000-000000006001', 'd1000000-0000-4000-8000-000000000006',
     'd9000000-0000-4000-8000-000000000003', 3.2, 0.97, 'CONFIRMED'),
    ('da000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008',
     'd9000000-0000-4000-8000-000000000004', 15.0, 0.90, 'CONFIRMED');

-- One per delivery status; sent_at stays empty while a notification is pending.
INSERT INTO notification (id, incident_id, recipient_id, message, channel, delivery_status, sent_at) VALUES
    ('db000000-0000-4000-8000-000000004001', 'd1000000-0000-4000-8000-000000000004', 'd0000000-0000-4000-8000-000000000002',
     'Your report "Overflowing bins" was rejected: duplicate of a report that is already being handled.',
     'APP', 'DELIVERED', '2026-10-16T15:00:00Z'),
    ('db000000-0000-4000-8000-000000007001', 'd1000000-0000-4000-8000-000000000007', 'd0000000-0000-4000-8000-000000000004',
     'New incident assigned to you: "Fallen tree branch blocking a path".',
     'APP', 'DELIVERED', '2026-10-10T17:05:00Z'),
    ('db000000-0000-4000-8000-000000008001', 'd1000000-0000-4000-8000-000000000008', 'd0000000-0000-4000-8000-000000000002',
     'Your incident "Broken swing in a play area" has been resolved.',
     'EMAIL', 'PENDING', NULL),
    ('db000000-0000-4000-8000-000000010001', 'd1000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000001',
     'Your incident "Pothole on Paseo del Parque" has been closed.',
     'SMS', 'FAILED', '2026-09-27T09:00:00Z');
