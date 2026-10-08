package com.nexus.backend.service;

import com.nexus.backend.domain.message.Message;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.MessageRequest;
import com.nexus.backend.dto.MessageResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.MessageRepository;
import com.nexus.backend.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Internal mail: writing to a colleague, and managing the two boxes that
 * result.
 *
 * Authorization is per-message rather than by role, because a message belongs
 * to exactly two people. Anything else asks for the row and gets a 404 rather
 * than a 403, which keeps one user's message ids from being probeable by
 * another.
 */
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public MessageService(MessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    /** Writes a message from the signed-in user to the given recipient. */
    @Transactional
    public MessageResponse send(MessageRequest request) {
        User sender = currentUser();
        User recipient = userRepository.findById(request.recipientId())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.recipientId()));

        Message message = new Message(
            sender, recipient, request.subject().trim(), request.body().trim());
        return mapToResponse(messageRepository.save(message));
    }

    /** Received mail, newest first. */
    @Transactional(readOnly = true)
    public List<MessageResponse> inbox() {
        return messageRepository.findByRecipientAndDeletedByRecipientFalseOrderByIdDesc(currentUser())
            .stream()
            .map(MessageService::mapToResponse)
            .toList();
    }

    /** Sent mail, newest first. */
    @Transactional(readOnly = true)
    public List<MessageResponse> sent() {
        return messageRepository.findBySenderAndDeletedBySenderFalseOrderByIdDesc(currentUser())
            .stream()
            .map(MessageService::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        return messageRepository.countByRecipientAndReadFalseAndDeletedByRecipientFalse(currentUser());
    }

    /** Only the recipient can mark a message read. */
    @Transactional
    public void markRead(Long id) {
        Message message = messageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", id));
        requireRecipient(message);

        if (!message.isRead()) {
            message.setRead(true);
            messageRepository.save(message);
        }
    }

    /** Removes the message from the caller's own box, leaving the other copy. */
    @Transactional
    public void delete(Long id) {
        Message message = messageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", id));
        String email = currentUserEmail();

        if (message.getRecipient().getEmail().equals(email)) {
            message.setDeletedByRecipient(true);
        } else if (message.getSender().getEmail().equals(email)) {
            message.setDeletedBySender(true);
        } else {
            throw new ResourceNotFoundException("Message", "id", id);
        }
        messageRepository.save(message);
    }

    private void requireRecipient(Message message) {
        if (!message.getRecipient().getEmail().equals(currentUserEmail())) {
            throw new ResourceNotFoundException("Message", "id", message.getId());
        }
    }

    private User currentUser() {
        String email = currentUserEmail();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private static MessageResponse mapToResponse(Message message) {
        return new MessageResponse(
            message.getId(),
            message.getSender().getId(),
            message.getSender().getName(),
            message.getRecipient().getId(),
            message.getRecipient().getName(),
            message.getSubject(),
            message.getBody(),
            message.isRead(),
            message.getCreatedAt()
        );
    }
}
