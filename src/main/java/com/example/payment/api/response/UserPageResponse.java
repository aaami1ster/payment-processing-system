package com.example.payment.api.response;

import java.util.List;

public record UserPageResponse(
        List<UserResponse> items,
        String nextCursor,
        boolean hasMore
) {}
