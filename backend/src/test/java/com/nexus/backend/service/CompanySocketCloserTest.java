package com.nexus.backend.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nexus.backend.chat.ChatSocketHandler;
import com.nexus.backend.whiteboard.WhiteboardSocketHandler;
import org.junit.jupiter.api.Test;

/**
 * Closing a suspended company's sockets reaches <em>both</em> of its live
 * connections — the chat stream and the whiteboard stream — because each carries
 * the workspace's traffic and either alone would leave the other open.
 */
class CompanySocketCloserTest {

    private final ChatSocketHandler chat = mock(ChatSocketHandler.class);
    private final WhiteboardSocketHandler whiteboard = mock(WhiteboardSocketHandler.class);
    private final CompanySocketCloser closer = new CompanySocketCloser(chat, whiteboard);

    @Test
    void closesBothSocketsForTheCompany() {
        closer.closeCompany(3L);

        verify(chat).closeCompany(3L);
        verify(whiteboard).closeCompany(3L);
    }

    /** A null company names no audience, so nothing may be closed on a guess. */
    @Test
    void closesNothingWithoutACompany() {
        closer.closeCompany(null);

        verify(chat, never()).closeCompany(org.mockito.ArgumentMatchers.any());
        verify(whiteboard, never()).closeCompany(org.mockito.ArgumentMatchers.any());
    }
}
