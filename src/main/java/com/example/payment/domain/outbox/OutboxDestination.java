package com.example.payment.domain.outbox;

/** Outbox delivery target: Mongo audit projection or signed HTTP webhook. */
public enum OutboxDestination {
    AUDIT,
    WEBHOOK
}
