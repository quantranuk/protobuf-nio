/**
 * protobuf-nio: protobuf messages over TCP, on virtual threads. The API is the {@code io.github.quantranuk.protobuf.nio}
 * package; everything else is internal.
 * <p>
 * Received messages are resolved by class name: on the module path, the packages of your generated protobuf classes
 * must be exported (or opened) to this module, so that it can call their {@code getDefaultInstance()}:
 * <pre>{@code
 * module com.example.app {
 *     requires io.github.quantranuk.protobuf.nio;
 *     exports com.example.app.proto to io.github.quantranuk.protobuf.nio;
 * }
 * }</pre>
 */
@SuppressWarnings("requires-transitive-automatic")   // protobuf-java has no module descriptor, only an automatic name
module io.github.quantranuk.protobuf.nio {
    requires transitive com.google.protobuf;   // MessageLite is part of the API
    requires org.slf4j;

    exports io.github.quantranuk.protobuf.nio;
}
