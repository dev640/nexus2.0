package com.nexus.backend.web;

import com.nexus.backend.dto.SprintRequest;
import com.nexus.backend.dto.SprintResponse;
import com.nexus.backend.dto.SprintStatusRequest;
import com.nexus.backend.security.WorkspaceWrite;
import com.nexus.backend.service.SprintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sprints")
@RequiredArgsConstructor
public class SprintController {

    private final SprintService sprintService;

    @GetMapping
    public ResponseEntity<List<SprintResponse>> listSprints(@RequestParam(required = false) Long projectId) {
        // Without a projectId this is "all sprints": the frontend loads the whole
        // workspace at once. Returning an empty list here left the Sprints page blank.
        List<SprintResponse> sprints = projectId != null
            ? sprintService.findByProjectId(projectId)
            : sprintService.findAll();
        return ResponseEntity.ok(sprints);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SprintResponse> getSprint(@PathVariable Long id) {
        SprintResponse sprint = sprintService.findById(id);
        return ResponseEntity.ok(sprint);
    }

    @WorkspaceWrite
    @PostMapping
    public ResponseEntity<SprintResponse> createSprint(@Valid @RequestBody SprintRequest request) {
        SprintResponse sprint = sprintService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(sprint);
    }

    @WorkspaceWrite
    @PatchMapping("/{id}/status")
    public ResponseEntity<SprintResponse> updateSprintStatus(
        @PathVariable Long id,
        @Valid @RequestBody SprintStatusRequest request
    ) {
        SprintResponse sprint = sprintService.updateStatus(id, request.status());
        return ResponseEntity.ok(sprint);
    }

    @WorkspaceWrite
    @PutMapping("/{id}")
    public ResponseEntity<SprintResponse> updateSprint(
        @PathVariable Long id,
        @Valid @RequestBody SprintRequest request
    ) {
        return ResponseEntity.ok(sprintService.update(id, request));
    }

    @WorkspaceWrite
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSprint(@PathVariable Long id) {
        sprintService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
