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
  delivery statuses `PENDING`, `DELIVERED` and `FAILED`, but no link to the status change, no
  attempt count and no provider id.

Which providers deliver the citizen's notifications, through which channels, and what does each
channel need from the backend and the app?

## Decision

Proposed: the `notifications` module ([ADR-0010](0010-modular-monolith.md)) delivers **push
notifications through the Expo push service** and **email through Brevo** (as ADR-0019 proposed),
and **sends no SMS**.

- **Channels:** `PUSH` (new), `EMAIL` and `APP` (the row itself, read by the apps through the API,
  as in ADR-0019). The migration of #373 adds `PUSH` to the channel check. Whether it also removes
  `SMS` or leaves it unused is decided there.
- **Which rows, once:** a change the citizen may see creates one `notification` row per channel the
  citizen has turned on: `APP` always, `EMAIL` and `PUSH` when on, and `PUSH` only if the citizen
  has at least one registered device. The migration of #373 adds the status change the row comes
  from and a unique key on (status change, recipient, channel), so the same change processed twice
  creates no second row.
- **Delivery, as in ADR-0019:**
  - The status change writes the rows as `PENDING` in its own transaction.
  - A scheduled job of `notifications` sends them after the commit, through one sender per
    channel. A slow or failing provider never delays or rolls back the incident.
  - **Retries:** a transient failure (no answer, a network error, `429` or `5xx`) leaves the row
    `PENDING` for a later attempt, with growing waits, as Expo asks: the second attempt one minute
    after the first, the third five minutes after the second. After the third failed attempt the
    row is `FAILED`. Any other failure, such as a `4xx` that is not `429`, is `FAILED` at once,
    because it would fail again. The migration of #373 adds the attempt count and the time of the
    next attempt to the row.
  - A crash after the provider accepted a message and before the row is updated sends it again on
    the next run. The citizen may then get it twice; Expo itself also delivers at least once.
- **Push through the Expo push service:**
  - **Calls:** the backend posts to `https://exp.host/--/api/v2/push/send` with Spring's HTTP
    client, up to 100 messages per request, so no SDK is needed.
  - **Credentials:** the requests carry an Expo access token with Expo's *enhanced security* turned
    on, so a leaked push token alone cannot be used to message a citizen.
  - **One delivery per device:** a `PUSH` row is sent to every registered device of the citizen.
    Each message is a *push delivery* of a new table of #373: the row, the device's token, Expo's
    ticket id and its own status.
  - **Tickets:** Expo answers each message with a *ticket*. Status `ok` means Expo accepted it. An
    error ticket fails that delivery and is not retried. `DeviceNotRegistered` also deletes the
    token, since Expo asks not to send to it again.
  - **The row's status:** `DELIVERED`, with the meaning ADR-0019 gives it (accepted by the
    provider, not read), when at least one delivery has an `ok` ticket; `FAILED` when none does.
    The status is final once set.
  - **Receipts:** a later run reads the *receipts* of the `ok` tickets: Expo recommends 15 minutes
    after sending, and keeps them 24 hours. A receipt with an error marks that delivery `FAILED`
    and, for `DeviceNotRegistered`, deletes the token. It does not change the row's status, which
    records whether the notification left UrbanPulse; the deliveries record what happened next.
  - **Push tokens:**
    - The app asks for the permission only when the citizen turns push on in *Profile*. Android 13
      and later shows its own permission prompt for notifications. The app then sends its Expo push
      token to the API, which accepts only the `ExponentPushToken[…]` format.
    - A token belongs to one device and one user: the table of #373 has numeric keys (#369) and a
      unique token. Registering a token already held by another user moves it to the new user, so
      a shared phone never shows the notifications of the previous citizen.
    - Signing out on the phone, or turning push off, deletes the token of that device.
  - **Android delivery:** on Android, Expo delivers through Firebase Cloud Messaging. The team needs:
    - an Expo account shared by the team, with the app's project, whose `projectId` goes in the
      app's configuration (the token is requested for that project);
    - a Firebase project, with its FCM V1 key uploaded to that Expo project.

    The Android build may also need the Firebase configuration file (`google-services.json`); #373
    checks it against Expo's FCM guide.
  - **iOS:** iOS push needs a paid Apple developer account, so it stays out until the team has one.
    The sender needs no change for it.
- **Email:** Brevo, as in ADR-0019: transactional API, authenticated sending domain, templates in
  the code base.
- **No SMS:** Twilio and the per-message cost go away; the `users` table needs no phone number.
- **What a notification says:** only what the citizen may see of their incident (ADR-0020,
  *Citizen sees*): its title, the new status and the message or reason addressed to the citizen.
  It never carries internal notes, staff names or restricted data (`AGENTS.md`, section 3). A push
  carries the incident's public id for the app to open it, never an internal key (#369). Expo
  keeps a notification only in memory and in queues, but its push service runs in the United
  States, and on Android the message also goes through Google's FCM, so the push text stays this
  short.
- **Consent:** a citizen gets push or email only for their own incidents, and only for the channels
  they turned on (#373).
- **Configuration** ([ADR-0011](0011-deployment-12factor.md)), never committed:
  - `EXPO_ACCESS_TOKEN` and `NOTIFICATIONS_PUSH_ENABLED`;
  - `BREVO_API_KEY`, `MAIL_FROM` and `NOTIFICATIONS_EMAIL_ENABLED`, as in ADR-0019;
  - the Twilio variables and `NOTIFICATIONS_SMS_ENABLED` of ADR-0019 are not created.
- **Network:** the `egress-proxy` list gains the host of the Expo push service (`exp.host`) and
  Brevo's API host (`api.brevo.com`), and nothing else.
- **Local and test profiles** use the logging sender of ADR-0019. Tests and CI never call Expo or
  Brevo, and need no keys.

## Considered Options

**Push:**

- **The Expo push service.** One HTTPS API for Android and iOS, with no cost to send, from the Expo
  stack the app already uses; the app gets its token from the `expo-notifications` library. It
  needs an Expo project for the app. Selected.
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
  - **Two more accounts and two more secrets:** an Expo account for the team with the app's
    project (the app has none yet: no `projectId` in its configuration, built on each machine), a
    Firebase project for Android delivery, the Expo access token and the Brevo key. This differs
    from #397's assumption that push needs nothing beyond Expo.
  - **No iOS push** until the team has a paid Apple developer account.
  - **Push text leaves the EU** through Expo and, on Android, Google's FCM. The text is kept to the
    citizen-visible minimum, and the privacy notice must name both.
  - **A second step per push:** reading the receipts is a second pass of the job, and a receipt
    error is recorded on the delivery, not on the notification.
  - **At least once:** Expo, and a crash between sending and recording, may deliver a notification
    twice. Our side still creates one row per change and channel (#373, failure case "processed
    twice").
  - **New migrations in #373:** the push token table, the push delivery table, the new channel,
    and on `notification` the status change, the unique key, the attempt count and the next
    attempt time. The `egress-proxy` list also changes.
  - The C4 model shows the Expo push service instead of Twilio.

## How it will be evaluated

- **Metric:** the notifications per channel and per delivery status in the tests of #373 (#376),
  with Expo and Brevo replaced by test doubles, and on a real Android phone.
- **Threshold:**
  - every notification ends `DELIVERED` or `FAILED`, never left `PENDING` after its retries;
  - with the push double answering `503`, the status change is saved and answered without calling
    it (the call happens in the scheduled job), and the row ends `FAILED` after its third attempt
    (#376, T3);
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
