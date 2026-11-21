# ADR-0031: Notify the citizen through Expo push notifications and email, without SMS

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-11-21
- **Amends:** [ADR-0019](0019-notification-delivery-providers.md): the push channel is added and
  the SMS channel is dropped; email through Brevo and the delivery mechanism stay as proposed there.

## Context

RF14 (Medium) asks that citizens learn about the relevant changes of their incidents through
channels they configure. #373 builds it for the citizen: push notifications and email, no SMS.
[ADR-0019](0019-notification-delivery-providers.md), still *Proposed*, chose Brevo for email and
Twilio for SMS and left push notifications to their own ADR. #373 cannot start on a decision that
contradicts it.

What the citizen app already is, and what a push channel has to fit:

- **The citizen app is an Expo app** ([ADR-0005](0005-mobile-application-technology.md)) that runs
  as a **development build**, not in Expo Go, because the base map needs a native module
  ([development guide](../development.md)). Expo Go does not receive push notifications from
  SDK 53 on; the app is on SDK 58.
- **The team builds and tests on Android.** An iPhone needs a Mac with Xcode and a paid Apple
  developer account, which the team does not have.
- **A provider must never affect the incident** (`AGENTS.md`, section 8): delivery happens after
  the commit, with bounded retries, and secrets stay out of the code
  ([ADR-0011](0011-deployment-12factor.md)).
- **In the deployment the backend has no route to the internet** except through the
  `egress-proxy`, which allows only the hosts on its list (today `api.open-meteo.com`).
- **The project has no budget.**
- **The `notification` table** (migration `V5`) has the channels `EMAIL`, `SMS` and `APP` and the
  delivery statuses `PENDING`, `DELIVERED` and `FAILED`.

Which providers deliver the citizen's notifications, through which channels, and what does each
channel need from the backend and the app?

## Decision

Proposed: the `notifications` module ([ADR-0010](0010-modular-monolith.md)) delivers **push
notifications through the Expo push service** and **email through Brevo** (as ADR-0019 proposed),
and **sends no SMS**.

- **Channels:** `PUSH` (new), `EMAIL` and `APP` (the row itself, read by the apps through the API,
  as in ADR-0019). The migration of #373 adds `PUSH` to the channel check. Whether it also removes
  `SMS` or leaves it unused is decided there.
- **Delivery, unchanged from ADR-0019:**
  - The status change writes the `notification` rows as `PENDING` in its own transaction.
  - A scheduled job of `notifications` sends them after the commit, through one sender per
    channel. A slow or failing provider never delays or rolls back the incident.
  - A row is retried a bounded number of times on transient errors, then marked `FAILED`.
- **Push through the Expo push service:**
  - **Calls:** the backend posts to `https://exp.host/--/api/v2/push/send` with Spring's HTTP
    client, up to 100 messages per request, so no SDK is needed.
  - **Credentials:** the requests carry an Expo access token with Expo's *enhanced security* turned
    on, so a leaked push token alone cannot be used to message a citizen.
  - **Delivery status:** a push *ticket* with status `ok` means that Expo accepted the message, so
    the row becomes `DELIVERED`, with the meaning ADR-0019 gives that status. On a network error,
    `429` or `5xx` the row stays `PENDING` and is retried later; Expo asks for exponential backoff
    between attempts.
  - **Receipts:** a later run reads the push *receipts*: Expo recommends 15 minutes after sending,
    and keeps them for 24 hours. A receipt with an error marks the row `FAILED`.
    `DeviceNotRegistered` also deletes that push token: Expo asks not to send to it again.
  - **Push tokens:** the app asks for the permission only when the citizen turns push on in
    *Profile*. It then sends its Expo push token to the API. The backend keeps the tokens of each
    citizen, one per device, in a new table of #373 (numeric keys, #369).
  - **Android delivery:** on Android, Expo delivers through Firebase Cloud Messaging, so the
    project needs a Firebase project and its FCM V1 key uploaded to Expo's build service (EAS).
    iOS needs a paid Apple developer account, so iOS push stays out until the team has one; the
    sender needs no change for it.
- **Email:** Brevo, as in ADR-0019: transactional API, authenticated sending domain, templates in
  the code base.
- **No SMS:** Twilio and the per-message cost go away; the `users` table needs no phone number.
- **What a notification says:** only what the citizen may see of their incident (ADR-0020,
  *Citizen sees*): its title, the new status and the message or reason addressed to the citizen.
  It never carries internal notes, staff names or restricted data (`AGENTS.md`, section 3). A push
  carries the incident id for the app to open it. Expo keeps a notification only in memory and in
  queues, but its push service runs in the United States, so the push text stays this short.
- **Consent:** a citizen gets push or email only for their own incidents, and only for the channels
  they turned on (#373). Android 13 and later also asks the user for permission to show
  notifications.
- **Configuration** ([ADR-0011](0011-deployment-12factor.md)), never committed:
  - `EXPO_ACCESS_TOKEN` and `NOTIFICATIONS_PUSH_ENABLED`;
  - `BREVO_API_KEY`, `MAIL_FROM` and `NOTIFICATIONS_EMAIL_ENABLED`, as in ADR-0019;
  - the Twilio variables and `NOTIFICATIONS_SMS_ENABLED` of ADR-0019 are not created.
- **Network:** the `egress-proxy` list gains the host of the Expo push service (`exp.host`) and
  Brevo's API host, and nothing else.
- **Local and test profiles** use the logging sender of ADR-0019. Tests and CI never call Expo or
  Brevo, and need no keys.

## Considered Options

**Push:**

- **The Expo push service.** One HTTPS API for Android and iOS, with no cost to send. It reuses the
  Expo project the app already has, and the app gets its token from the `expo-notifications`
  library. Selected.
- **Firebase Cloud Messaging and APNs directly.** No intermediary, but two integrations: the FCM
  HTTP v1 API with a service account in the backend, and APNs with its own key. It still needs the
  Firebase project that Expo needs, and more code for the same result at this volume.
- **No push, email only.** Simplest, but the citizen would not learn of a change on the phone
  they reported from, which is what RF14 is for.

**Email:**

- **Brevo**, as in ADR-0019: EU-based, a free plan with a daily limit, and a transactional API.
  Selected; no new fact changes the reasons of ADR-0019.
- **Another transactional provider** (Amazon SES, SendGrid, Mailgun, Resend): compared in
  ADR-0019.
- **SMTP of a personal or university mailbox:** no domain authentication, low sending limits and a
  personal credential in the deployment.

**SMS:**

- **Twilio**, as in ADR-0019: a cost per message, a phone number in `users`, and personal data
  leaving the EU, for a channel that #373 does not require.
- **No SMS.** Selected.

## Consequences

- **Positives:**
  - Both channels cost nothing at the project's volume. Expo states no cost for its push service
    and a limit of 600 notifications per second per project, far above the citizens' changes.
  - Push needs no SMS provider and no phone number; it reaches the phone the citizen reported
    from.
  - A provider down never affects an incident, as in ADR-0019: the scheduled job retries and
    records the outcome.
  - Tests and CI need no keys and send nothing.
- **Negatives:**
  - **Push works only in a development or release build, never in Expo Go.** The app already
    needs one for the map, but testing push needs a build with the notifications library.
  - **One more account and two more secrets:** the Firebase project for Android delivery, the Expo
    access token and the Brevo key. This differs from #397's assumption that push needs nothing
    beyond Expo.
  - **No iOS push** until the team has a paid Apple developer account.
  - **Push text leaves the EU** through Expo. The text is kept to the citizen-visible minimum, and
    the privacy notice must say so.
  - **A second step per push:** reading the receipts is a second pass of the job.
  - **Expo delivers at least once,** so a citizen may rarely see a notification twice. Our side
    still creates one row per change and channel (#373, failure case "processed twice").
  - **New migrations in #373:** the push token table and the new channel. The `egress-proxy`
    list also changes.
  - The C4 model shows the Expo push service instead of Twilio.

## How it will be evaluated

- **Metric:** the notifications per channel and per delivery status in the tests of #373 (#376),
  with Expo and Brevo replaced by test doubles, and on a real Android phone. Also the response time
  of a status change with the push provider answering `503` (#376, T3).
- **Threshold:**
  - every notification ends `DELIVERED` or `FAILED`, never left `PENDING` after its retries;
  - with the provider down, the status change is saved and answers as fast as without
    notifications;
  - a validation and a resolution arrive on a real Android phone (#376, T6).
- **Review:** after #373. Revisit if Expo changes its pricing or its push service, or if the team
  gets an Apple developer account for iOS push.

## Links Related

- [ADR-0019: Use Brevo for email and Twilio for SMS notifications](0019-notification-delivery-providers.md) (amended here)
- [ADR-0005: Mobile application technology](0005-mobile-application-technology.md)
- [ADR-0010: Modular monolith](0010-modular-monolith.md) (`notifications` module)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0020: Incident lifecycle transitions](0020-incident-lifecycle-transitions.md) (*Citizen sees*)
- #397 (this decision), #373 (the notifications), #376 (their tests), #369 (numeric keys)
- C4 model: external systems `Expo push service` and `Brevo`, used by the `notifications` component
- Expo push notifications: https://docs.expo.dev/push-notifications/overview/ and
  https://docs.expo.dev/push-notifications/sending-notifications/
