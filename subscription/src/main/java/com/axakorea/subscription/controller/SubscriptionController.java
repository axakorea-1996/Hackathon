package com.axakorea.subscription.controller;

import com.axakorea.subscription.common.response.ApiResponse;
import com.axakorea.subscription.dto.SubscriptionRequestDto;
import com.axakorea.subscription.dto.SubscriptionResponseDto;
import com.axakorea.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "AXA 청약 API")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    @Operation(summary = "청약 계약 저장")
    public ResponseEntity<ApiResponse<SubscriptionResponseDto>> create(
            @RequestBody @Valid SubscriptionRequestDto request) {


        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }

    @GetMapping("/my")
    @Operation(summary = "내 청약 목록 조회")
    public ResponseEntity<ApiResponse<List<SubscriptionResponseDto>>> getMy(
            // ⚠️ 보안 추가: phone 형식 검증
            @RequestParam
            @Pattern(regexp = "^\\d{3}-\\d{3,4}-\\d{4}$",
                    message = "전화번호 형식이 올바르지 않습니다")
            String phone) {

        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.getMyList(phone)));
    }
}