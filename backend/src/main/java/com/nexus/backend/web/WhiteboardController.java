package com.nexus.backend.web;

import com.nexus.backend.dto.WhiteboardNoteRequest;
import com.nexus.backend.dto.WhiteboardNoteResponse;
import com.nexus.backend.dto.WhiteboardNoteUpdateRequest;
import com.nexus.backend.security.WorkspaceWrite;
import com.nexus.backend.service.WhiteboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/whiteboard")
public class WhiteboardController {

    private final WhiteboardService whiteboardService;

    public WhiteboardController(WhiteboardService whiteboardService) {
        this.whiteboardService = whiteboardService;
    }

    @GetMapping("/notes")
    public ResponseEntity<List<WhiteboardNoteResponse>> listNotes(@RequestParam(required = false) String board) {
        return ResponseEntity.ok(whiteboardService.list(board));
    }

    @WorkspaceWrite
    @PostMapping("/notes")
    public ResponseEntity<WhiteboardNoteResponse> createNote(
        @Valid @RequestBody WhiteboardNoteRequest request,
        @RequestParam(required = false) String board
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(whiteboardService.create(request, board));
    }

    @WorkspaceWrite
    @PatchMapping("/notes/{id}")
    public ResponseEntity<WhiteboardNoteResponse> updateNote(
        @PathVariable Long id,
        @RequestBody WhiteboardNoteUpdateRequest request
    ) {
        return ResponseEntity.ok(whiteboardService.update(id, request));
    }

    @WorkspaceWrite
    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long id) {
        whiteboardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
