package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {
    // Get all chats for the sidebar, ordered by latest activity
    List<AiChatSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
}