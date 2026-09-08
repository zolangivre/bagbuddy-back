package com.bagbuddy.userservice.repository;

import com.bagbuddy.userservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findBySub(String sub);
}