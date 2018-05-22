[![Build Status](https://travis-ci.org/quantranuk/protobuf-nio.svg?branch=master)](https://travis-ci.org/quantranuk/protobuf-nio)
# protobuf-nio
Simple library to send and receive [protobuf messages](https://developers.google.com/protocol-buffers/) using Java NIO sockets

##### Pros
- Pure Java NIO sockets (no netty / mina dependencies)
- Protobuf-friendly API's
- Good throughput
- Scalable: Handles many connections from a single thread

##### Cons
- No support for SSL/TLS yet

_Note: due to the way [SSL Engine](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLEngine.html) is implemented, adding SSL/TLS support will require major change to the code, this is also complained in [here](https://jfarcand.wordpress.com/2006/09/21/tricks-and-tips-with-nio-part-v-ssl-and-nio-friend-or-foe) and [here](https://stackoverflow.com/questions/9118367/java-nio-channels-and-tls)_

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

## Benchmark
#### Throughput
With buffer size = 8 Kb
```
SampleClientBenchmarkTest - Sending and receiving 1000000 message took 2145 milliseconds
SampleClientBenchmarkTest - Throughput: 466.20 messages per millisecond (round-trip)
```
#### Handle multiple connections from a single thread
```
...
MultiClientsTest - Client 14996 is connected
MultiClientsTest - Client 14997 is connected
MultiClientsTest - Client 14998 is connected
MultiClientsTest - Client 14999 is connected
MultiClientsTest - All 15000 clients are connected
MultiClientsTest - All HeartBeat response messages have been received. Closing all clients..
```