package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByEmailIgnoreCase(String email);
    
	boolean existsByEmailIgnoreCase(String email);
}