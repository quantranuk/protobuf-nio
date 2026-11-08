# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately, not in a public issue: on the repository's
[Security tab](https://github.com/quantranuk/protobuf-nio/security), choose **Report a vulnerability**. Only the
maintainer sees the report, and the fix can be discussed and prepared there before anything is made public.

Include what you can of:

- the affected version;
- what an attacker can do, and from where (for example a client that can reach the server's port);
- how to reproduce it: a small program or test is ideal;
- the settings involved, if it depends on the configuration (for example without `allowedMessageTypes`, or with a
  large `maxMessageSize`).

protobuf-nio is maintained by one person, so please allow a few days for a first reply. Once a fix is released, the
advisory is published with credit to the reporter, unless you'd rather not be named.

## Supported versions

Security fixes go into the latest release. Upgrade to it to get them.

## Scope

protobuf-nio handles bytes from the network, so issues where a peer can do more than it should are in scope, for
example:

- crashing, hanging or exhausting the memory or threads of the other side with little effort, especially with the
  settings recommended in the README's [hardening checklist](README.md#hardening-checklist);
- making the receiver load or initialise classes it shouldn't, or bypassing `allowedMessageTypes`;
- TLS problems in the library's own setup, such as skipping hostname verification when it's enabled.

Out of scope:

- what follows from the documented defaults on a network you don't trust, when the hardening checklist addresses it
  (for example a peer sending messages up to the default 64 MiB `maxMessageSize`);
- vulnerabilities in protobuf-java, the JDK or other dependencies themselves: report those to their projects (but do
  tell us if protobuf-nio uses them in an unsafe way, or should require a fixed version);
- problems in your own listeners or message handling.
