package com.example.payment.api.controller;

import com.example.payment.api.request.ProcessTransactionRequest;
import com.example.payment.api.response.ApiResponse;
import com.example.payment.api.response.TransactionPageResponse;
import com.example.payment.api.response.TransactionResponse;
import com.example.payment.common.web.RequestIdFilter;
import com.example.payment.domain.transaction.Transaction;
import com.example.payment.domain.transaction.TransactionStatus;
import com.example.payment.service.command.ProcessTransactionHandler;
import com.example.payment.service.mapper.TransactionMapper;
import com.example.payment.service.query.GetTransactionHandler;
import com.example.payment.service.query.ListTransactionsHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Payment authorization and lookup")
public class TransactionController {

    private final ProcessTransactionHandler processTransactionHandler;
    private final GetTransactionHandler getTransactionHandler;
    private final ListTransactionsHandler listTransactionsHandler;
    private final TransactionMapper transactionMapper;

    @PostMapping
    @Operation(summary = "Process a payment transaction")
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

    @GetMapping({"", "/"})
    @Operation(summary = "List transactions for a user (cursor page)")
    public ResponseEntity<ApiResponse<TransactionPageResponse>> list(
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "status", required = false) TransactionStatus status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest httpRequest) {
        ListTransactionsHandler.Result page =
                listTransactionsHandler.handle(userId, from, to, status, cursor, limit);
        List<TransactionResponse> items =
                page.items().stream().map(transactionMapper::toResponse).toList();
        TransactionPageResponse data =
                new TransactionPageResponse(items, page.nextCursor(), page.hasMore());
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(data, "Transactions listed", requestId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a stored transaction by id")
    public ResponseEntity<ApiResponse<TransactionResponse>> get(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {
        Transaction transaction = getTransactionHandler.handle(id);
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(
                transactionMapper.toResponse(transaction),
                "Transaction retrieved",
                requestId));
    }
}
