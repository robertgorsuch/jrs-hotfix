## What and why

<!-- What this changes, and the problem it solves. Link the issue: "Closes #123". -->

## What an operator or a script will notice

<!-- New or changed commands, options, menu entries, messages, exit codes or files in the home.
     "Nothing" is a fine answer for a refactor. Anything a script must adapt to needs a `feat!:` commit. -->

## Safety

<!-- Delete what does not apply. -->
- [ ] Every change to a server, a WAR or the home goes through a plan: precheck, action, compensation, journaled.
- [ ] A failure part way leaves the server as it was, or recoverable with `runs resume` / `runs undo`.
- [ ] Nothing new reaches the terminal or a log without passing the redactor.

## How it was tested

- [ ] A test that fails without the change (for a bug fix)
- [ ] `scripts/fast.sh test <the classes touched>`
- [ ] `scripts/fast.sh accept` (for a change to a command or a step)
- [ ] `scripts/mvn.sh verify`
- [ ] By hand against a test server (say which release, OS and service kind)

## Documentation

- [ ] `README.md`
- [ ] The `--docs` page (`src/main/resources/docs/README.md`)
- [ ] Nothing an operator sees changed
- [ ] For the release notes: <!-- one line describing the change for users, or "none" -->
