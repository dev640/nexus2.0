package com.nexus.backend.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private final Chunker chunker = new Chunker(1000, 120, 200);

    @Test
    void shortTextStaysInOnePiece() {
        assertThat(chunker.chunk("hello world")).containsExactly("hello world");
    }

    @Test
    void blankInputYieldsNoChunks() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n\t  ")).isEmpty();
    }

    @Test
    void longTextSplitsWithinTargetSizeAndCoversBothEnds() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            text.append("Paragraph ").append(i)
                .append(" about Project Alpha, its sprint goal and delivery plan.")
                .append(" More detail to reach the target size for splitting.")
                .append("\n\n");
        }
        String source = text.toString().strip();

        List<String> pieces = chunker.chunk(source);

        assertThat(pieces).hasSizeGreaterThan(1);
        pieces.forEach(piece -> {
            assertThat(piece).isNotBlank();
            assertThat(piece.length()).isLessThanOrEqualTo(1000);
        });
        assertThat(pieces.get(0)).startsWith("Paragraph 0");
        assertThat(pieces.get(pieces.size() - 1)).endsWith("splitting.");
    }

    @Test
    void overlapKeepsContextAcrossABoundary() {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < 200; i++) word.append("alpha ").append(i).append(" ");
        String source = word.toString().strip();
        assertThat(source.length()).isGreaterThan(1000);

        List<String> pieces = chunker.chunk(source);
        assertThat(pieces).hasSizeGreaterThan(1);
        // The tail of each piece reappears inside the next piece (overlap region).
        String shared = pieces.get(0).substring(pieces.get(0).length() - 60);
        assertThat(pieces.get(1)).contains(shared);
    }

    @Test
    void maxChunksBoundsPathologicalDocuments() {
        Chunker limited = new Chunker(200, 20, 5);
        String source = "x".repeat(5000);
        assertThat(limited.chunk(source)).hasSizeLessThanOrEqualTo(5);
    }
}
