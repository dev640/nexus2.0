package com.nexus.backend.repository;

import com.nexus.backend.domain.whiteboard.WhiteboardNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhiteboardNoteRepository extends JpaRepository<WhiteboardNote, Long> {

    /**
     * A board's notes inside this company. The board is a client-supplied name,
     * so it identifies a board only within the workspace that names it.
     */
    List<WhiteboardNote> findByBoardAndCompanyIdOrderByCreatedAtAsc(String board, Long companyId);

    /** One note, only if it belongs to this company. */
    Optional<WhiteboardNote> findByIdAndCompanyId(Long id, Long companyId);

    /** Every note in this company, for the knowledge reconcile's source feed. */
    List<WhiteboardNote> findByCompanyId(Long companyId);
}
