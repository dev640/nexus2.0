# Graph Report - lexus  (2026-10-09)

## Corpus Check
- 332 files · ~126,795 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 11 file(s) not represented in the graph (top: (none) 6, .properties 2, .example 1)

## Summary
- 2962 nodes · 9483 edges · 118 communities (67 shown, 51 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 1074 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Markdown Rendering & Badges
- Internal Mail Modals
- Avatar Cache & UI Tests
- Frontend API Client
- JPA Entity Base & Auditing
- Project & Sprint Edit Modals
- Platform Environment Adapter
- Password Reset Schema
- AI Rate Limiting
- Device Alert Toasts
- Error Handling Layer
- Task Entity
- Wiki Page Entity
- Chat & Copilot DTOs
- Chat Socket Handler
- Chat Handshake Auth
- Notification Categories
- Test Mocking Utilities
- Chat Channel Entity
- Avatar Storage
- Status Validation Tests
- Sprint Entity
- REST Controllers
- Chat Broadcast & Membership
- Sprint DTOs & Errors
- User Entity Mutators
- Task Request & Role Policy
- Compose Message Flow
- App Routing & Navigation
- Knowledge Chunk Entity
- Grounded Copilot Service
- Chat Delivery Path
- Supabase Configuration
- User Admin & Supabase Linking
- Knowledge Retrieval Queries
- Whiteboard DTOs
- Datasource Properties
- Workspace Activity Feed
- Knowledge Source Types
- Project Entity
- Whiteboard Note Entity
- Frontend Dependencies Manifest
- Password Reset Status
- Workspace Write & Manage Annotations
- Security Configuration
- AI Message Entity
- Chat Message Entity
- Internal Mail Service
- AI Response DTOs
- Knowledge Chunk Fields
- Mail Message Entity
- Project CRUD DTOs
- RAG Prompt Builder
- Body Size Filter Tests
- Docs & CI Graph
- Task Enums & User Response
- AI Engine Interface
- Frontend TS Config (app)
- Password Reset Entity
- Task Responses & Queries
- Security Test Harness
- User Validation Tests
- AI Conversation Entity
- Knowledge Chunk Mutators
- Mail Repository & Response
- AI Request DTOs
- Analytics DTOs
- Wiki DTOs & Knowledge Events
- Activity Event Wiring
- LLM Client & Providers
- Frontend Dev Dependencies
- Frontend TS Config (node)
- Knowledge Source Labels
- User Role Enum
- LLM Failure Classification
- Platform Environment Internals
- Whiteboard Broadcast
- Admin Reset API Client
- OpenAI Engine
- Frontend Runtime Dependencies
- Calendar Page
- Security Audit Graph
- Base Entity Accessors
- Chat Unread Counts
- Avatar Endpoint Tests
- PRD Graph
- User Controller & Requests
- Knowledge Access Scope
- Source Document Loading
- Knowledge Chunking
- Application Bootstrap
- AI Conversation DTOs
- Message Broadcast
- SPA Shell & Assets
- Task Type Enum
- Admin Reset Controller
- Config Guard Tests
- Oxlint Configuration
- Frontend npm Scripts
- Vercel Deployment Config
- Store Test Suite
- Frontend Vercel Config
- Whiteboard Notes Schema
- Root TS Config
- Backend Project Coordinates

## God Nodes (most connected - your core abstractions)
1. `User` - 179 edges
2. `useAppStore` - 85 edges
3. `Project` - 78 edges
4. `UserRepository` - 75 edges
5. `ResourceNotFoundException` - 70 edges
6. `Task` - 62 edges
7. `ChatChannel` - 60 edges
8. `KnowledgeChunk` - 56 edges
9. `apiErrorMessage()` - 53 edges
10. `Sprint` - 52 edges

## Surprising Connections (you probably didn't know these)
- `Delivery phase history` --references--> `PlatformEnvironment`  [EXTRACTED]
  PRD.md → backend/src/main/java/com/nexus/backend/config/PlatformEnvironment.java
- `VIEWER read-only enforcement` --references--> `WorkspaceWrite`  [EXTRACTED]
  PRD.md → backend/src/main/java/com/nexus/backend/security/WorkspaceWrite.java
- `Redis whiteboard fan-out` --references--> `WhiteboardBroadcaster`  [EXTRACTED]
  PRD.md → backend/src/main/java/com/nexus/backend/whiteboard/WhiteboardBroadcaster.java
- `Release and verify flow` --references--> `PlatformEnvironment`  [EXTRACTED]
  OPERATIONS.md → backend/src/main/java/com/nexus/backend/config/PlatformEnvironment.java
- `Grounded Copilot answers` --references--> `CopilotService`  [EXTRACTED]
  PRD.md → backend/src/main/java/com/nexus/backend/service/CopilotService.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Security hardening pass** — backend_security_audit_decision_defaults, backend_security_audit_jwt_failfast, backend_security_audit_headers, backend_security_audit_cors, backend_security_audit_rate_limits, backend_security_audit_body_cap, backend_security_audit_ws_bounds, backend_security_audit_error_generic [EXTRACTED 1.00]
- **Nexus live deployment stack** — readme_nexus_platform, prd_whiteboard_fanout, operations_release_flow, docker_compose_local_stack, _github_workflows_ci [INFERRED 0.75]
- **Deployment environment contract** — operations_config_map, operations_secrets_rules, _env_example, readme_deployment_railway, readme_deployment_vercel [INFERRED 0.85]

## Communities (118 total, 51 thin omitted)

### Community 0 - "Markdown Rendering & Badges"
Cohesion: 0.04
Nodes (108): Markdown(), MarkdownProps, useUnreadCount(), onEvent(), onStatus(), refresh(), AiConversation, AiConversationDetail (+100 more)

### Community 1 - "Internal Mail Modals"
Cohesion: 0.05
Nodes (50): MessageModal(), EditProjectModal(), NewProjectModal(), EditSprintModal(), Props, statuses, NewSprintModal(), EditTaskModal() (+42 more)

### Community 2 - "Avatar Cache & UI Tests"
Cohesion: 0.05
Nodes (56): cache, CacheEntry, clearAvatarCache(), flushPendingRevoke(), forgetAvatar(), getVersion(), invalidateAvatar(), listeners (+48 more)

### Community 3 - "Frontend API Client"
Cohesion: 0.07
Nodes (65): apiArchiveNotification(), apiCreateWhiteboardNote(), apiCreateWikiPage(), apiDeleteWhiteboardNote(), apiDeleteWikiPage(), apiListNotifications(), apiListWhiteboardNotes(), apiListWikiPages() (+57 more)

### Community 4 - "JPA Entity Base & Auditing"
Cohesion: 0.04
Nodes (5): Organization, Project, Workspace, OrganizationRepository, WorkspaceRepository

### Community 5 - "Project & Sprint Edit Modals"
Cohesion: 0.07
Nodes (58): Props, statuses, apiCreateProject(), apiCreateSprint(), apiCreateTask(), apiDeleteProject(), apiDeleteSprint(), apiDeleteTask() (+50 more)

### Community 7 - "Password Reset Schema"
Cohesion: 0.05
Nodes (57): idx_password_reset_requests_status, password_reset_requests, uq_password_reset_requests_pending, user_avatars, ux_users_employee_code, idx_tasks_blocked, ai_conversations, ai_messages (+49 more)

### Community 8 - "AI Rate Limiting"
Cohesion: 0.10
Nodes (9): AiRateLimitFilter, Window, ApiBodySizeFilter, AuthRateLimitFilter, Window, GlobalRateLimitFilter, Window, SecurityHeadersFilter (+1 more)

### Community 9 - "Device Alert Toasts"
Cohesion: 0.07
Nodes (32): AlertToast(), AlertToasts(), renderToasts(), AppShell(), adminNavItem, iconByPath, NavRow(), Sidebar() (+24 more)

### Community 10 - "Error Handling Layer"
Cohesion: 0.07
Nodes (16): ErrorResponse, GlobalExceptionHandler, AiEngineException, Kind, AUTH, INVALID_REQUEST, NOT_CONFIGURED, RATE_LIMIT (+8 more)

### Community 11 - "Task Entity"
Cohesion: 0.05
Nodes (14): Task, TaskPriority, HIGH, LOW, MEDIUM, URGENT, TaskStatus, BACKLOG (+6 more)

### Community 12 - "Wiki Page Entity"
Cohesion: 0.08
Nodes (6): WikiPage, ProjectRepository, SprintRepository, TaskRepository, WikiPageRepository, AnalyticsService

### Community 13 - "Chat & Copilot DTOs"
Cohesion: 0.10
Nodes (9): ChatMessageRequest, ChatReactionRequest, CopilotAskRequest, ChatController, PresenceResponse, UpdateTopicRequest, HealthController, MessageController (+1 more)

### Community 14 - "Chat Socket Handler"
Cohesion: 0.10
Nodes (5): ChatSocketHandler, JsonSerializer<>, MessageWindow, WhiteboardSocketHandler, ChatSocketRateLimitTest

### Community 15 - "Chat Handshake Auth"
Cohesion: 0.11
Nodes (8): ChatHandshakeInterceptor, ChatSocketConfig, JpaAuditingConfig, JwtUtil, KnowledgeSyncListener, SupabaseUserService, JwtHandshakeInterceptor, WhiteboardSocketConfig

### Community 16 - "Notification Categories"
Cohesion: 0.06
Nodes (9): Category, AI, MENTIONS, PROJECTS, SYSTEM, TASKS, Notification, User (+1 more)

### Community 18 - "Chat Channel Entity"
Cohesion: 0.11
Nodes (8): ChatChannel, Type, DM, PRIVATE, PUBLIC, ChatChannelRequest, ChatChannelResponse, ChatChannelRepository

### Community 19 - "Avatar Storage"
Cohesion: 0.12
Nodes (4): UserAvatar, UserAvatarRepository, AvatarService, AvatarServiceTest

### Community 21 - "Sprint Entity"
Cohesion: 0.05
Nodes (6): Sprint, SprintStatus, ACTIVE, COMPLETED, PLANNED, SprintStatusRequest

### Community 23 - "Chat Broadcast & Membership"
Cohesion: 0.12
Nodes (3): ChatChannelMember, ChatChannelMemberRepository, ChatServiceTest

### Community 24 - "Sprint DTOs & Errors"
Cohesion: 0.13
Nodes (5): SprintRequest, SprintResponse, ResourceNotFoundException, SprintService, UserSprintServiceTest

### Community 25 - "User Entity Mutators"
Cohesion: 0.13
Nodes (8): AuthResponse, LoginRequest, RegisterRequest, UserResponse, UserService, AuthController, PasswordResetRequestBody, RefreshRequest

### Community 27 - "Task Request & Role Policy"
Cohesion: 0.16
Nodes (4): TaskRequest, RolePolicy, TaskAssignmentPolicyTest, ProjectTaskServiceTest

### Community 28 - "Compose Message Flow"
Cohesion: 0.09
Nodes (27): ComposeMessageModal(), handleClose(), handleSubmit(), reset(), created, apiBroadcastMessage(), apiDeleteMessage(), apiListMessages() (+19 more)

### Community 29 - "App Routing & Navigation"
Cohesion: 0.11
Nodes (31): Admin, AICopilot, Analytics, App(), AppShell, Backlog, Board, ChatPage (+23 more)

### Community 30 - "Knowledge Chunk Entity"
Cohesion: 0.08
Nodes (4): AiKnowledgeStatusResponse, Chunker, KnowledgeIndexService, ReindexResult

### Community 31 - "Grounded Copilot Service"
Cohesion: 0.14
Nodes (4): CopilotService, LlmClient, LlmResult, CopilotServiceTest

### Community 32 - "Chat Delivery Path"
Cohesion: 0.22
Nodes (7): ChatBroadcaster, ChatEvent, ReactionEvent, ChatMessageResponse, ValidationException, ChatService, com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, List<Long>>>

### Community 34 - "User Admin & Supabase Linking"
Cohesion: 0.16
Nodes (3): CreateUserRequest, SupabaseAdminClient, AdminAccountServiceTest

### Community 35 - "Knowledge Retrieval Queries"
Cohesion: 0.12
Nodes (5): KnowledgeChunkRepository, PermissionService, Candidate, RetrievalService, RetrievalServiceTest

### Community 36 - "Whiteboard DTOs"
Cohesion: 0.08
Nodes (7): WhiteboardNoteRequest, WhiteboardNoteResponse, WhiteboardNoteUpdateRequest, ChatSocketFacade, TaskController, WhiteboardController, ViewerReadOnlySecurityTest

### Community 38 - "Workspace Activity Feed"
Cohesion: 0.16
Nodes (4): NotificationResponse, WorkspaceActivityListener, NotificationService, NotificationServiceTest

### Community 39 - "Knowledge Source Types"
Cohesion: 0.18
Nodes (5): WhiteboardNoteRepository, ActivityEvents, KnowledgeEvents, ProjectService, WhiteboardService

### Community 40 - "Project Entity"
Cohesion: 0.09
Nodes (10): ProjectHealth, AT_RISK, OFF_TRACK, ON_TRACK, ProjectStatus, ACTIVE, ARCHIVED, COMPLETED (+2 more)

### Community 42 - "Frontend Dependencies Manifest"
Cohesion: 0.08
Nodes (21): name, private, type, version, autoprefixer, clsx, @fontsource/inter, jsdom (+13 more)

### Community 43 - "Password Reset Status"
Cohesion: 0.14
Nodes (7): ResetRequestStatus, DISMISSED, PENDING, RESOLVED, PasswordResetResponse, PasswordResetRequestRepository, AdminAccountService

### Community 44 - "Workspace Write & Manage Annotations"
Cohesion: 0.14
Nodes (3): WorkspaceManage, WorkspaceWrite, SprintController

### Community 46 - "AI Message Entity"
Cohesion: 0.10
Nodes (4): AiMessage, AiRole, ASSISTANT, USER

### Community 49 - "AI Response DTOs"
Cohesion: 0.17
Nodes (5): AiMessageResponse, AiSourceResponse, AiMessageRepository, ConversationService, RegeneratePlan

### Community 52 - "Project CRUD DTOs"
Cohesion: 0.16
Nodes (3): ProjectRequest, ProjectResponse, ProjectController

### Community 53 - "RAG Prompt Builder"
Cohesion: 0.21
Nodes (3): PromptBuilder, RetrievedContext, PromptBuilderTest

### Community 54 - "Body Size Filter Tests"
Cohesion: 0.17
Nodes (3): ApiBodySizeFilterTest, GlobalRateLimitFilterTest, SecurityHeadersFilterTest

### Community 55 - "Docs & CI Graph"
Cohesion: 0.12
Nodes (14): .env.example, Backend CI job, Frontend CI job, Supabase service-role key committed in docker-compose.yml, Local Postgres + Redis + API stack, Configuration platform map, Incident response checklist, Documented configuration inconsistencies (+6 more)

### Community 58 - "AI Engine Interface"
Cohesion: 0.21
Nodes (5): AiEngine, Message, AiAnswer, AiService, AiServiceTest

### Community 59 - "Frontend TS Config (app)"
Cohesion: 0.10
Nodes (19): compilerOptions, allowArbitraryExtensions, allowImportingTsExtensions, erasableSyntaxOnly, jsx, lib, module, moduleDetection (+11 more)

### Community 67 - "Mail Repository & Response"
Cohesion: 0.20
Nodes (3): MessageResponse, MessageRepository, MessageService

### Community 68 - "AI Request DTOs"
Cohesion: 0.25
Nodes (3): AiAskRequest, AiReindexResponse, AiController

### Community 69 - "Analytics DTOs"
Cohesion: 0.20
Nodes (8): AnalyticsResponse, MemberLoad, PriorityCount, RiskItem, SprintVelocity, StatusCount, Summary, VelocityData

### Community 70 - "Wiki DTOs & Knowledge Events"
Cohesion: 0.27
Nodes (4): WikiPageRequest, WikiPageResponse, WikiPageService, WikiPageController

### Community 72 - "LLM Client & Providers"
Cohesion: 0.15
Nodes (6): Auth, API_KEY_HEADER, BEARER, LlmProvider, GEMINI, OPENAI

### Community 73 - "Frontend Dev Dependencies"
Cohesion: 0.12
Nodes (17): devDependencies, autoprefixer, jsdom, oxlint, postcss, tailwindcss, @tailwindcss/postcss, @testing-library/dom (+9 more)

### Community 74 - "Frontend TS Config (node)"
Cohesion: 0.12
Nodes (16): compilerOptions, allowImportingTsExtensions, erasableSyntaxOnly, lib, module, moduleDetection, noEmit, noFallthroughCasesInSwitch (+8 more)

### Community 75 - "Knowledge Source Labels"
Cohesion: 0.15
Nodes (8): KnowledgeSourceType, PROJECT, SPRINT, TASK, WHITEBOARD_NOTE, WIKI_PAGE, KnowledgeChangeEvent, KnowledgeRemoveEvent

### Community 76 - "User Role Enum"
Cohesion: 0.15
Nodes (6): UserRole, ADMIN, DEVELOPER, MANAGER, MEMBER, VIEWER

### Community 77 - "LLM Failure Classification"
Cohesion: 0.12
Nodes (12): LlmFailure, BAD_REQUEST, INVALID_API_KEY, MALFORMED_RESPONSE, MODEL_NOT_CONFIGURED, MODEL_NOT_FOUND, NETWORK, NOT_CONFIGURED (+4 more)

### Community 81 - "Admin Reset API Client"
Cohesion: 0.22
Nodes (11): apiCreateUser(), apiDismissPasswordReset(), apiListPasswordResets(), ApiPasswordResetRequest, apiResolvePasswordReset(), ApiUserRole, Admin(), handleCreateUser() (+3 more)

### Community 83 - "Frontend Runtime Dependencies"
Cohesion: 0.17
Nodes (12): dependencies, axios, clsx, @fontsource/inter, lucide-react, react, react-dom, react-markdown (+4 more)

### Community 84 - "Calendar Page"
Cohesion: 0.21
Nodes (8): Calendar, buildMonthGrid(), Calendar(), monthNames, pad(), projectColors, toISODate(), weekdayLabels

### Community 90 - "PRD Graph"
Cohesion: 0.22
Nodes (9): REST, SSE and WebSocket API surface, Delivery phase history, Hybrid retrieval with rank fusion, Knowledge chunk index, Nexus AI retrieval-augmented chat, Workspace role model, VIEWER read-only enforcement, Redis whiteboard fan-out (+1 more)

### Community 92 - "User Controller & Requests"
Cohesion: 0.40
Nodes (3): UpdateMeRequest, UpdateRoleRequest, UserController

### Community 93 - "Knowledge Access Scope"
Cohesion: 0.22
Nodes (6): AccessScope, ADMIN, PRIVATE, WORKSPACE, Entry, Scored

### Community 101 - "Task Type Enum"
Cohesion: 0.33
Nodes (5): TaskType, EPIC, STORY, SUBTASK, TASK

### Community 104 - "Oxlint Configuration"
Cohesion: 0.33
Nodes (5): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema

### Community 105 - "Frontend npm Scripts"
Cohesion: 0.33
Nodes (6): scripts, build, dev, lint, preview, test

### Community 106 - "Vercel Deployment Config"
Cohesion: 0.33
Nodes (5): buildCommand, installCommand, outputDirectory, rewrites, version

### Community 107 - "Store Test Suite"
Cohesion: 0.40
Nodes (4): ActionKeys, actions, initialSessionFlags, State

### Community 108 - "Frontend Vercel Config"
Cohesion: 0.40
Nodes (4): buildCommand, outputDirectory, rewrites, Vercel frontend deployment

## Ambiguous Edges - Review These
- `frontend/README.md` → `icons.svg`  [AMBIGUOUS]
  frontend/public/icons.svg · relation: conceptually_related_to

## Knowledge Gaps
- **100 isolated node(s):** `com.nexus:backend`, `USER`, `ASSISTANT`, `PUBLIC`, `PRIVATE` (+95 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 708 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **51 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SupabaseUserService` connect `Chat Handshake Auth` to `Avatar Service & Tests`, `Supabase Configuration`, `User Admin & Supabase Linking`, `Whiteboard DTOs`, `Datasource Properties`, `Knowledge Source Types`, `AI Rate Limiting`, `Project Entity`, `User Role Enum`, `JWT Token Utilities`, `Test Mocking Utilities`, `Docs & CI Graph`, `Avatar Endpoint Tests`, `Security Test Harness`?**
  _High betweenness centrality (0.141) - this node is a cross-community bridge._
- **What connects `com.nexus:backend`, `USER`, `ASSISTANT` to the rest of the system?**
  _100 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Markdown Rendering & Badges` be split into smaller, more focused modules?**
  _Cohesion score 0.03627450980392157 - nodes in this community are weakly interconnected._
- **Why does `User` connect `Notification Categories` to `JPA Entity Base & Auditing`, `Platform Environment Adapter`, `AI Rate Limiting`, `Task Entity`, `Wiki Page Entity`, `Chat Socket Handler`, `Chat Handshake Auth`, `Test Mocking Utilities`, `Chat Channel Entity`, `Avatar Storage`, `REST Controllers`, `Chat Broadcast & Membership`, `User Entity Mutators`, `AI Conversation Persistence`, `Task Request & Role Policy`, `Chat Delivery Path`, `User Admin & Supabase Linking`, `Knowledge Retrieval Queries`, `Datasource Properties`, `Workspace Activity Feed`, `Knowledge Source Types`, `Password Reset Status`, `AI Message Entity`, `Chat Message Entity`, `Internal Mail Service`, `AI Response DTOs`, `Mail Message Entity`, `Task Enums & User Response`, `Password Reset Entity`, `Task Responses & Queries`, `Security Test Harness`, `User Validation Tests`, `Avatar Service & Tests`, `AI Conversation Entity`, `Knowledge Chunk Mutators`, `Mail Repository & Response`, `AI Request DTOs`, `Activity Event Wiring`, `User Role Enum`, `Chat Unread Counts`, `User Controller & Requests`, `Admin Account Guards`, `AI Conversation DTOs`, `Message Broadcast`, `Admin Reset Controller`?**
  _High betweenness centrality (0.114) - this node is a cross-community bridge._
- **Should `Internal Mail Modals` be split into smaller, more focused modules?**
  _Cohesion score 0.054464703132304816 - nodes in this community are weakly interconnected._
- **Why does `ChatSocketHandler` connect `Chat Socket Handler` to `Chat Delivery Path`, `Whiteboard DTOs`, `Platform Environment Adapter`, `AI Rate Limiting`, `Wiki Page Entity`, `Chat Handshake Auth`, `Test Mocking Utilities`, `Chat Channel Entity`, `Security Audit Graph`, `Docs & CI Graph`, `Chat Broadcast & Membership`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._

### Low-confidence Hints
_AMBIGUOUS edges — the extractor was unsure. Verify before acting on these._

- **What is the exact relationship between `frontend/README.md` and `icons.svg`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._