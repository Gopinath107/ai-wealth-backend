package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.AiChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {
    
    // Fetch history ONLY for a specific session
    @Query("SELECT m FROM AiChatMessage m WHERE m.session.id = :sessionId ORDER BY m.timestamp DESC")
    List<AiChatMessage> findBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);
}