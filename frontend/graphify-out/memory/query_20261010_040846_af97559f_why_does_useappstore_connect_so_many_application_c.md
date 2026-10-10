---
type: "query"
date: "2026-10-10T04:08:46.784290+00:00"
question: "Why does useAppStore connect so many application communities?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["useAppStore", "App()", "ChatPage()", "Settings()", "Home()", "useDeviceAlerts()", "useUnreadCount()", "createAuthSlice()", "createTasksSlice()", "createSettingsSlice()"]
---

# Q: Why does useAppStore connect so many application communities?

## Answer

Expanded from graph vocabulary: [use, app, store, connect, api, react, state, workspace]. The graph identifies useAppStore (src/store/useAppStore.ts:L23) as a degree-85 bridge: 46 call edges, 38 import edges, and 1 containment edge. The store composes 13 domain slice factories, including auth, alerts, sync, tasks, projects, sprints, members, whiteboard, teams, wiki, notifications, messages, and settings (src/store/useAppStore.ts:L24-L36). Feature consumers call the shared hook across communities: App() at src/App.tsx:L53, ChatPage() at src/pages/Chat.tsx:L54, Settings() at src/pages/Settings.tsx:L43, Home() at src/pages/Home.tsx:L112, useDeviceAlerts() at src/hooks/useDeviceAlerts.ts:L52, and useUnreadCount() at src/hooks/useUnreadCount.ts:L11. Therefore it bridges because it is the common state integration point for many feature areas, not because those features directly call one another. The graph is undirected, so treat degree and community links as connectivity evidence, while the edge relation and source locations identify observed import/call direction.

## Outcome

- Signal: useful

## Source Nodes

- useAppStore
- App()
- ChatPage()
- Settings()
- Home()
- useDeviceAlerts()
- useUnreadCount()
- createAuthSlice()
- createTasksSlice()
- createSettingsSlice()