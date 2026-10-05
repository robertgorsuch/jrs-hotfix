# Contributing to jrs-hotfix

Thank you for helping. jrs-hotfix changes production JasperReports Server installations, so a contribution is judged first on whether it keeps a server safe: every change planned, journaled and reversible, and nothing touched that the plan does not name. This page says how to report a problem, propose a change and get a pull request merged.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting a problem

- **A security vulnerability:** do not open an issue. Report it privately, as a GitHub security advisory or by email: see [SECURITY.md](SECURITY.md).
- **A bug:** open an issue with the *Bug report* form. It asks for what the [wiki's troubleshooting page](https://github.com/robertgorsuch/jrs-hotfix/wiki/Diagnostics-and-Troubleshooting#getting-help) lists: `jrs-hotfix --version`, the exit code, `jrs-hotfix runs show <id>`, the run's `run.log`, the OS and the service kind. Review host names and paths before you paste them; the tool redacts secrets, not names.
- **A problem in JasperReports Server itself,** or in a hotfix package's content: that is for Jaspersoft Support, not this repository.

## Proposing a change

Open an issue with the *Feature request* form before you write a large change, so the approach can be agreed first. jrs-hotfix is deliberately small (0.4.0 removed four features nobody needed, and 0.6.0 the ledger; see the [release notes](docs/releases/)), and some things are out of scope by design:

- inputs other than official Jaspersoft hotfix packages (custom-built hotfixes and the old signed bundle format were dropped);
- Community edition, application servers other than Tomcat, platforms other than Windows and Linux x86-64;
- running buildomatic, logging in to the server, or anything that needs the server's or the database's credentials.

A design change of any size is written down first: the specs under [`docs/`](docs/) (`spec.md`, `spec-customized-servers.md`, `spec-no-ledger.md`, `spec-targets.md`) and the decisions under [`docs/decisions/`](docs/decisions/) are the record of how jrs-hotfix got its shape.

## Development setup

You need a clone and **JDK 21**; the Maven Wrapper is in the repository. Your default `java` may be older: the scripts look for JDK 21 and select it, and `JRSHOTFIX_JDK` names it when it is elsewhere. A build run with an older JDK stops at once with a message saying so.

On Linux and in Git Bash use `scripts/*.sh`; from `cmd` on Windows, `scripts\*.cmd`.

| Tier | Command | When |
|---|---|---|
| 1. Named unit tests | `scripts/fast.sh test <TestClass[,TestClass]>` | After every edit |
| 2. Acceptance tests | `scripts/fast.sh accept [TestClass]` | Before a pull request that touches a command or a step |
| 3. Full gate | `scripts/mvn.sh verify` | Before every pull request; CI runs it on Ubuntu and Windows |

Tier 1 compiles with Error Prone and `-Werror`. Do not run `mvn -Dtest=...` yourself: a partial run cannot meet the coverage floor, and `fast` sets what it needs. The [Testing Locally](https://github.com/robertgorsuch/jrs-hotfix/wiki/Testing-Locally) wiki page describes the fixtures (`HotfixFixture`, `Packages`, `SiteFixture`, `Wars`) and how to try a build by hand against a **test** server, never a production one.

## Code

- **Format:** google-java-format, enforced by the build. Run `scripts/fast.sh fmt` before committing.
- **Static checks:** Error Prone with `-Werror`; a warning fails the build.
- **Coverage:** the build fails below 70% line coverage.
- **Match the code around you.** Classes state their invariants in the class Javadoc; comments say why, not what. Read the neighbouring classes before adding one.
- **Every change to a server goes through a plan:** steps with a precheck, an action and a compensation, journaled before they are reported. A new mutating feature that bypasses the engine will not be merged.
- **Tests come with the change.** A bug fix starts with a test that fails without it. Behaviour that only shows through the commands is tested through the commands (`CommandsTest`, `MenuTest` and their neighbours).
- **Secrets never reach a log or the terminal.** Anything that prints goes through the redactor.

## Commits and pull requests

- Work on a branch, never on `main`.
- Commit messages start with a type: `feat:`, `fix:`, `docs:`, `build:`, `refactor(<area>):`, and `feat!:` for a change a script must adapt to. The subject says what the change does, in the present tense, and the body says why.
- One change per pull request. Fill in the template: what changed, what an operator or a script will notice, and how it was tested.
- Update the documentation the change affects: [`README.md`](README.md), and the page `jrs-hotfix --docs` prints ([`src/main/resources/docs/README.md`](src/main/resources/docs/README.md)) for anything an operator sees. A user-visible change is described in the next release's notes by the maintainer; say in the pull request what it should say.
- CI must be green on Ubuntu and Windows before a merge.

## Releases

Releases are cut by the maintainer: release notes in `docs/releases/v<version>.md`, the version set in `pom.xml`, then a `v<version>` tag, which builds, smoke-tests and publishes the archives for each OS, the jar and `SHA256SUMS`.

## Licence

jrs-hotfix is licensed under the GNU General Public License, version 3 only (`GPL-3.0-only`). By submitting a contribution you agree that it is licensed under the same terms.
