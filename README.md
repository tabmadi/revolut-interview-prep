# 🚀 Java Template

[![Java Version](https://img.shields.io/badge/Java-25_LTS-orange.svg)](https://adoptium.net/temurin/releases/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Gradle](https://img.shields.io/badge/Gradle-Kotlin_DSL-02303A.svg)](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
[![mise](https://img.shields.io/badge/mise-managed-blue.svg)](https://mise.jdx.dev)
[![lefthook](https://img.shields.io/badge/lefthook-enabled-green.svg)](https://github.com/evilmartians/lefthook)
[![Spotless](https://img.shields.io/badge/Spotless-enabled-brightgreen.svg)](https://github.com/diffplug/spotless)
[![Error Prone](https://img.shields.io/badge/Error_Prone-enabled-brightgreen.svg)](https://errorprone.info/)
[![act](https://img.shields.io/badge/act-CI_simulation-blueviolet.svg)](https://github.com/nektos/act)

This is a modern GitHub template repository for Java projects. Use this template to create a new Java application with a
standardized structure, automated tooling, and best practices built-in.

## ✨ Features

- ☕ **Modern Java**: Java 25 LTS via a Gradle toolchain, so local builds and CI compile against the exact same JDK.
- 🐘 **Gradle with the Kotlin DSL**: Type-safe build scripts, a committed wrapper, and all dependency versions declared
  once in a [version catalog](gradle/libs.versions.toml).
- 🐛 **Bug Detection at Compile Time**: **Error Prone** plus **NullAway** turn whole classes of runtime failures —
  including `NullPointerException` — into compile errors.
- 🎨 **Zero-Debate Formatting**: **Spotless** with `palantir-java-format` formats Java, Gradle, and misc files.
- 🧪 **Tests and Coverage**: **JUnit 5** with **AssertJ** assertions, and **JaCoCo** enforcing a coverage floor as part
  of `check`.
- 🔧 **Modern Tooling**: Pre-configured with **mise** for tool and task management, **Lefthook** for Git hooks,
  **Cocogitto** for Conventional Commits, and **act** for local CI simulation.
- 📦 **Single-File Distribution**: A shaded, runnable `bin/app.jar` built by the **Shadow** plugin.

## 🚀 Quick Start

### Prerequisites

- [mise](https://mise.jdx.dev) - A multi-language version manager and task runner.
- [Java](https://adoptium.net/) - JDK 25 (managed by mise).
- [act](https://github.com/nektos/act) - Run your GitHub Actions locally.

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/tabmadi/java-template.git
   cd java-template
   ```

2. **Setup environment**:
   ```bash
   # Install all required tools using mise
   mise install

   # Set up the project (download dependencies and install git hooks)
   mise run setup
   ```

## 🏃‍♂️ Usage

Instructions on how to run the project.

```bash
# Run the application directly (development)
mise run run

# Build and run the production jar
mise run build
java -jar bin/app.jar
```

## 🛠️ Development

### Available Scripts

| Script            | Description                                     |
|-------------------|-------------------------------------------------|
| `mise run setup`  | Set up the project dependencies                 |
| `mise run clean`  | Clean build artifacts                           |
| `mise run format` | Format the code (Spotless)                      |
| `mise run lint`   | Run Spotless checks and compile with Error Prone |
| `mise run test`   | Run tests and verify coverage                   |
| `mise run check`  | Format, lint, and test                          |
| `mise run build`  | Build the shaded jar into `bin/app.jar`         |
| `mise run run`    | Run the application                             |
| `mise run start`  | Build, then run the jar                         |
| `mise run act`    | Simulate CI locally with act                    |

### 🧹 Code Quality

Formatting is handled by **Spotless**, and correctness by **Error Prone** + **NullAway**, which run as part of every
compilation. Warnings are errors (`-Xlint:all -Werror`), so the build fails on anything the compiler flags.

```bash
# Check formatting and compile with all static analysis enabled
mise run lint

# Auto-format the code
mise run format
```

### 🧪 Tests

Tests use **JUnit 5** with **AssertJ** assertions. **JaCoCo** enforces a line coverage floor of 80% (excluding the
entry point); the HTML report lands in `build/reports/jacoco/test/html/index.html`.

```bash
mise run test
```

### 🪝 Git Hooks & Conventional Commits

This project uses **Lefthook** for Git hooks and follows **Conventional Commits**.

- **Pre-commit**: Formats code and runs the linters.
- **Commit-msg**: Validates commit message format.
- **Pre-push**: Final Conventional Commits check on the whole branch.

#### Conventional Commits Example:

```bash
# ✅ Valid commit messages
git commit -m "feat: add user authentication"
git commit -m "fix: resolve memory leak"
```

## 📁 Project Structure

```
.
├── src/
│   ├── main/
│   │   ├── java/           # Application code
│   │   └── resources/      # application.conf, logback.xml
│   └── test/java/          # Tests
├── gradle/
│   ├── libs.versions.toml  # Dependency version catalog
│   └── wrapper/            # Gradle wrapper
├── scripts/                # Helper scripts
├── bin/                    # Built jar (created by build)
├── .github/                # GitHub Actions and act configuration
├── build.gradle.kts        # Build configuration
├── mise.toml               # Mise configuration
└── README.md               # You are here! 📍
```

## ⚙️ Configuration

| File                        | Purpose              | Key Features                                             |
|-----------------------------|----------------------|----------------------------------------------------------|
| **mise.toml**               | Mise task runner     | Tool versions, task definitions                          |
| **build.gradle.kts**        | Gradle build         | Toolchain, Spotless, Error Prone, NullAway, JaCoCo, Shadow |
| **gradle/libs.versions.toml** | Version catalog    | Single source of truth for dependency versions           |
| **.lefthook.yml**           | Git hooks            | Pre-commit linting, automated quality checks             |
| **src/main/resources/application.conf** | App config | HOCON defaults with environment variable overrides     |

Application settings are read with [Typesafe Config](https://github.com/lightbend/config) into a typed `AppConfig`
record. Every key in `application.conf` can be overridden by an environment variable — see [.env.example](.env.example).

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (**Conventional Commits**)
4. Push to the branch
5. Open a Pull Request

## 🔒 Security

Please see [SECURITY.md](SECURITY.md) for our security policy and how to report security vulnerabilities.

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [Temurin](https://adoptium.net/) for the JDK builds
- [Gradle](https://gradle.org/) for the build system
- [Spotless](https://github.com/diffplug/spotless) and [palantir-java-format](https://github.com/palantir/palantir-java-format) for formatting
- [Error Prone](https://errorprone.info/) and [NullAway](https://github.com/uber/NullAway) for compile-time bug detection
- [mise](https://mise.jdx.dev/) for tool and task management
- [Lefthook](https://github.com/evilmartians/lefthook) for fast and reliable Git hooks
- [act](https://github.com/nektos/act) for local CI simulation

---

**Happy coding! 🎉** If you find this template useful, please give it a ⭐️
