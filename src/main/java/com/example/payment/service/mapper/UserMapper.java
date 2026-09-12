package com.example.payment.service.mapper;

import com.example.payment.api.response.UserResponse;
import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.domain.user.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toDomain(UserEntity entity) {
        return new User(
                entity.getId(),
                entity.getEmail(),
                entity.getKycStatus(),
                entity.getPreApprovedTransactionLimit(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public UserEntity toEntity(User user) {
        return new UserEntity(
                user.getId(),
                user.getEmail(),
                user.getKycStatus(),
                user.getPreApprovedTransactionLimit(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    public void applyDomain(User user, UserEntity entity) {
        entity.setEmail(user.getEmail());
        entity.setKycStatus(user.getKycStatus());
        entity.setPreApprovedTransactionLimit(user.getPreApprovedTransactionLimit());
        entity.setUpdatedAt(user.getUpdatedAt());
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getKycStatus(),
                user.getPreApprovedTransactionLimit(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
