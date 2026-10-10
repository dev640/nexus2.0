# Graph Report - frontend  (2026-10-10)

## Corpus Check
- 106 files · ~56,331 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 2 file(s) not represented in the graph (top: (none) 1, .css 1)

## Summary
- 728 nodes · 1909 edges · 40 communities (34 shown, 6 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 24 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Workspace APIs and Models
- Application Routes and Pages
- Marketing Motion Components
- Mail Composition
- Chat API Operations
- Reusable React UI
- AI and Markdown
- Layout and Alerts
- API Contracts
- Wiki and Deep Links
- Avatar and HTTP Utilities
- Notifications and Home Briefs
- App TypeScript Config
- Package Manifest
- Device Alert Service
- Frontend Tooling Dependencies
- Task Chips and Avatars
- Settings and Avatar APIs
- Node TypeScript Config
- Unread Counts and Chat State
- Device Alert Tests
- Project and Task Creation
- Admin and Password Reset
- Runtime Dependencies
- Work Dashboard and Mock Data
- Test Harness
- Task Editing
- Project Editing
- React and Vite Starter Docs
- External Icon Sprite
- Nexus Entry and Brand Assets
- Oxlint Configuration
- Npm Scripts
- React Application Entry
- Vite Build Configuration
- Vercel Deployment
- Audio Alert Test Fixtures
- Notification Test Fixtures
- TypeScript Project References

## God Nodes (most connected - your core abstractions)
1. `useAppStore` - 85 edges
2. `apiErrorMessage()` - 53 edges
3. `react` - 39 edges
4. `ChatPage()` - 34 edges
5. `App()` - 24 edges
6. `Modal()` - 21 edges
7. `Settings()` - 19 edges
8. `NexusAI()` - 18 edges
9. `compilerOptions` - 18 edges
10. `vitest` - 16 edges

## Surprising Connections (you probably didn't know these)
- `Nexus Favicon Mark` --semantically_similar_to--> `Nexus Frontend`  [INFERRED] [semantically similar]
  public/favicon-v2.png → index.html
- `Nexus Logo Mark` --semantically_similar_to--> `Nexus Frontend`  [INFERRED] [semantically similar]
  public/logo-mark.png → index.html
- `Nexus Wordmark` --semantically_similar_to--> `Nexus Frontend`  [INFERRED] [semantically similar]
  public/logo-wordmark.png → index.html
- `Vite SVG Mark` --semantically_similar_to--> `Vite`  [INFERRED] [semantically similar]
  public/favicon.svg → README.md
- `Settings()` --indirect_call--> `loadAlertPreferences()`  [INFERRED]
  src/pages/Settings.tsx → src/lib/deviceAlerts.ts

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Nexus Brand Assets** — index_html_nexus, public_favicon_v2_nexus_mark, public_logo_mark_nexus_mark, public_logo_wordmark_nexus_wordmark [INFERRED 0.85]

## Communities (40 total, 6 thin omitted)

### Community 0 - "Workspace APIs and Models"
Cohesion: 0.06
Nodes (84): zustand, apiCreateProject(), apiCreateSprint(), apiCreateTask(), apiCreateWhiteboardNote(), apiCreateWikiPage(), apiDeleteProject(), apiDeleteSprint() (+76 more)

### Community 1 - "Application Routes and Pages"
Cohesion: 0.06
Nodes (45): Admin, AICopilot, Analytics, App(), AppShell, Backlog, Board, Calendar (+37 more)

### Community 2 - "Marketing Motion Components"
Cohesion: 0.08
Nodes (29): Reveal(), RevealProps, MagneticButton(), MagneticButtonProps, MarqueeBand(), MarqueeBandProps, apiLogin(), apiRequestPasswordReset() (+21 more)

### Community 3 - "Mail Composition"
Cohesion: 0.09
Nodes (28): ComposeMessageModal(), handleClose(), handleSubmit(), reset(), created, apiBroadcastMessage(), apiDeleteMessage(), apiListMessages() (+20 more)

### Community 4 - "Chat API Operations"
Cohesion: 0.14
Nodes (30): apiChatChannels(), apiChatCreateChannel(), apiChatDeleteChannel(), apiChatDeleteMessage(), apiChatDiscoverChannels(), apiChatEditMessage(), apiChatJoinChannel(), apiChatLeaveChannel() (+22 more)

### Community 5 - "Reusable React UI"
Cohesion: 0.17
Nodes (13): react, MessageModal(), EditSprintModal(), Props, statuses, NewSprintModal(), NewTeamModal(), Modal() (+5 more)

### Community 6 - "AI and Markdown"
Cohesion: 0.11
Nodes (26): react-markdown, remark-gfm, Markdown(), MarkdownProps, AiConversation, AiKnowledgeStatus, AiMessage, AiSource (+18 more)

### Community 7 - "Layout and Alerts"
Cohesion: 0.16
Nodes (17): lucide-react, react-router-dom, AlertToast(), AlertToasts(), renderToasts(), AppShell(), adminNavItem, iconByPath (+9 more)

### Community 8 - "API Contracts"
Cohesion: 0.08
Nodes (22): AiConversationDetail, AiReindexResult, ApiAuthResponse, ApiChatChannel, ApiChatChannelType, ApiCopilotAnswer, ApiCreatedUser, ApiCreateUserInput (+14 more)

### Community 9 - "Wiki and Deep Links"
Cohesion: 0.13
Nodes (11): NewWikiPageModal(), DeepLinkOptions, DeepLinkOutcome, DeepLinkPage(), handled, page(), useDeepLink(), Whiteboard() (+3 more)

### Community 10 - "Avatar and HTTP Utilities"
Cohesion: 0.17
Nodes (16): axios, cache, CacheEntry, clearAvatarCache(), flushPendingRevoke(), forgetAvatar(), getVersion(), invalidateAvatar() (+8 more)

### Community 11 - "Notifications and Home Briefs"
Cohesion: 0.19
Nodes (18): apiArchiveNotification(), apiListNotifications(), apiMarkAllNotificationsRead(), apiMarkNotificationRead(), ApiNotification, buildPersonalBrief(), buildWorkspaceBrief(), FocusRow() (+10 more)

### Community 12 - "App TypeScript Config"
Cohesion: 0.10
Nodes (19): compilerOptions, allowArbitraryExtensions, allowImportingTsExtensions, erasableSyntaxOnly, jsx, lib, module, moduleDetection (+11 more)

### Community 13 - "Package Manifest"
Cohesion: 0.11
Nodes (18): name, private, type, version, autoprefixer, clsx, jsdom, oxlint (+10 more)

### Community 14 - "Device Alert Service"
Cohesion: 0.24
Nodes (15): emitAlert(), onNotification(), useDeviceAlerts(), handle(), ALERT_SOUND_URL, alertPermission, defaultAlertPreferences, loadAlertPreferences() (+7 more)

### Community 15 - "Frontend Tooling Dependencies"
Cohesion: 0.12
Nodes (17): devDependencies, autoprefixer, jsdom, oxlint, postcss, tailwindcss, @tailwindcss/postcss, @testing-library/dom (+9 more)

### Community 16 - "Task Chips and Avatars"
Cohesion: 0.21
Nodes (12): LabelChips(), Props, Size, sizeClass, UserAvatar(), memberById(), Backlog(), priorityColor (+4 more)

### Community 17 - "Settings and Avatar APIs"
Cohesion: 0.18
Nodes (14): apiDeleteAvatar(), apiUploadAvatar(), AlertPreferences, requestAlertPermission(), saveAlertPreferences(), assignableRoles, categories, initialsOf() (+6 more)

### Community 18 - "Node TypeScript Config"
Cohesion: 0.12
Nodes (16): compilerOptions, allowImportingTsExtensions, erasableSyntaxOnly, lib, module, moduleDetection, noEmit, noFallthroughCasesInSwitch (+8 more)

### Community 19 - "Unread Counts and Chat State"
Cohesion: 0.25
Nodes (13): useUnreadCount(), onEvent(), onStatus(), refresh(), ApiChatMessage, apiChatUnread, getCurrentToken(), whiteboardSocketUrl() (+5 more)

### Community 20 - "Device Alert Tests"
Cohesion: 0.13
Nodes (8): ApiNotificationish, FakeAudio, FakeNotification, instances, plays, popups, socket, ChatSocketEvent

### Community 21 - "Project and Task Creation"
Cohesion: 0.23
Nodes (8): NewProjectModal(), NewTaskModal(), priorities, statuses, canManageWorkspace(), healthColor, healthLabel, Projects()

### Community 22 - "Admin and Password Reset"
Cohesion: 0.22
Nodes (11): apiCreateUser(), apiDismissPasswordReset(), apiListPasswordResets(), ApiPasswordResetRequest, apiResolvePasswordReset(), ApiUserRole, Admin(), handleCreateUser() (+3 more)

### Community 23 - "Runtime Dependencies"
Cohesion: 0.17
Nodes (12): dependencies, axios, clsx, @fontsource/inter, lucide-react, react, react-dom, react-markdown (+4 more)

### Community 24 - "Work Dashboard and Mock Data"
Cohesion: 0.27
Nodes (10): Task, TaskPriority, TaskStatus, buckets, MyWork(), priorityColor, TaskRow(), NewTaskInput (+2 more)

### Community 25 - "Test Harness"
Cohesion: 0.24
Nodes (6): @testing-library/react, vitest, ActionKeys, actions, initialSessionFlags, State

### Community 26 - "Task Editing"
Cohesion: 0.36
Nodes (6): EditTaskModal(), handleSubmit(), priorities, statuses, labelsToInput(), parseLabels()

### Community 27 - "Project Editing"
Cohesion: 0.29
Nodes (5): EditProjectModal(), Props, statuses, ApiProjectStatus, ProjectEditInput

### Community 28 - "React and Vite Starter Docs"
Cohesion: 0.33
Nodes (7): Vite SVG Mark, Oxlint, React, React Compiler, TypeScript, Vite, Vite React Plugin

### Community 29 - "External Icon Sprite"
Cohesion: 0.29
Nodes (7): Bluesky Icon, Discord Icon, Documentation Icon, GitHub Icon, Social Icon, Social and Utility Icon Sprite, X Icon

### Community 30 - "Nexus Entry and Brand Assets"
Cohesion: 0.33
Nodes (6): React Main Entry, Nexus Frontend, React Root Mount, Nexus Favicon Mark, Nexus Logo Mark, Nexus Wordmark

### Community 31 - "Oxlint Configuration"
Cohesion: 0.33
Nodes (5): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema

### Community 32 - "Npm Scripts"
Cohesion: 0.33
Nodes (6): scripts, build, dev, lint, preview, test

### Community 35 - "Vercel Deployment"
Cohesion: 0.50
Nodes (3): buildCommand, outputDirectory, rewrites

## Knowledge Gaps
- **27 isolated node(s):** `clsx`, `@tailwindcss/postcss`, `@testing-library/dom`, `@types/node`, `@types/react` (+22 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 263 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `useAppStore` connect `Reusable React UI` to `Workspace APIs and Models`, `Application Routes and Pages`, `Marketing Motion Components`, `Mail Composition`, `Chat API Operations`, `Layout and Alerts`, `Wiki and Deep Links`, `Notifications and Home Briefs`, `Device Alert Service`, `Task Chips and Avatars`, `Settings and Avatar APIs`, `Unread Counts and Chat State`, `Device Alert Tests`, `Project and Task Creation`, `Admin and Password Reset`, `Work Dashboard and Mock Data`, `Test Harness`, `Task Editing`, `Project Editing`?**
  _High betweenness centrality (0.158) - this node is a cross-community bridge._
- **What connects `clsx`, `@tailwindcss/postcss`, `@testing-library/dom` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Workspace APIs and Models` be split into smaller, more focused modules?**
  _Cohesion score 0.06167904054825814 - nodes in this community are weakly interconnected._
- **Why does `react` connect `Reusable React UI` to `Application Routes and Pages`, `Marketing Motion Components`, `Mail Composition`, `Chat API Operations`, `AI and Markdown`, `Layout and Alerts`, `Wiki and Deep Links`, `Avatar and HTTP Utilities`, `Package Manifest`, `Device Alert Service`, `Task Chips and Avatars`, `Settings and Avatar APIs`, `Unread Counts and Chat State`, `Project and Task Creation`, `Admin and Password Reset`, `Work Dashboard and Mock Data`, `Task Editing`, `Project Editing`, `React Application Entry`?**
  _High betweenness centrality (0.085) - this node is a cross-community bridge._
- **Should `Application Routes and Pages` be split into smaller, more focused modules?**
  _Cohesion score 0.06289308176100629 - nodes in this community are weakly interconnected._
- **Why does `apiErrorMessage()` connect `Chat API Operations` to `Workspace APIs and Models`, `Application Routes and Pages`, `Marketing Motion Components`, `Mail Composition`, `AI and Markdown`, `API Contracts`, `Notifications and Home Briefs`, `Settings and Avatar APIs`, `Admin and Password Reset`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Should `Marketing Motion Components` be split into smaller, more focused modules?**
  _Cohesion score 0.08130081300813008 - nodes in this community are weakly interconnected._