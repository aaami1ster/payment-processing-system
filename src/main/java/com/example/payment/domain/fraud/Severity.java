package com.example.payment.domain.fraud;

/**
 * Per-rule severity. Aggregation precedence: {@link #DECLINE} &gt; {@link #FLAG} &gt; {@link #ALLOW}.
 */
public enum Severity {
    ALLOW,
    FLAG,
    DECLINE
}
