# Agent guide

Tool-agnostic guide for any coding agent (Codex, Cursor, Claude Code, or another) working in this repo. `AGENTS.md` is the one standard: an agent either reads it or it does not — the repo carries no per-tool shim files (`CLAUDE.md`, `.cursor/rules/`, etc.). A tool that ignores `AGENTS.md` is a limitation of that tool, not something the repo works around.

## The one rule that outranks this file

**Humans are the first developers. [README.md](README.md) outranks this file.** It is the human-facing description of what this template provides and how to use it. This file holds only agent-specific operational hints: how to navigate, build, and run the repo.

## What this repo is

A Java application template on Gradle with the Kotlin DSL, targeting Java 25 LTS. The sample code is a demonstration seam, not a feature set.

- **Everything here is inherited wholesale** by every project generated from it. A dependency added here is a dependency every generated project carries, so add one only when the template itself needs it.
- **Every version lives in [gradle/libs.versions.toml](gradle/libs.versions.toml).** Never write a version literal in `build.gradle.kts`; declare it in the catalog and reference it as `libs.<alias>`.
- The Gradle wrapper is committed and authoritative. Build through `./gradlew`, never through a Gradle on `PATH`, and change the version with `gradle wrapper --gradle-version <v>` so the wrapper properties and jar stay in step.
- The JDK is fixed by the toolchain block in `build.gradle.kts`, so the build compiles against the same JDK everywhere regardless of `JAVA_HOME`. Bumping Java means editing the `java` version in the catalog and the `java` tool in `mise.toml` together.
- `AppConfig` is the pattern for configuration: HOCON defaults in `application.conf`, overridden by environment variables, parsed once into a record. Extend it rather than reading `System.getenv` from scattered call sites.
- The group, version, and `rootProject.name` are placeholders carrying a `TODO`. A generated project rewrites them; this repo does not.

## Working in the repo

- The task runner is `mise` (root `mise.toml`); commands are `mise run <task>`. `mise run setup` compiles once to warm the dependency cache and installs the git hooks.
- Tools are pinned and installed by `mise`; a shell with mise inactive resolves a bare tool call (`java`, `cog`, `act`, …) from `PATH`, at an unpinned version. `mise run <task>` activates the toolchain for that task's duration, a bare tool call does not. A bare `./gradlew` outside mise fails with `JAVA_HOME is not set` — that is the pin working.
- `mise run run` runs from source; `mise run build` produces the shaded `bin/app.jar` and `mise run start` runs it.
- `mise run act` replays the pull request workflow locally with [act](https://github.com/nektos/act). It reads secrets from `.env` — copy `.env.example` first.
- Before finishing a change, run `mise run check` to format, lint, and test; `mise run format` / `lint` / `test` run each individually.

## Conventions

- **Formatting is not a discussion.** Spotless with `palantir-java-format` owns it; run `mise run format` and move on. Never hand-format to match a preference the formatter will undo.
- **Warnings are errors.** The build compiles with `-Xlint:all -Werror`, plus Error Prone and NullAway. A finding is a defect to fix, not a check to suppress: reach for `@SuppressWarnings` only with a comment saying why the analysis is wrong here.
- **NullAway treats `io.github.tabmadi` as non-null by default.** Anything nullable is annotated `@Nullable` (JSpecify); an unannotated reference is a promise the compiler enforces.
- Tests use JUnit 5 with AssertJ assertions and a `@DisplayName` describing the behaviour, not the method name. JaCoCo enforces a line coverage floor in `mise run test`; raise the floor rather than widening the exclusion list.
- Prefer records for data, `final` classes with private constructors for static holders, and constructor injection over field mutation.

## Commits

- **Conventional Commits, enforced.** `cog verify` runs on `commit-msg` and `cog check` on `pre-push`, so a malformed message is rejected locally before CI sees it.
- Commit messages are a title only — no body, no footer.
- Never push unless asked to.
