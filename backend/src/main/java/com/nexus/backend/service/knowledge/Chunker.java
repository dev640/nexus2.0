package com.nexus.backend.service.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits normalized text into retrieval-sized chunks: paragraph-aware,
 * word-boundary-friendly, with a small overlap so answers that straddle a
 * boundary still retrieve correctly. Short documents stay in one piece.
 */
@Component
public class Chunker {

    private final int targetChars;
    private final int overlapChars;
    private final int maxChunks;

    public Chunker(
            @Value("${nexus.ai.chunk.target-chars:1000}") int targetChars,
            @Value("${nexus.ai.chunk.overlap-chars:120}") int overlapChars,
            @Value("${nexus.ai.chunk.max-chunks:200}") int maxChunks) {
        this.targetChars = Math.max(200, targetChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.targetChars / 2));
        this.maxChunks = Math.max(1, maxChunks);
    }

    /** Chunks {@code text}; empty list for null/blank input. */
    public List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) return out;
        if (normalized.length() <= targetChars) {
            out.add(normalized);
            return out;
        }

        int pos = 0;
        int len = normalized.length();
        while (pos < len && out.size() < maxChunks) {
            int end = Math.min(pos + targetChars, len);
            if (end < len) end = preferredBreak(normalized, pos, end);
            String piece = normalized.substring(pos, end).strip();
            if (!piece.isEmpty()) out.add(piece);
            if (end >= len) break;
            pos = Math.max(end - overlapChars, pos + 1);
        }
        return out;
    }

    /** Prefer breaking on a paragraph, then line, then word boundary. */
    private int preferredBreak(String text, int start, int end) {
        int floor = start + (end - start) / 2;
        for (int i = end; i > floor; i--) {
            if (text.charAt(i) == '\n' && text.charAt(i - 1) == '\n') return i;
        }
        for (int i = end; i > floor; i--) {
            if (text.charAt(i) == '\n') return i;
        }
        for (int i = end; i > floor; i--) {
            if (text.charAt(i) == ' ') return i;
        }
        return end;
    }
}
