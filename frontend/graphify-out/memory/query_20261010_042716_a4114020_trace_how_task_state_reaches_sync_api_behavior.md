---
type: "query"
date: "2026-10-10T04:27:16.755300+00:00"
question: "Trace how task state reaches sync/API behavior"
contributor: "graphify"
outcome: "useful"
source_nodes: ["useAppStore", "createTasksSlice()", "createSyncSlice()", "apiCreateTask()", "apiUpdateTask()", "apiDeleteTask()", "apiUpdateTaskStatus()", "apiListTasks()", "mapTask()"]
---

# Q: Trace how task state reaches sync/API behavior

## Answer

Expanded from graph vocabulary: [task, tasks, state, sync, api, create, update, slice, project]. The graph shows useAppStore composes createSyncSlice() at src/store/useAppStore.ts:L26 and createTasksSlice() at L27. They are sibling slices in one shared store, not a direct runtime call from sync to tasks. createTasksSlice() (src/store/slices/tasks.ts:L25) calls apiCreateTask() at L30, apiUpdateTask() at L53, apiDeleteTask() at L78, and apiUpdateTaskStatus() at L93; it also calls mapTask() at L40. createSyncSlice() (src/store/slices/sync.ts:L15) calls apiListTasks() at L26 and has an INFERRED indirect_call edge to mapTask() at L61; it also loads users, projects, and sprints. apiListTasks() is called from sync.ts:L26, while apiUpdateTask() is called from tasks.ts:L53. Thus task mutations go through the task slice's write APIs, while sync independently fetches task records and maps them into shared state. The graph path between slices passes through module/file containment and imports; it does not establish a runtime task-update-to-sync callback.

## Outcome

- Signal: useful

## Source Nodes

- useAppStore
- createTasksSlice()
- createSyncSlice()
- apiCreateTask()
- apiUpdateTask()
- apiDeleteTask()
- apiUpdateTaskStatus()
- apiListTasks()
- mapTask()