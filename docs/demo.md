# 🎬 Demo Script

How to start UrbanPulse with the demo dataset and walk through it. The demo runs on the same
Compose stack, code and configuration as development; only the `demo` Spring profile adds the
curated story (#54). Every step names the issue that delivers it: a step whose issue is not merged
yet cannot be shown.

## The Story

Five users, one per role, and fifteen incidents in Málaga reported by the citizen over the last two
weeks. Together they cover every status of the lifecycle, each with a history allowed by
[ADR-0020](adr/0020-incident-lifecycle-transitions.md).

| Email | Name | Role |
| --- | --- | --- |
| `demo.citizen@example.com` | Ana Fernández | `CITIZEN` |
| `demo.operator@example.com` | Raúl Ortega | `MUNICIPAL_OPERATOR` |
| `demo.technician@example.com` | Sergio Molina | `TECHNICIAN` |
| `demo.admin@example.com` | Isabel Romero | `ADMINISTRATOR` |
| `demo.analyst@example.com` | Diego Herrera | `ANALYST` |

Every user signs in with the password in `DEMO_PASSWORD`. It is never written in the repository.

| Status | Incidents |
| --- | --- |
| `REPORTED` | Street light flickering on Calle Larios (no category, address not resolved yet); loose paving stones in Plaza de la Merced (the operator asked for a photo); overflowing recycling containers in El Palo |
| `VALIDATED` | Pothole on Avenida de Andalucía; broken bench in Paseo del Parque |
| `REJECTED` | Loud music from a bar terrace (handled by the Municipal Police); fallen sign on the seafront promenade (duplicate) |
| `ASSIGNED` | Broken sprinkler in Jardines de Pedro Luis Alonso (to the technician); graffiti on a shop shutter (to the Cleaning and Waste department, no technician yet) |
| `IN_PROGRESS` | Traffic light out on Alameda Principal; tree roots lifting the pavement in Calle Victoria |
| `RESOLVED` | Broken swing in Parque del Oeste; street lights off on Calle Héroe de Sostoa |
| `REOPENED` | Leaking fountain in Plaza de la Constitución |
| `CLOSED` | Old furniture left next to the containers in Teatinos |

## Start the Demo

Follow these steps on a clean clone, with the prerequisites of the
[development guide](development.md) installed and Docker running.

1. Create `backend/.env` from `backend/.env.example` (see *Create the Local Environment File* in the
   development guide) and set a password for the demo users, without quotes:

   ```properties
   DEMO_PASSWORD=choose-a-password
   ```

2. Start from an empty database. A database that loaded another data profile, such as `dev`, does
   not start with `demo`. From `backend/`:

   ```bash
   docker compose --profile app down -v
   ```

3. Start the backend with the `demo` profile from the terminal, not in Docker: the phone must reach
   it through your machine's address in the local network, and the Docker service only listens on
   `127.0.0.1`. From `backend/`:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
   ```

   On Windows PowerShell:

   ```powershell
   ./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
   ```

   Without `DEMO_PASSWORD` the backend does not start. If Windows asks whether Java may use the
   network, allow private networks.

4. Start the web app. From `frontend/`, after `pnpm install`:

   ```bash
   pnpm dev:web
   ```

   It opens at `http://localhost:5173`.

5. Start the mobile app on a phone in the same Wi-Fi network as your machine. Copy
   `frontend/apps/mobile/.env.example` to `frontend/apps/mobile/.env` and set your machine's
   address in that network, never `localhost`, which on the phone is the phone itself:

   ```properties
   EXPO_PUBLIC_API_BASE_URL=http://192.168.1.20:8080/api/v1
   ```

   Find the address with `ipconfig` on Windows (*IPv4 Address* of the Wi-Fi adapter) or
   `ip addr` on Linux. Then, from `frontend/`:

   ```bash
   pnpm dev:mobile
   ```

   Scan the QR code with Expo Go. If the phone cannot connect, use `pnpm dev:mobile --tunnel` (see
   *Client Workspace* in the development guide).

## Walk Through It

| # | Who and where | What to do | What it shows | Needs |
| --- | --- | --- | --- | --- |
| 1 | Operator, web | Sign in as `demo.operator@example.com`. | Each role lands on its own screen: the operator on the inbox. | #39 |
| 2 | Operator, web | Look at the inbox. | The fifteen incidents, newest first, with category, status and priority. | #46 |
| 3 | Operator, web | Open *Street light flickering on Calle Larios*. | An incident without category; address, neighbourhood and district are "not available", never empty or invented. | #46 |
| 4 | Operator, web | Open *Leaking fountain in Plaza de la Constitución*. | The full history from the report to the reopening, with who made each change and the internal reasons (resolution notes, reopening). | #46 |
| 5 | Citizen, phone | Sign in as `demo.citizen@example.com` and report a new incident with your location, for example a bin knocked over on Calle Granada. | Reporting from the phone with the device's position and accuracy. | #43 |
| 6 | Operator, web | Reload the inbox and open the new incident. | The report is first in the list, with its position; the address is "not available" until the context is resolved, and the report was accepted anyway. | #43, #46 |
| 7 | Citizen, phone | Open the list of your incidents. | The sixteen incidents of the citizen with their status. | #45 |
| 8 | Citizen, phone | Open *Loud music from a bar terrace*, then *Leaking fountain in Plaza de la Constitución*. | The rejection reason, which the citizen sees; the history of the fountain without the internal resolution and reopening notes. | #45 |
| 9 | Citizen, web | Sign out on the web and sign in as `demo.citizen@example.com`, then open `/inbox`. | The web sends citizens to the mobile app: the inbox is staff only, and the API checks it again. | #39 |
| 10 | Technician, administrator, analyst, web | Sign in with each of them. | Each lands on its own screen (work queue, users, reports). | #39 |

## Repeat or End the Demo

The incident created in step 5 stays in the database when you restart the backend. To repeat the
demo from the same starting point, stop the backend and reset the database (step 2), then start it
again (step 3).

To go back to development, reset the database and start with the `dev` profile (see *Development
Dataset* in the development guide).
