package com.axakorea.subscription.repository;

import com.axakorea.subscription.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("SELECT s FROM Subscription s " +
           "JOIN FETCH s.customer c " +
           "JOIN FETCH s.vehicle v " +
           "WHERE c.phone = :phone " +
           "ORDER BY s.createdAt DESC")
    List<Subscription> findByCustomerPhone(@Param("phone") String phone);
}
