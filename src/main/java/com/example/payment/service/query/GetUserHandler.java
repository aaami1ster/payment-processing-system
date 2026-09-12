package com.example.payment.service.query;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.UserNotFoundException;
import com.example.payment.data.postgres.repository.UserJpaRepository;
import com.example.payment.domain.user.User;
import com.example.payment.service.mapper.UserMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetUserHandler {

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;

    public GetUserHandler(UserJpaRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public User handle(UUID userId) {
        if (userId == null) {
            throw new InvalidRequestException("id", "user id is required");
        }
        return userRepository.findById(userId)
                .map(userMapper::toDomain)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
