package com.nexus.backend.repository;

import com.nexus.backend.domain.message.Message;
import com.nexus.backend.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Queries for the two boxes a message produces. Every listing filters on the
 * flag belonging to the box it serves, which is what makes one person's delete
 * invisible to the other. Ordering is by id rather than createdAt: the sequence
 * is monotonic and indexed, and two messages written in the same millisecond
 * would otherwise come back in an arbitrary order.
 *
 * Each of the three queries below has a matching index in V14 — the two box
 * listings on (person, id DESC), and a partial index for the unread badge so
 * counting it does not scan mail that has already been read or cleared. The
 * migration is applied in production, so the rationale lives here rather than
 * as a comment in the .sql file: Flyway validates checksums on deploy, and an
 * edited migration stops the application from starting.
 *
 * The inherited {@code findById} is deliberately not narrowed any further — the
 * per-message authorization lives in {@link com.nexus.backend.service.MessageService},
 * which needs the row before it can decide whether the caller may touch it.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Received mail the recipient has not cleared, newest first. */
    List<Message> findByRecipientAndDeletedByRecipientFalseOrderByIdDesc(User recipient);

    /** Sent mail the sender has not cleared, newest first. */
    List<Message> findBySenderAndDeletedBySenderFalseOrderByIdDesc(User sender);

    /**
     * Unread received mail, for the Inbox badge. Mail the recipient already
     * cleared is not unread mail.
     */
    long countByRecipientAndReadFalseAndDeletedByRecipientFalse(User recipient);
}
