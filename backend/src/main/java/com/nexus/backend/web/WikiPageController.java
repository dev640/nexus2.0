package com.nexus.backend.web;

import com.nexus.backend.dto.WikiPageRequest;
import com.nexus.backend.dto.WikiPageResponse;
import com.nexus.backend.security.WorkspaceWrite;
import com.nexus.backend.service.WikiPageService;
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
@RequestMapping("/api/wiki")
public class WikiPageController {

    private final WikiPageService wikiPageService;

    public WikiPageController(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @GetMapping
    public ResponseEntity<List<WikiPageResponse>> listPages(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(wikiPageService.findAll(q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WikiPageResponse> getPage(@PathVariable Long id) {
        return ResponseEntity.ok(wikiPageService.findById(id));
    }

    @WorkspaceWrite
    @PostMapping
    public ResponseEntity<WikiPageResponse> createPage(@Valid @RequestBody WikiPageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wikiPageService.create(request));
    }

    @WorkspaceWrite
    @PatchMapping("/{id}")
    public ResponseEntity<WikiPageResponse> updatePage(
        @PathVariable Long id,
        @Valid @RequestBody WikiPageRequest request
    ) {
        return ResponseEntity.ok(wikiPageService.update(id, request));
    }

    @WorkspaceWrite
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePage(@PathVariable Long id) {
        wikiPageService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
