# Reservation Engine - AI Coding Rules

## Owner says
Do not execute Git commands or modify anything inside the `.git` directory. Git operations will be handled manually outside the coding agent.

## 1. General Rule

This project is being developed incrementally for learning and interview preparation.

Priorities are:

1. Correctness
2. Safety
3. Understanding
4. Verification
5. Minimal changes

Do not optimize for speed by making broad, speculative, or unnecessary changes.

---

## 2. Inspect Before Changing Anything

Before modifying any file:

1. Inspect the relevant existing files.
2. Inspect the project structure.
3. Inspect relevant configuration.
4. Understand the current implementation.
5. Identify exactly what needs to change.
6. Only then make the changes.

Never assume that a file, class, dependency, configuration, database object, or feature exists.

---

## 3. Strict Task Scope

Only implement the functionality explicitly requested in the current task.

Do NOT:

- implement future features
- refactor unrelated code
- rename unrelated classes
- reorganize packages unnecessarily
- modify unrelated configuration
- change the architecture
- add "helpful" abstractions that are not required
- implement the next development step automatically

When the requested task is complete, STOP and wait for further instructions.

---

## 4. Destructive Operations Require Explicit User Approval

NEVER perform destructive or potentially irreversible operations without explicit approval from the user.

This includes, but is not limited to:

### Files

- deleting files
- deleting directories
- overwriting unrelated files
- mass-renaming files
- replacing large portions of the project

### Git

- `git reset --hard`
- `git clean`
- force push
- deleting branches
- rewriting history
- modifying `.git`
- deleting commits

### Docker

- removing containers
- removing Docker volumes
- removing Docker images
- `docker system prune`
- `docker volume prune`
- `docker container prune`
- `docker image prune`
- `docker compose down -v`

### Database

- `DROP DATABASE`
- `DROP TABLE`
- `TRUNCATE`
- destructive `DELETE`
- deleting or resetting existing data
- recreating the database
- changing database ownership/permissions

### Operating System

- uninstalling software
- modifying system settings
- modifying Windows configuration
- modifying unrelated environment variables

If any such operation appears necessary:

STOP.

Explain:

1. The exact command/action.
2. Why it is necessary.
3. What it will change/delete.
4. Whether it is reversible.

Then ask the user for explicit approval.

---

## 5. Important Commands

Safe read-only commands and normal development commands may be executed automatically.

Examples:

- inspecting files
- `git status`
- `git diff`
- `git log`
- `docker ps`
- `docker ps -a`
- compiling the project
- running tests
- starting the application

Potentially consequential commands must be explained before execution.

Do not execute risky commands merely because they might solve a problem.

---

## 6. Database Safety

Treat the PostgreSQL database as persistent project data.

NEVER reset, recreate, drop, truncate, or delete database data automatically.

Before any database operation that can modify or delete existing data:

STOP and request explicit approval.

Schema changes required by the current implementation are allowed when they are non-destructive and within task scope.

If a schema change may destroy or alter existing data, STOP and ask first.

---

## 7. Docker Safety

Do not remove or recreate Docker containers, volumes, images, or networks automatically if doing so could destroy data or affect the existing development environment.

Do not run Docker cleanup/prune commands automatically.

Starting an existing required development container is allowed.

If an existing container must be removed/recreated, explain why and ask for approval first.

---

## 8. Git Safety

Do not automatically:

- commit changes
- push changes
- reset changes
- discard working-tree changes
- delete branches
- rewrite history

Before any operation that could discard existing work, STOP and ask for approval.

You may inspect Git status and Git diff automatically.

---

## 9. Dependency Safety

Do not add, remove, upgrade, downgrade, or replace dependencies unless required by the current task.

If a dependency change is required:

1. Explain the dependency.
2. Explain why it is required.
3. Explain whether an existing dependency can solve the problem.
4. Ask for approval before changing dependencies.

Do not introduce future-phase dependencies prematurely.

In particular, do not add:

- Redis
- Kafka
- Redisson
- Kafka clients
- Redis clients
- messaging infrastructure

unless explicitly requested by the current task.

---

## 10. Architecture Protection

The project's documented architecture is authoritative.

Do not silently redesign the architecture.

Do not introduce technologies or patterns simply because they are personally preferred.

The agent may suggest improvements, but suggestions must remain suggestions unless the user explicitly approves implementation.

Do not prematurely implement:

- Redis hot-path inventory
- Redis Lua
- Kafka
- Transactional Outbox
- Payment Worker
- Notification Worker
- Expiry Worker
- Reconciliation Worker
- Redisson distributed locks
- DLQ
- load testing

unless explicitly requested.

---

## 11. Preserve Existing Configuration

Unless explicitly instructed otherwise, preserve the existing:

- Java version
- Spring Boot version
- Maven configuration
- PostgreSQL configuration
- Docker configuration
- application port
- timezone configuration
- package structure
- existing dependencies

Do not change configuration simply to make an error disappear.

If existing configuration appears incorrect:

STOP, explain the problem, and propose the smallest safe fix.

---

## 12. Failure Handling

When a command fails:

1. Read the complete error.
2. Diagnose the likely cause.
3. Explain the diagnosis.
4. Propose ONE corrective action.
5. Execute that action.
6. Verify the result.

Do not continuously try random commands.

Do not make multiple unrelated changes hoping one will work.

For the same underlying problem, make at most TWO corrective attempts.

After two unsuccessful attempts:

STOP and report:

- what failed
- what was tried
- what the evidence indicates
- what you recommend doing next

Wait for user instructions.

---

## 13. Never Hide Errors

Never:

- suppress errors
- ignore failed tests
- ignore build failures
- ignore database errors
- hide warnings that materially affect correctness
- claim success when verification failed

Always report the actual result.

---

## 14. Verification

After implementation:

1. Compile/build the project.
2. Run relevant tests.
3. Start the application when appropriate.
4. Verify the actual behavior.

Do not claim that something works unless it was actually verified.

If something could not be verified, explicitly state:

"Not verified."

---

## 15. Do Not Modify Unrelated Files

Only modify files necessary for the current task.

Before modifying a file, determine why it needs to be changed.

If a change to an unrelated file appears necessary, explain why before making the change.

---

## 16. Do Not Automatically Fix Unrelated Problems

If you encounter an unrelated warning, bug, code smell, formatting issue, dependency issue, or configuration issue:

Do NOT fix it automatically.

Report it separately and continue only if the current task can safely proceed.

If it prevents the current task from working, explain the blocker.

---

## 17. Learning Requirement

This project is being built to develop strong understanding of Java backend engineering.

Do not only provide code.

After every implementation task, provide a learning report containing:

### Files Created

List every file created.

### Files Modified

List every file modified.

### Files Deleted

Explicitly state `NONE` if no files were deleted.

### Dependencies

State whether dependencies changed.

### Database Changes

Explain any schema/database changes.

### Commands Executed

List the important commands actually executed.

### Verification

Explain exactly what was tested and the result.

### Implementation Explanation

Explain what was implemented and why.

### Important Concepts

Explain the important Java, Spring Boot, JPA, database, concurrency, or distributed-systems concepts involved.

### Design Decisions

Explain important design decisions and reasonable alternatives.

### Code Walkthrough

Explain the important parts of the generated code so the user can understand and review it.

### Common Mistakes

Explain mistakes or misunderstandings the user should avoid.

### Interview Connection

Provide relevant SDE-1/backend interview questions and concise answers.

### Manual Review

Tell the user exactly what parts of the implementation they should inspect manually to ensure they understand the code.

---

## 18. No Automatic Progression

The development process is incremental.

If the user asks for Step 1, implement only Step 1.

Do not automatically proceed to Step 2 after completing Step 1.

Do not implement multiple layers/features merely because they are logically related.

Stop after the requested scope is complete.

---

## 19. Conflict Resolution

If the current task conflicts with:

- existing code
- project documentation
- configuration
- database state
- architecture
- these rules

do not silently choose a solution.

STOP and explain the conflict.

Ask the user to decide.

---

## 20. Final Report Format

At the end of every task, use this structure:

### Implementation Summary

### Files Created

### Files Modified

### Files Deleted

### Dependencies Changed

### Database Changes

### Commands Executed

### Verification Results

### Important Concepts

### Design Decisions

### Problems / Warnings

### Interview Questions

### What I Should Review Manually

Then STOP.