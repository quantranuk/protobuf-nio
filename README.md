[![Build Status](https://travis-ci.org/quantranuk/protobuf-nio.svg?branch=master)](https://travis-ci.org/quantranuk/protobuf-nio)
# protobuf-nio
Simple library to send and receive [protobuf messages](https://developers.google.com/protocol-buffers/) using Java NIO sockets

##### Pros
- Pure Java NIO sockets (no netty / mina dependencies)
- Protobuf-friendly API's
- Good throughput
- Can handle many connections

##### Cons
- No support for SSL/TLS yet

## Getting Started

### Server side
##### Create server and connect
```
// Create server listening to port 3456
ProtoServerocketChannel server = ProtoChannelFactory.newServer(3456).build();
 
// Register message handlers
server.addMessageReceivedHandler(this::onMessageReceived);
 
// Start the server
server.start();
 
// Sending message
// This call will not block.
server.sendMessage(protobufMessage);
```

##### ProtoServerSocketChannel interface
```
public interface ProtoServerSocketChannel { 
    void start() throws IOException;
    void stop();
    void sendMessage(SocketAddress socketAddress, Message message);
 
    void addConnectionHandler(ConnectionHandler handler);
    void addDisconnectionHandler(DisconnectionHandler handler);
    void addMessageReceivedHandler(MessageReceivedHandler handler);
    void addMessageSentHandler(MessageSentHandler handler);
    void addMessageSendFailureHandler(MessageSendFailureHandler handler);
    ...
}
```

### Client side
##### Create client and connect
```
// Create client to connect to localhost:3456
ProtoSocketChannel client = ProtoChannelFactory.newClient("localhost", 3456).build();
 
// Register message handlers
client.addMessageReceivedHandler(this::onMessageReceived);
 
// Connect
client.connect();
 
// Sending message
// This call will not block
client.sendMessage(protobufMessage);
```

##### ProtoSocketChannel interface
```
public interface ProtoSocketChannel { 
    void connect();
    void disconnect();
    void sendMessage(Message message);
 
    void addConnectionHandler(ConnectionHandler handler);
    void addDisconnectionHandler(DisconnectionHandler handler);
    void addMessageReceivedHandler(MessageReceivedHandler handler);
    void addMessageSentHandler(MessageSentHandler handler);
    void addMessageSendFailureHandler(MessageSendFailureHandler handler);
    ...
}
```

## Benchmark
#### Throughput
With buffer size = 8 Kb
```
SampleClientBenchmarkTest - Sending and receiving 1000000 message took 2145 milliseconds
SampleClientBenchmarkTest - Throughput: 466.20 messages per millisecond (round-trip)
SampleClientBenchmarkTest - Average: 2.15 microseconds per message
```
#### Multiple connections
```
...
MultiClientsTest - Client 14996 is connected
MultiClientsTest - Client 14997 is connected
MultiClientsTest - Client 14998 is connected
MultiClientsTest - Client 14999 is connected
MultiClientsTest - All 15000 clients are connected
MultiClientsTest - All HeartBeat response messages have been received. Closing all clients..
```