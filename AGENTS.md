# Blaze Launcher: Rules and Architecture for Agents

Blaze Launcher is a Minecraft: Java Edition launcher for Android based on Zalith Launcher 2 (ZL2), integrating the Starling/Horizon Rust engine.

## Core Rules

1. **Compile Only Through Workflows**: This environment (Termux/phone) cannot compile full Android APKs or native code. All compilation, unit tests, and releases must run strictly through GitHub Actions workflows.
2. **Evidence Required for Every Claim**: Paste raw command output (`gh run view`, `git status`, `apksigner verify`, etc.) for every verification claim. Never claim a test or build passed without showing the log output.
3. **One Task per Branch**: Never commit directly to `main`. Every unit of work must have its own feature branch (`feat/...`).
4. **No Mass Renames / Refactors**: Keep ZL2 codebase intact. `namespace` remains `com.movtery.zalithlauncher` to avoid breaking R classes and JNI bindings. Only `applicationId` changes to `io.github.<login>.blazelauncher`. Do not refactor untouched upstream code.
5. **Scope Guard**: Run `scripts/scope_guard.sh` to enforce allowlists per task. Do not touch files outside the task scope.
6. **Zero Secret Leaks**: Never print, echo, cat, or commit keystores, passwords, or tokens. Never place them in logs, comments, or commit messages. Secrets live only in GitHub Repository Secrets.
7. **Keep License Headers**: Keep all ZL2 GPL-3.0 and upstream license headers and credits intact. New code follows GPL-3.0.
8. **Three ZL2 Hook Points Only**:
   - (i) Version model `runtime` field (`Version.kt` / `VersionConfig.kt`).
   - (ii) Launch dispatcher (`GameLauncher.kt`).
   - (iii) Input sink interface (`TouchController.kt` / `Events.kt`).
9. **Modular Development**: All new runtime and engine features must reside in new packages (e.g., `io.github.<login>.blazelauncher.rustruntime.*`) or submodules (`engine/`).
10. **Sub-Agent Coordination**: Maximum 4 sub-agents with disjoint file assignments. Shared files (Gradle files, manifests, workflows, strings.xml) belong exclusively to the lead agent.
11. **Tool Resilience**: When a tool is blocked (e.g. ripgrep missing binary), immediately choose the nearest working alternative (`git grep`, bash) and continue without delay.
12. **Report Format**: Every completion report must conclude with:
    - Files created
    - Files modified
    - Files untouched
    - CI run URL
    - UNVERIFIED items list
