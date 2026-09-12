package com.example.payment.api.controller;

import com.example.payment.api.request.CreateUserRequest;
import com.example.payment.api.request.UpdateUserRequest;
import com.example.payment.api.response.ApiResponse;
import com.example.payment.api.response.UserPageResponse;
import com.example.payment.api.response.UserResponse;
import com.example.payment.common.web.RequestIdFilter;
import com.example.payment.domain.user.KycStatus;
import com.example.payment.domain.user.User;
import com.example.payment.service.command.CreateUserHandler;
import com.example.payment.service.command.UpdateUserHandler;
import com.example.payment.service.mapper.UserMapper;
import com.example.payment.service.query.GetUserHandler;
import com.example.payment.service.query.ListUsersHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final CreateUserHandler createUserHandler;
    private final UpdateUserHandler updateUserHandler;
    private final GetUserHandler getUserHandler;
    private final ListUsersHandler listUsersHandler;
    private final UserMapper userMapper;

    public UserController(
            CreateUserHandler createUserHandler,
            UpdateUserHandler updateUserHandler,
            GetUserHandler getUserHandler,
            ListUsersHandler listUsersHandler,
            UserMapper userMapper) {
        this.createUserHandler = createUserHandler;
        this.updateUserHandler = updateUserHandler;
        this.getUserHandler = getUserHandler;
        this.listUsersHandler = listUsersHandler;
        this.userMapper = userMapper;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest) {
        User user = createUserHandler.handle(request.email(), request.kycStatus());
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(userMapper.toResponse(user), "User created", requestId));
    }

    @GetMapping({"", "/"})
    public ResponseEntity<ApiResponse<UserPageResponse>> list(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "kycStatus", required = false) KycStatus kycStatus,
            HttpServletRequest httpRequest) {
        ListUsersHandler.Result page = listUsersHandler.handle(cursor, limit, kycStatus);
        List<UserResponse> items = page.items().stream().map(userMapper::toResponse).toList();
        UserPageResponse data = new UserPageResponse(items, page.nextCursor(), page.hasMore());
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(data, "Users listed", requestId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> get(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {
        User user = getUserHandler.handle(id);
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(userMapper.toResponse(user), "User retrieved", requestId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            HttpServletRequest httpRequest) {
        User user = updateUserHandler.handle(
                id,
                request.hasKycStatus() ? request.kycStatus() : null,
                request.preApprovedTransactionLimit(),
                request.hasPreApprovedTransactionLimit());
        String requestId = RequestIdFilter.resolve(httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(userMapper.toResponse(user), "User updated", requestId));
    }
}
