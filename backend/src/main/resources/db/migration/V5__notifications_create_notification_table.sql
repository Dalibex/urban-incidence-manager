-- sent_at stays empty until the notification is actually delivered or attempted.
CREATE TABLE notification (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     uuid        NOT NULL,
    recipient_id    uuid        NOT NULL,
    message         varchar     NOT NULL,
    channel         varchar     NOT NULL,
    delivery_status varchar     NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT fk_notification_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL', 'SMS', 'APP')),
    CONSTRAINT ck_notification_delivery_status CHECK (delivery_status IN ('PENDING', 'DELIVERED', 'FAILED'))
);
