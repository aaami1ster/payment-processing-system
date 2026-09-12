package com.example.payment.data.postgres.repository;

import com.example.payment.data.postgres.entity.UserEntity;
import com.example.payment.domain.user.KycStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT u FROM UserEntity u
            WHERE (:kycStatus IS NULL OR u.kycStatus = :kycStatus)
            ORDER BY u.createdAt DESC, u.id DESC
            """)
    List<UserEntity> findFirstPage(
            @Param("kycStatus") KycStatus kycStatus,
            Pageable pageable);

    @Query("""
            SELECT u FROM UserEntity u
            WHERE (:kycStatus IS NULL OR u.kycStatus = :kycStatus)
              AND (
                    u.createdAt < :cursorCreatedAt
                    OR (u.createdAt = :cursorCreatedAt AND u.id < :cursorId)
                  )
            ORDER BY u.createdAt DESC, u.id DESC
            """)
    List<UserEntity> findPageAfter(
            @Param("kycStatus") KycStatus kycStatus,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
