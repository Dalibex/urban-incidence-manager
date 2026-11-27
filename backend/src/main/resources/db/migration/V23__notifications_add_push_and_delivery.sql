-- Notifications of the status changes of a citizen's incidents (#373, ADR-0031). The citizen
-- chooses the channels: email is on from sign-up, also for the existing accounts, and push starts
-- off because it needs the phone's permission and a registered device.
ALTER TABLE users
    ADD COLUMN notify_by_push  boolean NOT NULL DEFAULT false,
    ADD COLUMN notify_by_email boolean NOT NULL DEFAULT true;

-- No code of the application ever wrote a notification: the only rows come from the datasets,
-- which load them again in the new shape. A row could not be tied to its status change anyway.
DELETE FROM notification;

-- One row per status change, recipient and channel, created in the transaction of the change and
-- sent after it by the delivery job: attempts and next_attempt_at drive its retries, and sent_at is
-- the instant of the final outcome (DELIVERED or FAILED). The unique key makes processing the same
-- change twice harmless. SMS and APP are gone (ADR-0031).
ALTER TABLE notification
    ADD COLUMN status_change_id uuid        NOT NULL,
    ADD COLUMN attempts         smallint    NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at  timestamptz NOT NULL,
    ADD COLUMN created_at       timestamptz NOT NULL,
    ADD CONSTRAINT fk_notification_status_change
        FOREIGN KEY (status_change_id) REFERENCES status_change (id),
    ADD CONSTRAINT uq_notification_change_recipient_channel
        UNIQUE (status_change_id, recipient_id, channel),
    DROP CONSTRAINT ck_notification_channel,
    ADD CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL', 'PUSH'));

-- The delivery job reads only the pending rows that are due.
CREATE INDEX ix_notification_pending_due ON notification (next_attempt_at)
    WHERE delivery_status = 'PENDING';

-- An Expo push token of a citizen's phone. A token belongs to one device, so it is unique: when
-- another account signs in on that phone, the token moves to it. last_registered_at is renewed at
-- every registration; a token not renewed for 60 days is deleted.
-- The keys of the new tables are bigint; the foreign keys to the existing tables stay uuid until
-- those tables change their keys (#369).
CREATE TABLE push_token (
    id                 bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            uuid         NOT NULL,
    token              varchar(255) NOT NULL,
    created_at         timestamptz  NOT NULL,
    last_registered_at timestamptz  NOT NULL,
    CONSTRAINT uq_push_token_token UNIQUE (token),
    CONSTRAINT fk_push_token_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_push_token_user_id ON push_token (user_id);
CREATE INDEX ix_push_token_last_registered_at ON push_token (last_registered_at);

-- One message of a push notification to one device, with the ticket Expo answered. The token is
-- kept by value, because its push_token row may be deleted afterwards. ACCEPTED waits for Expo's
-- receipt, which ends it as DELIVERED or FAILED.
CREATE TABLE push_delivery (
    id                 bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id    uuid         NOT NULL,
    token              varchar(255) NOT NULL,
    ticket_id          varchar,
    status             varchar      NOT NULL,
    error              varchar,
    sent_at            timestamptz  NOT NULL,
    receipt_checked_at timestamptz,
    CONSTRAINT fk_push_delivery_notification
        FOREIGN KEY (notification_id) REFERENCES notification (id),
    CONSTRAINT ck_push_delivery_status CHECK (status IN ('ACCEPTED', 'DELIVERED', 'FAILED'))
);

-- The receipts pass reads only the tickets that still wait for their receipt.
CREATE INDEX ix_push_delivery_accepted ON push_delivery (sent_at) WHERE status = 'ACCEPTED';

-- The rows of a notification are read, and deleted with it, by its id.
CREATE INDEX ix_push_delivery_notification ON push_delivery (notification_id);
