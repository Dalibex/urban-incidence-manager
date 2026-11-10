-- Performance dataset (#84). Loaded only by the perf profile (application-perf.yaml), never in
-- production, to measure quality attributes on a realistic volume (docs/development.md,
-- "Performance Dataset"). Never activate it together with the dev or demo profile: each one empties
-- the incident data of the others.
--
-- The rows are generated here with generate_series, not stored: the volume is the perf_incidents
-- placeholder (PERF_INCIDENTS, 100000 by default) and everything else follows from it.
--
-- Deterministic: every random() follows the seed below and is drawn once per incident, in order,
-- into perf_draw; ids come from row numbers and the password hash from a fixed salt. Two loads with
-- the same volume give the same rows, on any machine.
--
-- Running it again gives the same rows without duplicates: users are inserted or updated by their
-- fixed id and never deleted, and the incident data is emptied and inserted again.
--
-- Fixed ids: the first group says the table (f0 users, f1 incident, f2 status_change, f3 location,
-- f4 assignment, f5 comment, f6 attachment, fb notification) and the last one is a number in hex.
-- Users carry their role in the fourth group (8000 citizen, 8001 operator, 8002 technician,
-- 8003 administrator, 8004 analyst); history entries number n * 8 + their position.
--
-- Every user's password is "urbanpulse-perf". Coordinates are WGS 84, longitude first.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

TRUNCATE incident, location, status_change, assignment, comment, attachment, urban_context,
    external_observation, incident_asset, notification CASCADE;

SELECT setseed(0.84);

-- Users: one citizen per twenty incidents, and a fixed staff.
DROP TABLE IF EXISTS perf_user;
CREATE TEMPORARY TABLE perf_user AS
SELECT format('f0000000-0000-4000-%s-%s', kind.code, lpad(to_hex(n), 12, '0'))::uuid AS id,
       kind.role,
       n,
       format('Perf %s %s', kind.label, n) AS name,
       format('perf.%s%s@example.com', kind.handle, n) AS email
FROM (VALUES
    ('8000', 'CITIZEN', 'Citizen', 'citizen', greatest(${perf_incidents} / 20, 10)),
    ('8001', 'MUNICIPAL_OPERATOR', 'Operator', 'operator', 10),
    ('8002', 'TECHNICIAN', 'Technician', 'technician', 40),
    ('8003', 'ADMINISTRATOR', 'Administrator', 'admin', 2),
    ('8004', 'ANALYST', 'Analyst', 'analyst', 3)
) AS kind (code, role, label, handle, total)
CROSS JOIN LATERAL generate_series(1, kind.total) AS n;

-- bcrypt with a fixed salt: the same hash on every load, computed once for every user.
DROP TABLE IF EXISTS perf_password;
CREATE TEMPORARY TABLE perf_password AS
SELECT '{bcrypt}' || crypt('urbanpulse-perf', '$2a$10$UrbanPulsePerfDataSalt') AS hash;

INSERT INTO users (id, name, email, password_hash, role, enabled)
SELECT u.id, u.name, u.email, p.hash, u.role, true
FROM perf_user u CROSS JOIN perf_password p
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    email = EXCLUDED.email,
    password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    enabled = EXCLUDED.enabled;

-- Every random value of an incident, drawn once and in order. The steps below only read them.
-- r_unused is read by nothing since every comment is internal (#50); it is still drawn so the
-- values after it, and the checksums of docs/datasets.md, stay the same.
DROP TABLE IF EXISTS perf_draw;
CREATE TEMPORARY TABLE perf_draw AS
SELECT n,
       random() AS r_time, random() AS r_hour, random() AS r_reporter, random() AS r_status,
       random() AS r_info, random() AS r_has_category, random() AS r_category,
       random() AS r_priority, random() AS r_operator, random() AS r_technician, random() AS r_gap,
       random() AS r_cluster, random() AS r_dx1, random() AS r_dx2, random() AS r_dx3,
       random() AS r_dy1, random() AS r_dy2, random() AS r_dy3, random() AS r_accuracy,
       random() AS r_place, random() AS r_number, random() AS r_comment,
       random() AS r_unused, random() AS r_attachment, random() AS r_file_size,
       random() AS r_channel, random() AS r_delivery
FROM generate_series(1, ${perf_incidents}) AS n;

-- Places where incidents cluster. The first ones are the busiest: the city centre gets the most.
DROP TABLE IF EXISTS perf_cluster;
CREATE TEMPORARY TABLE perf_cluster (idx, longitude, latitude, street, neighborhood, district) AS
VALUES
    (1, -4.4210, 36.7210, 'Calle Larios', 'Centro Histórico', 'Centro'),
    (2, -4.4352, 36.7169, 'Avenida de Andalucía', 'La Aurora', 'Cruz de Humilladero'),
    (3, -4.4778, 36.7172, 'Calle Navarro Ledesma', 'Teatinos', 'Teatinos-Universidad'),
    (4, -4.4385, 36.6955, 'Calle Ayala', 'Huelin', 'Carretera de Cádiz'),
    (5, -4.4030, 36.7190, 'Paseo de Reding', 'La Malagueta', 'Este'),
    (6, -4.4247, 36.7393, 'Avenida Jorge Silvela', 'Ciudad Jardín', 'Ciudad Jardín'),
    (7, -4.4330, 36.7260, 'Calle Trinidad', 'La Trinidad', 'Cruz de Humilladero'),
    (8, -4.4280, 36.7330, 'Calle Martínez Maldonado', 'Miraflores de los Ángeles', 'Bailén-Miraflores'),
    (9, -4.3644, 36.7211, 'Calle Bolivia', 'El Palo', 'Este'),
    (10, -4.4370, 36.7430, 'Calle Doctor Gálvez Moll', 'La Palmilla', 'Palma-Palmilla'),
    (11, -4.4810, 36.7420, 'Calle Lope de Rueda', 'Puerto de la Torre', 'Puerto de la Torre'),
    (12, -4.5030, 36.6690, 'Calle Torremolinos', 'Churriana', 'Churriana');

-- Categories of V7, repeated by how often they are reported, the department that handles each,
-- and a title for its incidents.
DROP TABLE IF EXISTS perf_category;
CREATE TEMPORARY TABLE perf_category (idx, category_id, department_id, title) AS
VALUES
    (1, 'fd4a9835-80be-4b73-bc9f-a042bed62b61'::uuid, '502a9777-6043-40b4-afd6-2b57cc0f055a'::uuid, 'Street light off'),
    (2, 'fd4a9835-80be-4b73-bc9f-a042bed62b61'::uuid, '502a9777-6043-40b4-afd6-2b57cc0f055a'::uuid, 'Street light off'),
    (3, 'fd4a9835-80be-4b73-bc9f-a042bed62b61'::uuid, '502a9777-6043-40b4-afd6-2b57cc0f055a'::uuid, 'Street light off'),
    (4, 'fd4a9835-80be-4b73-bc9f-a042bed62b61'::uuid, '502a9777-6043-40b4-afd6-2b57cc0f055a'::uuid, 'Street light off'),
    (5, '265293f4-456f-4a2d-b637-1c3a263815e4'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Pothole'),
    (6, '265293f4-456f-4a2d-b637-1c3a263815e4'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Pothole'),
    (7, '265293f4-456f-4a2d-b637-1c3a263815e4'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Pothole'),
    (8, '265293f4-456f-4a2d-b637-1c3a263815e4'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Loose paving stones'),
    (9, '265293f4-456f-4a2d-b637-1c3a263815e4'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Loose paving stones'),
    (10, 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee'::uuid, '58998fe5-1698-4410-b0d0-780ae78361a6'::uuid, 'Overflowing bins'),
    (11, 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee'::uuid, '58998fe5-1698-4410-b0d0-780ae78361a6'::uuid, 'Overflowing bins'),
    (12, 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee'::uuid, '58998fe5-1698-4410-b0d0-780ae78361a6'::uuid, 'Overflowing bins'),
    (13, 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee'::uuid, '58998fe5-1698-4410-b0d0-780ae78361a6'::uuid, 'Rubbish dumped in the street'),
    (14, 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee'::uuid, '58998fe5-1698-4410-b0d0-780ae78361a6'::uuid, 'Rubbish dumped in the street'),
    (15, '16fff6f7-e8ab-4841-ae3e-9dff80314c01'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Broken bench'),
    (16, '16fff6f7-e8ab-4841-ae3e-9dff80314c01'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Damaged bus shelter'),
    (17, '16fff6f7-e8ab-4841-ae3e-9dff80314c01'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Leaking fountain'),
    (18, 'edaddd74-d0d9-4185-adc8-e278c468eb29'::uuid, '45e862fd-d303-4128-961d-a135e3831aa4'::uuid, 'Fallen branch'),
    (19, 'edaddd74-d0d9-4185-adc8-e278c468eb29'::uuid, '45e862fd-d303-4128-961d-a135e3831aa4'::uuid, 'Broken play area'),
    (20, 'edaddd74-d0d9-4185-adc8-e278c468eb29'::uuid, '45e862fd-d303-4128-961d-a135e3831aa4'::uuid, 'Broken sprinkler'),
    (21, '3a73f376-34c4-4112-b85a-89f104c0f1f4'::uuid, 'd4f68abd-56f1-49c2-9de4-16af258b1bb9'::uuid, 'Traffic sign knocked down'),
    (22, '3a73f376-34c4-4112-b85a-89f104c0f1f4'::uuid, 'd4f68abd-56f1-49c2-9de4-16af258b1bb9'::uuid, 'Faded road marking'),
    (23, '474f62e6-521a-4958-ba99-e0d46b420233'::uuid, '0d339d5b-7a6f-4004-a51a-943eb7d79ee3'::uuid, 'Problem in the street');

-- One row per incident with everything its rows need. Incidents are reported over eleven and a half
-- months; older ones are mostly finished, recent ones still in progress. A few citizens report
-- many incidents (the cube of a uniform value favours the first ones).
DROP TABLE IF EXISTS perf_incident;
CREATE TEMPORARY TABLE perf_incident AS
SELECT d.*,
       format('f1000000-0000-4000-8000-%s', lpad(to_hex(d.n), 12, '0'))::uuid AS id,
       timestamptz '2025-11-01 07:00:00+00' + d.r_time * interval '348 days'
           + d.r_hour * interval '14 hours' AS reported_at,
       format('f0000000-0000-4000-8000-%s',
              lpad(to_hex(1 + floor(greatest(${perf_incidents} / 20, 10) * d.r_reporter ^ 3)::int), 12, '0'))::uuid
           AS reporter_id,
       format('f0000000-0000-4000-8001-%s', lpad(to_hex(1 + floor(d.r_operator * 10)::int), 12, '0'))::uuid
           AS operator_id,
       format('f0000000-0000-4000-8002-%s', lpad(to_hex(1 + floor(d.r_technician * 40)::int), 12, '0'))::uuid
           AS technician_id,
       interval '1 hour' + d.r_gap * interval '47 hours' AS gap,
       CASE WHEN d.r_info < 0.12 THEN 1 ELSE 0 END AS info_steps,
       CASE
           WHEN d.r_time < 0.8 THEN CASE
               WHEN d.r_status < 0.02 THEN 'REPORTED'
               WHEN d.r_status < 0.04 THEN 'VALIDATED'
               WHEN d.r_status < 0.08 THEN 'ASSIGNED'
               WHEN d.r_status < 0.13 THEN 'IN_PROGRESS'
               WHEN d.r_status < 0.18 THEN 'RESOLVED'
               WHEN d.r_status < 0.20 THEN 'REOPENED'
               WHEN d.r_status < 0.30 THEN 'REJECTED'
               ELSE 'CLOSED' END
           ELSE CASE
               WHEN d.r_status < 0.25 THEN 'REPORTED'
               WHEN d.r_status < 0.37 THEN 'VALIDATED'
               WHEN d.r_status < 0.52 THEN 'ASSIGNED'
               WHEN d.r_status < 0.67 THEN 'IN_PROGRESS'
               WHEN d.r_status < 0.77 THEN 'RESOLVED'
               WHEN d.r_status < 0.79 THEN 'REOPENED'
               WHEN d.r_status < 0.87 THEN 'REJECTED'
               ELSE 'CLOSED' END
       END AS final_status,
       c.category_id, c.department_id, c.title AS category_title,
       k.longitude + (d.r_dx1 + d.r_dx2 + d.r_dx3 - 1.5) * 0.006 AS longitude,
       k.latitude + (d.r_dy1 + d.r_dy2 + d.r_dy3 - 1.5) * 0.005 AS latitude,
       k.street, k.neighborhood, k.district
FROM perf_draw d
JOIN perf_category c ON c.idx = 1 + floor(d.r_category * 23)::int
JOIN perf_cluster k ON k.idx = 1 + floor(d.r_cluster ^ 1.6 * 12)::int;

-- The transitions of the ADR-0020 table that lead to each final status, after the report and an
-- optional information request.
DROP TABLE IF EXISTS perf_path;
CREATE TEMPORARY TABLE perf_path (final_status, k, transition, previous_status, new_status) AS
VALUES
    ('REJECTED', 1, 'REJECTION', 'REPORTED', 'REJECTED'),
    ('VALIDATED', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('ASSIGNED', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('ASSIGNED', 2, 'ASSIGNMENT', 'VALIDATED', 'ASSIGNED'),
    ('IN_PROGRESS', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('IN_PROGRESS', 2, 'ASSIGNMENT', 'VALIDATED', 'ASSIGNED'),
    ('IN_PROGRESS', 3, 'ACCEPTANCE', 'ASSIGNED', 'IN_PROGRESS'),
    ('RESOLVED', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('RESOLVED', 2, 'ASSIGNMENT', 'VALIDATED', 'ASSIGNED'),
    ('RESOLVED', 3, 'ACCEPTANCE', 'ASSIGNED', 'IN_PROGRESS'),
    ('RESOLVED', 4, 'RESOLUTION', 'IN_PROGRESS', 'RESOLVED'),
    ('REOPENED', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('REOPENED', 2, 'ASSIGNMENT', 'VALIDATED', 'ASSIGNED'),
    ('REOPENED', 3, 'ACCEPTANCE', 'ASSIGNED', 'IN_PROGRESS'),
    ('REOPENED', 4, 'RESOLUTION', 'IN_PROGRESS', 'RESOLVED'),
    ('REOPENED', 5, 'REOPENING', 'RESOLVED', 'REOPENED'),
    ('CLOSED', 1, 'VALIDATION', 'REPORTED', 'VALIDATED'),
    ('CLOSED', 2, 'ASSIGNMENT', 'VALIDATED', 'ASSIGNED'),
    ('CLOSED', 3, 'ACCEPTANCE', 'ASSIGNED', 'IN_PROGRESS'),
    ('CLOSED', 4, 'RESOLUTION', 'IN_PROGRESS', 'RESOLVED'),
    ('CLOSED', 5, 'CLOSURE', 'RESOLVED', 'CLOSED');

-- Number of entries after the report, and whether the incident was validated (which needs a
-- category and a priority) and assigned.
DROP TABLE IF EXISTS perf_shape;
CREATE TEMPORARY TABLE perf_shape AS
SELECT i.id,
       i.info_steps + coalesce(max(p.k), 0) AS last_step,
       bool_or(p.transition = 'VALIDATION') IS TRUE AS validated,
       bool_or(p.transition = 'ASSIGNMENT') IS TRUE AS assigned
FROM perf_incident i
LEFT JOIN perf_path p ON p.final_status = i.final_status
GROUP BY i.id, i.info_steps;

INSERT INTO incident (id, reporter_id, priority_setter_id, category_id, title, description, status,
                      priority, priority_reason, priority_source, reported_at, updated_at, closed_at)
SELECT i.id,
       i.reporter_id,
       CASE WHEN s.validated THEN i.operator_id END,
       CASE WHEN s.validated OR i.r_has_category < 0.75 THEN i.category_id END,
       format('%s on %s', i.category_title, i.street),
       format('%s near number %s of %s, reported for the performance measurements.',
              i.category_title, 1 + floor(i.r_number * 150)::int, i.street),
       i.final_status,
       CASE WHEN NOT s.validated THEN NULL
            WHEN i.r_priority < 0.4 THEN 'LOW'
            WHEN i.r_priority < 0.8 THEN 'MEDIUM'
            ELSE 'HIGH' END,
       CASE WHEN s.validated THEN 'Set when the incident was validated' END,
       CASE WHEN s.validated THEN 'MANUAL' END,
       i.reported_at,
       i.reported_at + s.last_step * i.gap,
       CASE WHEN i.final_status = 'CLOSED' THEN i.reported_at + s.last_step * i.gap END
FROM perf_incident i
JOIN perf_shape s ON s.id = i.id;

-- A few positions without accuracy, and a few places not resolved yet ("not available").
INSERT INTO location (id, incident_id, coordinates, accuracy, address, neighborhood, district)
SELECT format('f3000000-0000-4000-8000-%s', lpad(to_hex(i.n), 12, '0'))::uuid,
       i.id,
       ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326)::geography,
       CASE WHEN i.r_accuracy < 0.05 THEN NULL ELSE round((3 + i.r_accuracy * 40)::numeric, 1) END,
       CASE WHEN i.r_place < 0.08 THEN NULL ELSE format('%s, %s', i.street, 1 + floor(i.r_number * 150)::int) END,
       CASE WHEN i.r_place < 0.08 THEN NULL ELSE i.neighborhood END,
       CASE WHEN i.r_place < 0.08 THEN NULL ELSE i.district END
FROM perf_incident i;

-- The report, then the information request when there is one, then the steps of the path, one gap
-- apart. Only transitions of the ADR-0020 table, made by who may make them.
INSERT INTO status_change (id, incident_id, actor_id, instant, previous_status, new_status, reason,
                           associated_data, visible_to_citizen)
SELECT format('f2000000-0000-4000-8000-%s', lpad(to_hex(i.n * 8), 12, '0'))::uuid,
       i.id, i.reporter_id, i.reported_at, NULL, 'REPORTED', NULL, NULL, true
FROM perf_incident i
UNION ALL
SELECT format('f2000000-0000-4000-8000-%s', lpad(to_hex(i.n * 8 + 1), 12, '0'))::uuid,
       i.id, i.operator_id, i.reported_at + i.gap, 'REPORTED', 'REPORTED',
       'Please add a photo so the crew can find the exact place.',
       '{"action": "INFORMATION_REQUESTED"}'::jsonb, true
FROM perf_incident i
WHERE i.info_steps = 1
UNION ALL
SELECT format('f2000000-0000-4000-8000-%s', lpad(to_hex(i.n * 8 + i.info_steps + p.k), 12, '0'))::uuid,
       i.id,
       CASE WHEN p.transition IN ('ACCEPTANCE', 'RESOLUTION') THEN i.technician_id ELSE i.operator_id END,
       i.reported_at + (i.info_steps + p.k) * i.gap,
       p.previous_status,
       p.new_status,
       CASE p.transition
           WHEN 'REJECTION' THEN 'Duplicate of a report that is already being handled.'
           WHEN 'RESOLUTION' THEN 'Repaired and checked on site.'
           WHEN 'REOPENING' THEN 'The citizen reports that the problem has appeared again.'
       END,
       CASE WHEN p.transition = 'ASSIGNMENT'
            THEN jsonb_build_object('departmentId', i.department_id, 'technicianId', i.technician_id)
       END,
       true
FROM perf_incident i
JOIN perf_path p ON p.final_status = i.final_status;

-- One row per ASSIGNMENT entry above.
INSERT INTO assignment (id, incident_id, department_id, technician_id, team, assigned_at, is_active)
SELECT format('f4000000-0000-4000-8000-%s', lpad(to_hex(i.n), 12, '0'))::uuid,
       i.id, i.department_id, i.technician_id, NULL,
       i.reported_at + (i.info_steps + 2) * i.gap, true
FROM perf_incident i
JOIN perf_shape s ON s.id = i.id
WHERE s.assigned;

INSERT INTO comment (id, incident_id, author_id, text, created_at, visible_to_citizen)
SELECT format('f5000000-0000-4000-8000-%s', lpad(to_hex(i.n), 12, '0'))::uuid,
       i.id,
       CASE WHEN s.assigned THEN i.technician_id ELSE i.operator_id END,
       CASE WHEN s.assigned THEN 'Checked on site; the crew will come back with the right tools.'
            WHEN s.validated THEN 'Waiting for a department to take it.'
            ELSE 'Asked the district office whether this spot was already reported.' END,
       i.reported_at + i.gap / 2,
       false
FROM perf_incident i
JOIN perf_shape s ON s.id = i.id
WHERE i.r_comment < 0.3;

-- Metadata only (ADR-0013): no file exists behind the storage key, and the hash is not of any
-- content.
INSERT INTO attachment (id, incident_id, comment_id, uploader_id, file_name, file_type, file_size,
                        storage_key, content_hash, uploaded_at)
SELECT format('f6000000-0000-4000-8000-%s', lpad(to_hex(i.n), 12, '0'))::uuid,
       i.id, NULL, i.reporter_id, 'photo.jpg', 'image/jpeg',
       150000 + floor(i.r_file_size * 2850000)::bigint,
       format('perf/%s', i.n),
       md5(i.n::text) || md5((i.n + 1)::text),
       i.reported_at
FROM perf_incident i
WHERE i.r_attachment < 0.4;

-- The citizen hears about the last change of every incident that changed after the report.
INSERT INTO notification (id, incident_id, recipient_id, message, channel, delivery_status, sent_at)
SELECT format('fb000000-0000-4000-8000-%s', lpad(to_hex(i.n), 12, '0'))::uuid,
       i.id, i.reporter_id,
       format('Your incident "%s on %s" is now %s.', i.category_title, i.street,
              lower(replace(i.final_status, '_', ' '))),
       CASE WHEN i.r_channel < 0.7 THEN 'APP' WHEN i.r_channel < 0.95 THEN 'EMAIL' ELSE 'SMS' END,
       CASE WHEN i.r_delivery < 0.9 THEN 'DELIVERED' WHEN i.r_delivery < 0.95 THEN 'PENDING' ELSE 'FAILED' END,
       CASE WHEN i.r_delivery < 0.9 OR i.r_delivery >= 0.95 THEN i.reported_at + s.last_step * i.gap END
FROM perf_incident i
JOIN perf_shape s ON s.id = i.id
WHERE s.last_step > 0;

DROP TABLE perf_user, perf_password, perf_draw, perf_cluster, perf_category, perf_incident, perf_path,
    perf_shape;
