package com.example.payment.service.query;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.util.CreatedAtIdCursor;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListUsersHandler {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public record Result(List<User> items, String nextCursor, boolean hasMore) {}

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Result handle(String cursor, Integer limit, KycStatus kycStatus) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new InvalidRequestException("limit", "limit must be between 1 and " + MAX_LIMIT);
        }

        CreatedAtIdCursor.Value cursorValue = CreatedAtIdCursor.decode(cursor);
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        // Split first page vs after-cursor so PostgreSQL never sees typed NULLs in IS NULL checks.
        List<UserEntity> rows = cursorValue == null
                ? userRepository.findFirstPage(kycStatus, pageRequest)
                : userRepository.findPageAfter(
                        kycStatus, cursorValue.createdAt(), cursorValue.id(), pageRequest);

        boolean hasMore = rows.size() > pageSize;
        List<UserEntity> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<User> items = page.stream().map(userMapper::toDomain).toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            UserEntity last = page.get(page.size() - 1);
            nextCursor = CreatedAtIdCursor.encode(last.getCreatedAt(), last.getId());
        }
        return new Result(items, nextCursor, hasMore);
    }
}
