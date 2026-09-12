package com.example.payment.api.response;

import java.util.List;

public record TransactionPageResponse(
        List<TransactionResponse> items,
        String nextCursor,
        boolean hasMore
) {}
