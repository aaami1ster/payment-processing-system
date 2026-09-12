package com.example.payment.api.controller;

import com.example.payment.api.request.ProcessTransactionRequest;
import com.example.payment.api.response.ApiResponse;
import com.example.payment.api.response.TransactionResponse;
import com.example.payment.common.web.RequestIdFilter;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.service.command.ProcessTransactionHandler;
import com.example.payment.service.mapper.TransactionMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final ProcessTransactionHandler processTransactionHandler;
    private final TransactionMapper transactionMapper;

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> process(
            @Valid @RequestBody ProcessTransactionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        Transaction transaction = processTransactionHandler.handle(
                request.userId(),
                request.merchantId(),
                request.amount(),
                request.category(),
                idempotencyKey);
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        transactionMapper.toResponse(transaction),
                        "Transaction processed",
                        requestId));
    }
}
