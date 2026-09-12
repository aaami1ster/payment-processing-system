package com.example.payment.service.query;

import com.example.payment.common.exception.InvalidRequestException;
import com.example.payment.common.exception.TransactionNotFoundException;
import com.example.payment.data.postgres.repository.TransactionJpaRepository;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.service.mapper.TransactionMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetTransactionHandler {

    private final TransactionJpaRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    @Transactional(readOnly = true)
    public Transaction handle(UUID transactionId) {
        if (transactionId == null) {
            throw new InvalidRequestException("id", "transaction id is required");
        }
        return transactionRepository.findById(transactionId)
                .map(transactionMapper::toDomain)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }
}
