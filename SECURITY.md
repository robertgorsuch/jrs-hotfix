# Security policy

jrs-hotfix runs with the rights of the account that owns a JasperReports Server installation, stops and starts its service, and replaces files in its webapp. A flaw in it can hurt a production server, so reports are welcome and taken seriously.

## Supported versions

Only the latest release receives fixes. A fix is released as a new version; older releases are not patched.

| Version | Supported |
|---|---|
| 0.8.x (latest) | Yes |
| 0.7.x and older | No: upgrade to the latest release |

Upgrading is safe: a home written by an earlier release is read as it is (see [Compatibility](https://github.com/robertgorsuch/jrs-hotfix/wiki/Compatibility)).

## Reporting a vulnerability

**Do not open a public issue, pull request or discussion for a vulnerability.**

Report it privately, in either of two ways:

- **On GitHub (preferred):** [open a private security advisory](https://github.com/robertgorsuch/jrs-hotfix/security/advisories/new) from the repository's **Security** tab ("Report a vulnerability"). Only you and the maintainer see it.
- **By email:** write to **robert.gorsuch@actian.com** with the subject `jrs-hotfix security`.

Include:

- the jrs-hotfix version (`jrs-hotfix --version`), the OS, and the service kind in `settings.json`;
- what an attacker can do, and what they need first (an account on the server, write access to a directory, a crafted package or WAR);
- the steps to reproduce it, as small as you can make them, with any files involved;
- whether you want to be credited, and how.

You will get a reply in the advisory, or by email. The fix is worked on privately, released as a new version, and described in that release's notes; the report is made public once a fixed release is out, with credit if you want it.

## What is in scope

Anything in jrs-hotfix itself, for example:

- a secret (a password from an installer-written properties file, a keystore password, a session token) reaching the terminal, `run.log`, `plan.json` or any other file jrs-hotfix writes, past the redactor;
- a file written, replaced or deleted outside what the plan names: outside the webapp, the installation, the home and the outputs named with `--out`, or a path in a package, WAR or distribution that escapes its directory (zip slip);
- anything in a package, WAR or distribution being executed, or a manual step from a hotfix readme being run (they are printed, never run);
- a command line built so that input reaches a shell;
- the package checksum question, the plan confirmation, or the run lock being bypassed, or a pending run's recovery being skipped;
- a rollback restoring files with the wrong owner or permissions;
- the release archives, the jar or `SHA256SUMS` not matching what the release workflow built.

## What is not

- **Vulnerabilities in JasperReports Server, Tomcat or a hotfix package's content.** Report those to Jaspersoft Support. jrs-hotfix installs the vendor's packages as they are.
- An account that can already write the installation or the home doing so through jrs-hotfix: it is designed to be run by that account.
- A package you were told was official but was not: jrs-hotfix shows its SHA-256 and asks you to compare it with the support portal; `--yes` skips that question, and the run log records that it was skipped.

## How jrs-hotfix limits the damage

The [Security](https://github.com/robertgorsuch/jrs-hotfix/wiki/Security) and [Architecture and Safety Model](https://github.com/robertgorsuch/jrs-hotfix/wiki/Architecture-and-Safety-Model) wiki pages describe the design: no stored credentials, one unauthenticated network call to the server's own `serverInfo`, redaction of everything that is printed or logged, processes started with argument lists and never through a shell, and every change planned, journaled and snapshotted so that it can be undone.

To check a download, compare it with `SHA256SUMS` on the release page; the README's [Checking your download](README.md#checking-your-download) section shows how on Linux and Windows.
