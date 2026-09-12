package com.example.payment.service.command;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpdateUserHandler {

    private static final Logger log = LogFactory.getLogger(UpdateUserHandler.class);

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;
    private final Clock clock;

    public UpdateUserHandler(UserJpaRepository userRepository, UserMapper userMapper, Clock clock) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Transactional
    public User handle(UUID userId, KycStatus kycStatus, BigDecimal preApprovedTransactionLimit, boolean updateLimit) {
        if (userId == null) {
            throw new InvalidRequestException("id", "user id is required");
        }
        if (updateLimit
                && preApprovedTransactionLimit != null
                && preApprovedTransactionLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidRequestException(
                    "preApprovedTransactionLimit", "preApprovedTransactionLimit must be greater than 0");
        }

        UserEntity entity = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        User user = userMapper.toDomain(entity);
        Instant now = Instant.now(clock);

        if (kycStatus != null) {
            user = user.withKycStatus(kycStatus, now);
        }
        if (updateLimit) {
            user = user.withPreApprovedTransactionLimit(preApprovedTransactionLimit, now);
        }
        if (kycStatus == null && !updateLimit) {
            return user;
        }

        userMapper.applyDomain(user, entity);
        userRepository.save(entity);
        log.info("Updated user id={}", userId);
        return user;
    }
}
