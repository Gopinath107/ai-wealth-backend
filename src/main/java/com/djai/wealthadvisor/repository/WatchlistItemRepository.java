package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<WatchlistItem> findByUserIdAndInstrumentKey(Long userId, String instrumentKey);

    @Modifying
    @Transactional
    long deleteByIdAndUserId(Long id, Long userId);
}
