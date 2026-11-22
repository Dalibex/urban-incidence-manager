# 🎬 Demo Script

How to start UrbanPulse as a test system, with sample data that simulates real use, and walk through
it. The demo runs the same code as any other environment; only the `demo` profile adds the data
(#54).

> [!NOTE]
> **For** whoever shows UrbanPulse to an audience. Every step of the walk-through names the issue
> that delivers it. What each screen does beyond the demo is in the [user guide](user-guide.md).

## 📑 Contents

- **[Demo Data](#-demo-data)**: the demo users and the incidents in every status.
- **[Start the Demo](#-start-the-demo)**: on the deployment or in development, and the quick sign-in.
- **[Walk Through It](#-walk-through-it)**: the steps to show, role by role.
- **[Repeat or End the Demo](#-repeat-or-end-the-demo)**: start again from the same data, or go back to development.

## 👥 Demo Data

Five users, one per role, and fifteen incidents in Málaga reported by the citizen over the last two
weeks, so that every status of the lifecycle has an example, each with a history allowed by
[ADR-0020](adr/0020-incident-lifecycle-transitions.md).

| Email | Name | Role |
| --- | --- | --- |
| `demo.citizen@example.com` | Ana Fernández | `CITIZEN` |
| `demo.operator@example.com` | Raúl Ortega | `MUNICIPAL_OPERATOR` |
| `demo.technician@example.com` | Sergio Molina | `TECHNICIAN` |
| `demo.admin@example.com` | Isabel Romero | `ADMINISTRATOR` |
| `demo.analyst@example.com` | Diego Herrera | `ANALYST` |

Every user signs in with the password in `DEMO_PASSWORD`. It is never written in the repository.

The four staff users also need the second factor ([security guide](security.md#-second-factor)).
Enrol them before the demo: sign in once with each one on the web, scan the QR code with an
authenticator app on the presenter's phone (Google Authenticator, Microsoft Authenticator, Aegis…)
and type the code it shows. From then on each sign-in asks for the current code of that account.
On a database created again (`docker compose down -v`) the accounts have to enrol again. On a
developer's machine, `MFA_REQUIRED_ROLES=` (empty) in `backend/.env` turns the second factor off;
never in a deployment.

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

## 🚀 Start the Demo

There are two ways. Use the first to show the system as it is deployed; use the second while you
develop, when you want to change role with one click.

### On the deployment (recommended)

The whole system in Docker, behind nginx. Docker and Git run the system. The citizen's phone also
needs the mobile setup of the [development guide](development.md#run-the-mobile-app) (Node 24, pnpm
and the development build installed on an Android phone), because the app loads its code from
Metro on your machine.

1. Follow *Quick Start* in the [README](../README.md#-quick-start): in the root `.env`, set
   `SPRING_PROFILES_ACTIVE=demo` and a `DEMO_PASSWORD`, then start the system and build the base
   map. To use the phone, set `TILES_BASE_URL=http://<your address>:8000/tiles` before building the
   map. Find your address in the local network with `ipconfig` on Windows (*IPv4 Address*) or
   `ip addr` on Linux.
2. Open `http://<your address>:8000` and sign in with the demo users, typing the email and
   the `DEMO_PASSWORD`. The deployed web app never has the quick sign-in.
3. For the citizen, point the mobile app at the deployment, as *Which `.env` each way of running
   reads* in the [development guide](development.md) shows, and start Metro from `frontend/` with
   `pnpm dev:mobile --clear`. The app runs as a development build, not in Expo Go: install it on the
   phone once, as *Run the mobile app* in the same guide explains.

If something does not answer, see *Troubleshooting* in the [development guide](development.md).

### In development

The backend from the terminal and the clients from their development servers, as in the
[development guide](development.md), with the `demo` profile instead of `dev`:

1. Set a `DEMO_PASSWORD` in `backend/.env`, without quotes. Without it the backend does not start.
2. Start from an empty database: one that loaded another data profile, such as `dev`, does not
   start with `demo`. From `backend/`, `docker compose --profile app down -v`.
3. Start the backend with the `demo` profile: from `backend/`,
   `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` (Windows PowerShell:
   `./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"`).
4. Start the web app with `pnpm dev:web` from `frontend/`, or `pnpm dev:web --host` to use the
   phone, and the mobile app as *Run the mobile app* in the development guide explains.

### Quick Sign-In

To change role with one click instead of typing the email and the password, turn on the quick
sign-in (#167). It is off by default and only exists for the demo and for development: the buttons
call the normal sign-in with the users of the table above, and the API treats them as any other
sign-in, so a staff button then asks for the code of the second factor too. Both apps read these
variables when they start, so set them before `pnpm dev:web` and `pnpm dev:mobile`, or restart
those that are already running.

It only works *In development*: the web image of the deployment is built without any `.env`.

1. Before `pnpm dev:web`, create `frontend/apps/web/.env` from
   `frontend/apps/web/.env.example` and set the same password as `DEMO_PASSWORD` in
   `backend/.env`:

   ```properties
   VITE_QUICK_SIGN_IN=true
   VITE_DEMO_PASSWORD=choose-a-password
   ```

2. In `frontend/apps/mobile/.env` (a fresh copy of `.env.example` turns the flag off), set the same
   two values with the Expo names:

   ```properties
   EXPO_PUBLIC_QUICK_SIGN_IN=true
   EXPO_PUBLIC_DEMO_PASSWORD=choose-a-password
   ```

The web sign-in then shows one button per staff role (*Operator*, *Technician*, *Administrator*,
*Analyst*), and the mobile sign-in a button for the demo citizen. With the flag on and no
password, they show which variable is missing instead of the buttons. A wrong password, or a
database without the demo dataset, gives the normal sign-in error.

> [!CAUTION]
> **Never deploy a build made with the flag on.** Vite and Expo copy these variables into the
> JavaScript they build, so anyone who opens that build can read the password. With the flag off, a
> production build (`pnpm build`) leaves the password out. The development servers, though, serve
> the variables of `.env` as they are, so leave the password empty when you do not use the quick
> sign-in.

## 🚶 Walk Through It

| # | Who and where | What to do | What it shows | Needs |
| --- | --- | --- | --- | --- |
| 1 | Operator, web | Sign in as `demo.operator@example.com` and type the code of the authenticator app. | Staff confirm the sign-in with a second factor; each role lands on its own screen: the operator on the inbox. | #39, #57 |
| 2 | Operator, web | Look at the inbox, then choose *Clear filters*. | It opens on the three `REPORTED` incidents, waiting for a review; without filters, the fifteen, newest first, with category, status and priority. | #46, #183 |
| 3 | Operator, web | Open *Street light flickering on Calle Larios*. | An incident without category; address, neighbourhood and district are "not available", never empty or invented. | #46 |
| 4 | Operator, web | Open *Leaking fountain in Plaza de la Constitución*. | The full history from the report to the reopening, with who made each change and the internal reasons (resolution notes, reopening). | #46 |
| 5 | Citizen, phone | Sign in as `demo.citizen@example.com`. On the *New* tab, allow the location when the phone asks, write a title and a description, for example a bin knocked over on Calle Granada, and tap *Submit*. | Reporting from the phone with the device's position and accuracy; the app confirms with "Incident reported". | #43 |
| 6 | Operator, web | Open *Inbox* again from the menu, not with the browser's reload, which signs you out, and open the new incident. | The report is first in the list, with its position; the address is "not available" until the context is resolved, and the report was accepted anyway. | #43, #46 |
| 7 | Citizen, phone | Open *My reports*, then tap *Closed*. | It opens on *Open*, with the thirteen open incidents of the citizen and their status; *Closed* shows the other three, rejected or closed. | #45 |
| 8 | Citizen, phone | On *Closed*, open *Loud music from a bar terrace*; then go back, tap *Open* and open *Leaking fountain in Plaza de la Constitución*. | The rejection reason, which the citizen sees; the history of the fountain without the internal resolution and reopening notes. | #45 |
| 9 | Citizen, web | Sign out on the web and sign in as `demo.citizen@example.com`, then open `/inbox`. | The web sends citizens to the mobile app: the inbox is staff only, and the API checks it again. | #39 |
| 10 | Technician, administrator, analyst, web | Sign in with each of them, with the code of each account. | Each lands on its own screen (work queue, users, reports). | #39, #57 |

## 🔁 Repeat or End the Demo

The incident created in step 5 stays in the database when you restart. To repeat the demo from the
same starting point, reset the database:

- **On the deployment:** from the repository root, `docker compose down -v`, then follow *Quick
  Start* again. `-v` deletes everything of that deployment, the base map included, so build it
  again; on a deployment with real data, back up first (*Reset* in the
  [deployment guide](deployment.md)).
- **In development:** stop the backend, reset the database (step 2) and start it again (step 3).

`docker compose down`, without `-v`, stops the deployment and keeps its data. To go back to
development, reset the database and start with the `dev` profile (see *Development Dataset* in the
development guide).
