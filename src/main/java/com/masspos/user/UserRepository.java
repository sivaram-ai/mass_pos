package com.masspos.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByRoleAndActiveTrue(UserRole role);

    long countByRoleAndActiveTrue(UserRole role);

    List<User> findAllByOrderByDisplayNameAsc();
}
