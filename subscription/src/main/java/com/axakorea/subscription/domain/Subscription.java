package com.axakorea.subscription.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(unique = true, length = 30)
    private String policyNo;              // 증권번호 AXA-2024-000001

    @Column(length = 30)
    private String driverScope;           // 운전자 범위

    private LocalDate startDate;
    private LocalDate endDate;

    private Long premium;                 // 보험료 (원)

    @Column(length = 30)
    private String payMethod;             // 결제수단

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Builder.Default private Boolean specialMileage  = false;
    @Builder.Default private Boolean specialBlackbox = false;
    @Builder.Default private Boolean specialChild    = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum SubscriptionStatus {
        ACTIVE, CANCELLED
    }
}
