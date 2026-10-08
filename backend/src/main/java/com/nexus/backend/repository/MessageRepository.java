package com.nexus.backend.repository;

import com.nexus.backend.domain.message.Message;
import com.nexus.backend.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByRecipientAndDeletedByRecipientFalseOrderByIdDesc(User recipient);

    List<Message> findBySenderAndDeletedBySenderFalseOrderByIdDesc(User sender);

    long countByRecipientAndReadFalseAndDeletedByRecipientFalse(User recipient);
}
