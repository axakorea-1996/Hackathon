package com.axakorea.subscription.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, length = 20)
    private String plateNumber;             // 차량번호

    @Column(length = 50)
    private String modelName;              // 차량 모델

    @Column(length = 20)
    private String grade;                  // 등급

    @Column(length = 30)
    private String driveType;             // 운행형태

    @CreationTimestamp
    private LocalDateTime createdAt;
}
