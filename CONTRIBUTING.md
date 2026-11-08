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

For maintainers. Releases go to Maven Central with the `release` profile, which builds sources and Javadoc jars, signs
every artifact with GPG and uploads them to the [Central Portal](https://central.sonatype.com):

1. Make sure `master` is green and `CHANGELOG.md`'s `## [Unreleased]` section describes the release.
2. Set the release version (no `-SNAPSHOT`) in the three `pom.xml` files, rename `## [Unreleased]` to
   `## [X.Y.Z] - YYYY-MM-DD` (and its link at the bottom to the `vX.Y.Z` tag), and commit.
3. Run `./mvnw -P release clean deploy`. It needs a `central` server in `~/.m2/settings.xml` with a Portal user token,
   and the signing key in the local `gpg`. To see what would be published without uploading anything, add
   `-Dcentral.skipUpload=true` and look at `target/central-publishing/central-bundle.zip`.
4. On the Central Portal, check the validated deployment and click **Publish**. It can take a while to appear on Maven
   Central.
5. Tag the release commit `vX.Y.Z`, push it, and create a GitHub release with the changelog section as its notes.
6. Set the next development version (`X.Y.(Z+1)-SNAPSHOT`), add a new empty `## [Unreleased]` section, and commit.
