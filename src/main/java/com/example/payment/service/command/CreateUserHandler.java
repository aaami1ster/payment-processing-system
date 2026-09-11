package com.example.payment.service.command;

import com.example.payment.common.exception.DuplicateEmailException;
import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.logging.LogFactory;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateUserHandler {

    private static final Logger log = LogFactory.getLogger(CreateUserHandler.class);

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;
    private final Clock clock;

    public CreateUserHandler(UserJpaRepository userRepository, UserMapper userMapper, Clock clock) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Transactional
    public User handle(String email, KycStatus kycStatus) {
        if (email == null || email.isBlank()) {
            throw new InvalidRequestException("email", "email is required");
        }
        String normalizedEmail = email.trim();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateEmailException(normalizedEmail);
        }

        Instant now = Instant.now(clock);
        KycStatus status = kycStatus != null ? kycStatus : KycStatus.PENDING;
        User user = User.createNew(UUID.randomUUID(), normalizedEmail, status, now);

        try {
            userRepository.save(userMapper.toEntity(user));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException(normalizedEmail);
        }

        log.info("Created user id={} email={}", user.getId(), user.getEmail());
        return user;
    }
}
