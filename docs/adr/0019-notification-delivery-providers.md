# ADR-0019: Use Brevo for email and Twilio for SMS notifications

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-25

## Context

The `notifications` module ([ADR-0010](0010-modular-monolith.md)) creates notifications when an
incident changes status, for example when it is resolved. The `notification` table
(migration `V5__notifications`) already defines three channels, `EMAIL`, `SMS` and `APP`, and a
`delivery_status` of `PENDING`, `DELIVERED` or `FAILED`.

- `APP` notifications are stored in the table and read by the web and mobile apps through the
  API, so they need no external provider.
- `EMAIL` and `SMS` have to be handed to a delivery provider, and none has been chosen. A
  Spring Boot application cannot send email or SMS reliably on its own:
  - email from an unknown server ends in spam without SPF/DKIM and a reputable sender;
  - SMS needs an agreement with carriers.

The project has no budget, so the providers must have a free tier or near-zero cost at
development volume. Recipients are citizens in Spain, so personal data (email address, phone
number, message text) leaves the system. That must be done with a provider that acts as a
data processor under the GDPR.

Which providers should deliver email and SMS notifications, and how does the
`notifications` module use them without coupling the domain to a vendor?

## Decision

We will deliver **email through Brevo's transactional email API** and **SMS through Twilio
Programmable Messaging**. Each provider sits behind a channel interface inside
`es.uma.urbanpulse.notifications`.

- **One sender per channel:**
  - The interface is `NotificationSender` with `channel()` and `send(Notification)`.
  - Adapters: `BrevoEmailSender` (Brevo REST API v3 over HTTPS), `TwilioSmsSender` (Twilio
    Java SDK) and `AppNotificationSender` (no-op: the row itself is the in-app notification).
  - Code outside `notifications` only calls `NotificationService`; it never calls Brevo or
    Twilio itself.
- **Created in the transaction, sent by a scheduled job** (plain MVC, ADR-0010):
  - The incident service calls `NotificationService` in the same transaction as the status
    change. That call only writes the `notification` row with status `PENDING`.
  - A scheduled job in `notifications` (`@Scheduled`, every 30 seconds) sends the `PENDING`
    rows through the matching sender. A slow or failing provider therefore never rolls back or
    delays the incident, and a row is never sent before its transaction has committed.
- **Delivery status:**
  - A successful API call sets `DELIVERED` and `sent_at`. It means accepted by the provider,
    not read by the user.
  - On an error the row stays `PENDING` and is retried on the next runs of the job, up to 3
    attempts, then the status is set to `FAILED`. Counting attempts needs the retry column
    mentioned under Consequences.
  - Provider webhooks for real delivery receipts are out of scope for now.
- **Email (Brevo):**
  - The sender address is configured, and the sending domain is authenticated in Brevo
    (SPF/DKIM).
  - Templates live in the code base, so messages are versioned with the application.
- **SMS (Twilio):**
  - Messages go out under an alphanumeric sender ID ("UrbanPulse"), which Spain supports.
  - SMS is **disabled by default** (`NOTIFICATIONS_SMS_ENABLED=false`) because it is the only
    channel with a per-message cost. It is enabled only for demonstrations or when the team
    decides to pay for it.
  - During development, the Twilio trial account only sends to verified numbers.
- **Configuration** follows twelve-factor ([ADR-0011](0011-deployment-12factor.md)). These
  environment variables are never committed:
  - `BREVO_API_KEY`, `MAIL_FROM`
  - `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_SENDER_ID`
  - `NOTIFICATIONS_EMAIL_ENABLED`, `NOTIFICATIONS_SMS_ENABLED`
- **Local and test profiles** use a `LoggingSender` that writes the message to the log instead
  of calling a provider. Tests and CI ([ADR-0015](0015-ci-pipeline-and-pre-commit-formatting.md))
  never need real keys.
- **Consent:**
  - A citizen receives email or SMS only for incidents they reported and only if they opted
    in. `APP` is always on.
  - The data processing agreements of Brevo and Twilio cover the GDPR requirements.

**Hypothesis and evaluation:**

- If email is sent through Brevo by the scheduled job, at least 95 % of email notifications reach
  `DELIVERED` within one minute of the status change, at zero cost at development volume.
- **Metrics:**
  - share of `DELIVERED` vs `FAILED` per channel;
  - time from the status change to `sent_at`;
  - emails per day compared with the free daily limit.
- **Review:** at the first sprint retrospective after the module sends real email.

## Considered Options

**Email:**

- **Brevo.** EU-based (France), free plan with a daily email limit and no time limit,
  transactional API and SMTP, and a Java-friendly REST API. Selected.
- **Amazon SES.** Very cheap per email and reliable, but needs an AWS account, starts in a
  sandbox, and adds IAM setup the team does not use anywhere else.
- **Resend / SendGrid / Mailgun.** Comparable developer APIs, but they are US-based and their
  free tiers have changed over time. They were not evaluated in depth.
- **SMTP of a personal or university mailbox (e.g. Gmail).** Zero setup, but sending limits,
  no domain authentication, and a personal credential inside the deployment.

**SMS:**

- **Twilio.** Widely used, a maintained Java SDK, trial mode for development, alphanumeric
  sender IDs in Spain, and pay-per-message with no monthly fee. Selected.
- **Brevo SMS.** One provider and one key for both channels, which is simpler. It is a valid
  alternative if the team prefers a single vendor. Twilio was preferred for its SDK and
  trial mode.
- **Amazon SNS.** Same AWS overhead as SES.
- **No SMS.** The `SMS` channel already exists in the schema, so it is kept, but switched off by
  default.

Brevo and Twilio were selected because each is the simplest option that works for its
channel, with free or near-free use during the project. The sender interface keeps the
choice reversible.

## Consequences

- **Positives:**
  - The `EMAIL` and `SMS` channels of the schema have a real delivery path.
  - Providers are hidden behind `NotificationSender`, so replacing one touches one adapter.
  - Sending from a scheduled job means a provider outage never affects an incident, and
    unsent rows are simply picked up again on the next run.
  - Tests and local development need no keys and send nothing.
- **Negatives:**
  - **The `users` table has no phone number**, so SMS cannot be sent yet. A new migration is
    needed: `users.phone` (nullable) plus notification preferences or consent per channel.
  - The `notification` table has no column for the provider's message id or the retry count.
    Both would help with support and debugging and need a migration if wanted.
  - Two more external dependencies and four more secrets to manage
    ([ADR-0011](0011-deployment-12factor.md)).
  - SMS costs money per message, about USD 0.09 per segment to Spain at the time of writing,
    so it must stay disabled by default.
  - Twilio is a US company, so phone numbers and message text leave the EU. This is covered
    by its data processing agreement, but it must be stated in the privacy notice.
  - Brevo's free plan has a daily sending limit. A burst of notifications above it is delayed
    or fails until the next day.
  - Push notifications to the mobile app (e.g. Expo push) are not covered and would need
    their own ADR.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md) (users and roles)
- [ADR-0010: Modular monolith](0010-modular-monolith.md) (`notifications` module)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0015: CI pipeline](0015-ci-pipeline-and-pre-commit-formatting.md)
- Migration `V5__notifications_create_notification_table.sql`
- C4 model: external systems `Brevo` and `Twilio`, used by the `notifications` component
- Brevo transactional email: https://developers.brevo.com/
- Twilio SMS pricing (Spain): https://www.twilio.com/en-us/sms/pricing/es
