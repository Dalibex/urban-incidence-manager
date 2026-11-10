# 🎬 Demo Script

How to start UrbanPulse with the demo dataset and walk through it. The demo runs on the same
Compose stack, code and configuration as development; only the `demo` Spring profile adds the
curated story (#54). Every step names the issue that delivers it: a step whose issue is not merged
yet cannot be shown. What each screen does beyond this story is in the [user guide](user-guide.md).

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
   EXPO_PUBLIC_MAP_STYLE_URL=http://192.168.1.20:5173/tiles/style.json
   ```

   Find the address with `ipconfig` on Windows (*IPv4 Address* of the Wi-Fi adapter) or
   `ip addr` on Linux. The map needs the base map built with that address and the web app started
   with `pnpm dev:web --host` instead of step 4.

   The app runs as a development build, not in Expo Go. Install it on the phone once, as *Run the
   mobile app* in the [development guide](development.md) explains. Then, from `frontend/`:

   ```bash
   pnpm dev:mobile
   ```

   Open the UrbanPulse app on the phone. If it cannot reach your machine, see *Build, install and
   run* in the development guide.

### Quick Sign-In

To change role with one click instead of typing the email and the password, turn on the quick
sign-in (#167). It is off by default and only exists for the demo and for development: the buttons
call the normal sign-in with the users of the table above, and the API treats them as any other
sign-in. Both apps read these variables when they start, so set them before `pnpm dev:web` and
`pnpm dev:mobile`, or restart those that are already running.

1. In step 4, before `pnpm dev:web`, create `frontend/apps/web/.env` from
   `frontend/apps/web/.env.example` and set the same password as `DEMO_PASSWORD` in
   `backend/.env`:

   ```properties
   VITE_QUICK_SIGN_IN=true
   VITE_DEMO_PASSWORD=choose-a-password
   ```

2. In step 5, after copying `.env.example` to `frontend/apps/mobile/.env` (the copy turns the flag
   off), set the same two values with the Expo names in that file:

   ```properties
   EXPO_PUBLIC_QUICK_SIGN_IN=true
   EXPO_PUBLIC_DEMO_PASSWORD=choose-a-password
   ```

The web sign-in then shows one button per staff role (*Operator*, *Technician*, *Administrator*,
*Analyst*), and the mobile sign-in a button for the demo citizen. With the flag on and no
password, they show which variable is missing instead of the buttons.

**Never deploy a build made with the flag on.** Vite and Expo copy these variables into the
JavaScript they build, so anyone who opens that build can read the password. With the flag off, a
production build (`pnpm build`) leaves the password out. The development servers, though, serve
the variables of `.env` as they are, so leave the password empty when you do not use the quick
sign-in. A wrong password, or a database without the demo dataset, gives the normal sign-in error.

## Walk Through It

| # | Who and where | What to do | What it shows | Needs |
| --- | --- | --- | --- | --- |
| 1 | Operator, web | Sign in as `demo.operator@example.com`. | Each role lands on its own screen: the operator on the inbox. | #39 |
| 2 | Operator, web | Look at the inbox, then choose *Clear filters*. | It opens on the three `REPORTED` incidents, waiting for a review; without filters, the fifteen, newest first, with category, status and priority. | #46, #183 |
| 3 | Operator, web | Open *Street light flickering on Calle Larios*. | An incident without category; address, neighbourhood and district are "not available", never empty or invented. | #46 |
| 4 | Operator, web | Open *Leaking fountain in Plaza de la Constitución*. | The full history from the report to the reopening, with who made each change and the internal reasons (resolution notes, reopening). | #46 |
| 5 | Citizen, phone | Sign in as `demo.citizen@example.com`. On the *New* tab, allow the location when the phone asks, write a title and a description, for example a bin knocked over on Calle Granada, and tap *Submit*. | Reporting from the phone with the device's position and accuracy; the app confirms with "Incident reported". | #43 |
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
