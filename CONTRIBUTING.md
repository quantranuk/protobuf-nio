# Contributing to protobuf-nio

Thanks for your interest! Bug reports, fixes and improvements are welcome.

- **Bugs and feature ideas:** open an [issue](https://github.com/quantranuk/protobuf-nio/issues/new/choose). For a
  larger change, please open an issue to discuss it before writing the code.
- **Security vulnerabilities:** don't open an issue; follow [SECURITY.md](SECURITY.md).
- **Questions:** open an issue too.

## Building and testing

You need JDK 25 or later. Always build with the Maven Wrapper (`./mvnw`), which downloads the right Maven version.

```bash
./mvnw clean install                                                     # build everything and run all tests
./mvnw test -pl protobuf-nio-core                                        # core tests only
./mvnw test -pl protobuf-nio-core -Dtest=ConnectionTest                  # one test class
./mvnw test -pl protobuf-nio-core -Dtest=ConnectionTest#closeFlushesQueuedMessages   # one test
./mvnw -pl protobuf-nio-core javadoc:javadoc                             # check the Javadoc
```

CI (GitHub Actions) runs `./mvnw -B verify javadoc:javadoc` on every push and pull request.

### Tests

- The tests in `protobuf-nio-core` are JUnit tests. They run on the module path, as the library's users do.
- `SoakTest` runs eight clients for a few seconds, closing sessions at random from both sides. It prints its random
  seed; pass it back with `-Dsoak.seed=<seed>` to replay the same choices.
- `ModulePathTest` compiles a small consumer module with `javac` and runs it in a separate JVM, against the real library
  module.
- `TlsTest` generates its certificates with the JDK's `keytool` at test time, so no keystore is committed.
- Many tests involve timing. CI runners are small, so before sending a change, run the tests on two CPUs a few times to
  catch timing assumptions that only hold on a fast machine (on Linux):

  ```bash
  taskset -c 0,1 ./mvnw test -pl protobuf-nio-core
  ```

### Samples

`protobuf-nio-sample` holds runnable examples and benchmarks (plain `main()` programs, not tests). After
`./mvnw install`, start the server, then run a client the same way in another terminal:

```bash
./mvnw -q -pl protobuf-nio-sample exec:java -Dexec.mainClass=io.github.quantranuk.protobuf.nio.sample.SampleServer
./mvnw -q -pl protobuf-nio-sample exec:java -Dexec.mainClass=io.github.quantranuk.protobuf.nio.sample.SampleClientBenchmarkTest
```

## Making a change

1. Fork the repository and create a branch.
2. Make the change, with a test that fails without it where that's practical.
3. Run `./mvnw clean install` (and the two-CPU run above if you touched the connection code).
4. Add a line to [CHANGELOG.md](CHANGELOG.md) under `## [Unreleased]` for anything a user would notice.
5. Open a pull request that explains what the change does and why.

Guidelines:

- **Public API:** only `io.github.quantranuk.protobuf.nio` is exported; everything else goes in the `internal` package.
  Changes to the public API follow the README's [versioning policy](README.md#versioning). Use `MessageLite`, not
  `Message`, in API code.
- **Javadoc:** every public type and member of the API package needs Javadoc, with `@param`, `@return` and `@throws`;
  missing documentation fails the build.
- **Performance:** the send and receive paths avoid allocating per message and block once per batch rather than once
  per message. `AllocationTest` checks the allocations; keep it passing.
- **Dependencies:** the library depends only on `protobuf-java` and `slf4j-api`. Don't add others, and don't add a
  logging backend outside test scope.
- **Versions:** dependency and plugin versions live as properties in the root `pom.xml`.
- **Wire format:** changes to the preamble or frames need a protocol version (see the README's
  [Wire format](README.md#wire-format) section) and a discussion first.

By contributing, you agree that your contributions are licensed under the project's [MIT License](LICENSE).

## Releasing

For maintainers. Releases go to Maven Central through the [Release workflow](.github/workflows/release.yml), which
builds, runs the tests, builds sources and Javadoc jars, signs every artifact and uploads them to the
[Central Portal](https://central.sonatype.com). It uses four repository secrets: `CENTRAL_TOKEN_USERNAME` and
`CENTRAL_TOKEN_PASSWORD` (a Portal user token), `MAVEN_GPG_KEY` (the ASCII-armored secret signing key) and
`MAVEN_GPG_PASSPHRASE`.

1. **Dry run.** On the repository's Actions tab, open **Release** and click **Run workflow** on `master`. It does the
   whole release for the current version without `-SNAPSHOT`, except the upload: the bundle that would be published
   and the release notes are kept as an artifact of the run. This checks the secrets and the signing too.
2. **Release commit.** Set the release version (no `-SNAPSHOT`) in the three `pom.xml` files, rename
   `## [Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD` (and point its link at the bottom to
   `https://github.com/quantranuk/protobuf-nio/releases/tag/vX.Y.Z`), then commit and push.
3. **Tag.** `git tag vX.Y.Z && git push origin vX.Y.Z`. The workflow checks that the tag matches the POM version and
   that `CHANGELOG.md` has a `## [X.Y.Z]` section, then uploads and creates the GitHub release with that section as its
   notes.
4. **Publish.** On the Central Portal, under **Deployments**, check the validated deployment and click **Publish**. It
   can take a while to appear on Maven Central. A published version can never be changed or removed: a mistake needs a
   new version.
5. **Next version.** Set `X.Y.(Z+1)-SNAPSHOT` in the three `pom.xml` files, add a new empty `## [Unreleased]` section
   (with its link to `https://github.com/quantranuk/protobuf-nio/compare/vX.Y.Z...master`), then commit and push.

If the workflow fails before uploading, fix the problem, move the tag to the fixed commit
(`git tag -f vX.Y.Z && git push -f origin vX.Y.Z`) and it runs again. If it fails after uploading, drop the deployment
on the Portal first.

To release from your own machine instead, run `./mvnw -P release clean deploy` from the release commit. It needs a
`central` server in `~/.m2/settings.xml` with the Portal token, and the signing key in your local `gpg`;
`-Dcentral.skipUpload=true` stops after building `target/central-publishing/central-bundle.zip`.
