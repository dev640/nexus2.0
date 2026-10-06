package com.nexus.backend.web;

import com.nexus.backend.dto.ProjectRequest;
import com.nexus.backend.dto.ProjectResponse;
import com.nexus.backend.security.WorkspaceWrite;
import com.nexus.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects() {
        List<ProjectResponse> projects = projectService.findAll();
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable Long id) {
        ProjectResponse project = projectService.findById(id);
        return ResponseEntity.ok(project);
    }

    @WorkspaceWrite
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse project = projectService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(project);
    }

    @WorkspaceWrite
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> updateProject(
        @PathVariable Long id,
        @Valid @RequestBody ProjectRequest request
    ) {
        ProjectResponse project = projectService.update(id, request);
        return ResponseEntity.ok(project);
    }

    @WorkspaceWrite
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
