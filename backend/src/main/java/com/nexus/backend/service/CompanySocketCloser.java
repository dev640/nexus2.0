package com.nexus.backend.service;

import com.nexus.backend.chat.ChatSocketHandler;
import com.nexus.backend.whiteboard.WhiteboardSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Closes every live socket belonging to one company, on both the chat and the
 * whiteboard connections.
 *
 * <p>Suspension withdraws a workspace's access, but an already-open socket was
 * opened while it was active and would keep streaming the same chat and board
 * its HTTP requests are now refused. The handshake refuses <em>new</em>
 * connections for a suspended company (see {@code SocketPrincipalResolver}); this
 * is what ends the ones already up.
 *
 * <p>Isolated here so the two socket handlers stay independent features and the
 * suspension service depends on one narrow seam rather than both registries. It
 * is deliberately local: closing the sockets on <em>this</em> instance. Other
 * instances learn of the suspension over Redis — see
 * {@code CompanySuspensionService} and {@code CompanySuspensionConfig}.
 */
@Component
public class CompanySocketCloser {

    private static final Logger log = LoggerFactory.getLogger(CompanySocketCloser.class);

    private final ChatSocketHandler chatSocketHandler;
    private final WhiteboardSocketHandler whiteboardSocketHandler;

    public CompanySocketCloser(
        ChatSocketHandler chatSocketHandler,
        WhiteboardSocketHandler whiteboardSocketHandler
    ) {
        this.chatSocketHandler = chatSocketHandler;
        this.whiteboardSocketHandler = whiteboardSocketHandler;
    }

    /**
     * Close this instance's sockets for the company. A null company is refused
     * rather than treated as "everyone": there is no correct audience for a
     * close that cannot name whose it is, and the wrong guess is locking every
     * workspace out at once.
     */
    public void closeCompany(Long companyId) {
        if (companyId == null) {
            log.warn("Not closing sockets: no company named");
            return;
        }
        chatSocketHandler.closeCompany(companyId);
        whiteboardSocketHandler.closeCompany(companyId);
        log.info("Closed chat and whiteboard sockets for company {}", companyId);
    }
}
