# ADR-0031: Notify the citizen through Expo push notifications and email, without SMS

- **Status:** Accepted
- **Decision Makers:** Francisco Jordá (PO)
- **Date:** 2026-11-25
- **Supersedes:** [ADR-0019](0019-notification-delivery-providers.md): it keeps its email provider
  (Brevo) and its delivery mechanism, adds the push channel, drops SMS and sets the retry and
  idempotency rules. Everything that still applies is stated here, so ADR-0019 is only history.

## Context

RF14 (Notifications) asks the system to notify citizens of the relevant changes of their incidents
through channels they configure. Feature #373 builds it for the citizen: push notifications and
email, no SMS. [ADR-0019](0019-notification-delivery-providers.md) chose Brevo for email and Twilio
for SMS, and left push notifications to their own ADR; it was never accepted, and this ADR
replaces it.

Notifications to staff are out of the scope of #373 and of this ADR. Staff work only in the web
app ([ADR-0006](0006-frontend-web-technology.md)), so a push would not reach them; a staff
notification, such as an incident assigned to a technician, would reuse the email sender decided
here, in its own issue.

What the citizen app already is, and what a push channel has to fit:

- **The citizen app is an Expo app** ([ADR-0005](0005-mobile-application-technology.md)) that runs
  as a **development build**, not in Expo Go, because the base map needs a native module
  ([development guide](../development.md)). Expo Go on Android does not receive push
  notifications from SDK 53 on; the app is on SDK 58.
- **The team builds and tests on Android.** An iPhone needs a Mac with Xcode and a paid Apple
  developer account, which the team does not have.
- **Sending a notification must never put the incident at risk.** A notification reports a change
  that has already happened: an operator validated, assigned or resolved an incident. If that
  change waited for Expo or Brevo to answer, a slow provider would slow down every change, and a
  provider that is down would make the change fail and roll back, so the staff member's work would
  be lost because a message could not be sent. The change must therefore only record what has to
  be sent, in its own transaction, and something else must send it afterwards, retrying a bounded
  number of times. At worst a provider failure costs a late or missing message, never an incident.
  The providers' keys stay out of the code ([ADR-0011](0011-deployment-12factor.md)).
- **In the deployment, the backend has no route to the internet** except through the
  `egress-proxy`, which allows only the hosts on its list (today `api.open-meteo.com`).
- **The project has no budget.**
- **The `notification` table** (migration `V5`) has the channels `EMAIL`, `SMS` and `APP` and the
  delivery statuses `PENDING`, `DELIVERED` and `FAILED`, but no link to the status change, no
  attempt count and no provider id.

Which providers deliver the citizen's notifications, through which channels, and what does each
channel need from the backend and the app?

## Decision

The `notifications` module ([ADR-0010](0010-modular-monolith.md)) delivers **push
notifications through the Expo push service** and **email through Brevo**, and **sends no SMS**.

- **How it is sent:** the status change writes one `notification` row per channel the citizen has
  turned on (`APP`, the row the apps read, always) as `PENDING`, in its own transaction. A unique
  key on (status change, recipient, channel) stops a change processed twice from creating a second
  row. A scheduled job of `notifications` sends the rows after the commit, every 30 seconds,
  through one sender per channel behind a common interface, so a provider is an adapter that can
  be replaced.
- **Retries:** a transient failure (no answer, a network error, `429` or `5xx`) leaves the row
  `PENDING` for a later attempt, with growing waits, as Expo asks: the second attempt one minute
  after the first, the third five minutes after the second. After the third failed attempt the row
  is `FAILED`. Any other failure, such as a `4xx` that is not `429`, is `FAILED` at once, because
  it would fail again. A crash between the provider accepting a message and the row being updated
  sends it again, so a citizen may rarely get it twice.
- **Push through the Expo push service:**
  - **Calls:** the backend posts to `https://exp.host/--/api/v2/push/send` with Spring's HTTP
    client, up to 100 messages per request, so no SDK is needed. Each message goes to one phone,
    addressed by its push token (see *Push tokens*).
  - **Credentials:** the requests carry an Expo access token with Expo's *enhanced security* turned
    on, so a leaked push token alone cannot be used to message a citizen.
  - **Answers:** Expo answers each message at once with a *ticket* and, about 15 minutes later,
    with a *receipt* that says whether it reached the phone's provider. An error in either fails
    that phone's delivery and is not retried; `DeviceNotRegistered` also deletes the token. A push
    notification is `DELIVERED` when Expo accepted it for at least one phone.
  - **Android delivery:** on Android, Expo delivers through Firebase Cloud Messaging. The team
    needs an Expo account shared by the team, with the app's project (its `projectId` goes in the
    app's configuration), and a Firebase project whose FCM V1 key is uploaded to that Expo
    project. The Android build may also need the Firebase configuration file
    (`google-services.json`); #373 checks it against Expo's FCM guide.
  - **iOS:** iOS push needs a paid Apple developer account, so it stays out until the team has one.
    The sender needs no change for it.
- **Email through Brevo:** its transactional REST API over HTTPS. The sender address is
  configured and the sending domain is authenticated in Brevo (SPF and DKIM), so the mail is not
  taken for spam. Templates live in the code base, versioned with the application. An email is
  `DELIVERED` when Brevo accepts it.
- **No SMS:** Twilio and the per-message cost go away; the `users` table needs no phone number.
- **What a notification says:** only what the citizen may see of their incident (ADR-0020,
  *Citizen sees*): its title, the new status and the message or reason addressed to the citizen.
  It never carries internal notes, staff names or restricted data. A push carries the incident's
  public id for the app to open it, never an internal key (#369). Push notifications reach phones
  in any country, but **the push text leaves the EU**: Expo's push service runs in the United
  States, and on Android the message also goes through Google's FCM. Expo keeps a notification
  only in memory and in queues; even so, the push text stays this short.
- **Consent:** a citizen gets push or email only for their own incidents, and only for the channels
  they turned on (#373).
- **Configuration** ([ADR-0011](0011-deployment-12factor.md)), never committed:
  - `EXPO_ACCESS_TOKEN` and `NOTIFICATIONS_PUSH_ENABLED`;
  - `BREVO_API_KEY`, `MAIL_FROM` and `NOTIFICATIONS_EMAIL_ENABLED`;
  - no Twilio variables and no `NOTIFICATIONS_SMS_ENABLED`.
- **Network:** the `egress-proxy` list gains the host of the Expo push service (`exp.host`) and
  Brevo's API host (`api.brevo.com`), and nothing else.
- **Local and test profiles** use a logging sender, which writes the message to the log instead of
  calling a provider. Tests and CI never call Expo or Brevo, and need no keys.

### Push tokens

A **push token** is the address of one installation of the app on one phone, such as
`ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]`. The app gets it from Expo, through the
`expo-notifications` library, once the citizen allows notifications (Android 13 and later asks
with its own prompt), and sends it to the API. The backend cannot send a push without it: every
message to Expo is addressed to a token, and Expo delivers it to that phone. So the backend keeps
the tokens of each citizen's phones, and must keep them right: a token kept under the wrong user
sends one citizen's notifications to someone else's phone. Expo explains it in
[Push notifications setup](https://docs.expo.dev/push-notifications/push-notifications-setup/).

- **One token per phone and user, not per session.** A session changes at every refresh
  ([security guide](../security.md#-sessions-and-passwords)), so a token tied to it would stop
  receiving pushes at the first refresh.
- **Registered by the app** at every sign-in and every start while signed in, when push is on;
  registering again only updates when it was last seen. The API accepts only Expo's token formats,
  and only while the session of the request is still active (checked in the database on this
  endpoint), so a request that arrives after the citizen signed out cannot bring the token back.
- **A shared phone never shows the previous citizen's notifications:** when another citizen signs
  in on it, the token moves to them, or is deleted if they have push off.
- **Deleted** when the citizen signs out or turns push off, when Expo answers that the phone is
  no longer registered (the app was uninstalled), when every session of the user ends at once (a
  password change, a disabled account or a reused refresh token), or after 60 days without being
  registered.
- **The trade-off:** a phone whose session ended without reaching the API (a sign-out without
  network, or 14 days unused) keeps receiving that citizen's pushes until someone signs in on it
  or the 60 days pass. It is accepted so that a citizen who does not open the app for weeks still
  learns that their incident was resolved. #373 tests both cases.

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

- **Brevo.** EU-based (France), a free plan with no time limit but a daily limit (about 300
  emails at the time of writing), a transactional REST API and SMTP. Selected.
- **Amazon SES.** Very cheap per email (cents per thousand) and reliable, but it needs an AWS
  account, starts in a sandbox and adds IAM setup the team uses nowhere else. The natural next
  step if the volume outgrows Brevo's plans.
- **Our own mail server** (Spring's `JavaMailSender` handing the mail to a Postfix of our own). No
  provider, but sending is the easy part: delivering well needs a domain with SPF, DKIM and DMARC,
  an IP address with a good reputation, and an open port 25, which many cloud providers block.
  Without them the mail lands in spam, and the team would operate a mail server.
- **SMTP of a personal or university mailbox (such as Gmail).** Lower sending limits than Brevo,
  no domain authentication, and a personal credential inside the deployment.

**SMS:**

- **Twilio**, which ADR-0019 had chosen: a cost per message, a phone number in `users`, and
  personal data leaving the EU, for a channel that #373 does not require.
- **No SMS.** Selected.

## Consequences

- **Positives:**
  - Both channels cost nothing at the project's volume. Expo states no cost for its push service
    and a limit of 600 notifications per second per project, far above the citizens' changes.
  - Push needs no SMS provider and no phone number; it reaches the phone the citizen reported
    from.
  - A provider down never affects an incident: the scheduled job retries and records the
    outcome.
  - **Scaling is a new adapter, not a new design.** Each channel sits behind its sender and the
    job sends asynchronously, so a peak waits as `PENDING` without touching the incidents, and a
    bigger plan or another provider replaces only the adapter. Building mail infrastructure for
    millions of incidents now would go against choosing the simplest option that works: no
    measured volume asks for it.
  - Tests and CI need no keys and send nothing.
- **Negatives:**
  - **On Android, push works only in a development or release build, never in Expo Go** (Expo Go
    on iOS still receives them, but the team has no iPhone build). The app already needs a build
    for the map, but testing push needs one with the notifications library.
  - **Two more accounts and two more secrets:** an Expo account for the team with the app's
    project (the app has none yet: no `projectId` in its configuration, built on each machine), a
    Firebase project for Android delivery, the Expo access token and the Brevo key. This differs
    from #397's assumption that push needs nothing beyond Expo.
  - **Brevo's free plan limits the emails per day** (about 300 at the time of writing). Above it,
    Brevo refuses the email: it is retried, and if the limit lasts past its attempts it ends
    `FAILED`. Above that volume the team moves to a paid plan or to Amazon SES, which costs cents
    per thousand emails.
  - **No iOS push** until the team has a paid Apple developer account.
  - **New migrations in #373:** the push token table (with the time each token was last
    registered), a table with each phone's delivery of a push, the new channel, and on
    `notification` the status change, the unique key, the attempt count and the next attempt
    time. The `egress-proxy` list also changes.
  - **A phone whose session ended without reaching the API** (signed out without network, or
    unused for 14 days) keeps getting the previous citizen's pushes for up to 60 days, unless
    another citizen signs in on it first (see *Push tokens*).
  - **Registering a push token checks the session in the database,** unlike the other requests,
    which trust the access token until it expires ([ADR-0004](0004-authentication-strategy.md)).
    It is one indexed lookup on an infrequent request.
  - The C4 model shows the Expo push service instead of Twilio.
- **When this ADR is accepted,** ADR-0019 becomes *Superseded by ADR-0031*: this ADR restates
  what still applies from it (Brevo and the delivery mechanism). The same commit changes every
  mention of either ADR as proposed:
  - the *Status* line of ADR-0019, and the `notifications` row of ADR-0010;
  - the two lists of *Proposed* ADRs in `architecture.md`;
  - the provider paragraph of `development.md` ("the three ADRs are *Proposed*");
  - RF14 and the *Notification channels* row of `requirements.md`;
  - the `Proposed` tags of the `brevo` and `expoPush` systems in `workspace.dsl`, regenerating
    the draw.io file.

## How it will be evaluated

- **Metric:** the notifications per channel and per delivery status in the tests of #373 (#376),
  with Expo and Brevo replaced by test doubles, and on a real Android phone.
- **Threshold:**
  - every notification ends `DELIVERED` or `FAILED`, never left `PENDING` after its retries;
  - with the push double answering `503`, the status change is saved and answered without calling
    it (the call happens in the scheduled job), and the row ends `FAILED` after its third attempt
    (#376, T3);
  - a validation and a resolution arrive on a real Android phone (#376, T6).
- **Review:** after #373. Revisit if Expo changes its pricing or its push service, if the team
  gets an Apple developer account for iOS push, or if the emails per day approach the daily limit
  of Brevo's plan (see *Consequences*).

## Links Related

- [ADR-0019: Use Brevo for email and Twilio for SMS notifications](0019-notification-delivery-providers.md) (superseded here)
- [ADR-0005: Mobile application technology](0005-mobile-application-technology.md)
- [ADR-0010: Modular monolith](0010-modular-monolith.md) (`notifications` module)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0020: Incident lifecycle transitions](0020-incident-lifecycle-transitions.md) (*Citizen sees*)
- #397 (this decision), #373 (the notifications), #376 (their tests), #369 (numeric keys)
- C4 model: external systems `Expo push service` and `Brevo`, used by the `notifications` component
- Expo push notifications: https://docs.expo.dev/push-notifications/overview/ and
  https://docs.expo.dev/push-notifications/sending-notifications/
