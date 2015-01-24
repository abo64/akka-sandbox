Akka Exercises

1. Pingpong
1.1 Write two actors Ping and Pong that send each other a "Ball" message to and fro
for a predefined number of times (or use scala.util.Random to decide whether the ball is hit or not).
On reception they should do a println to the Console. You might have a Thread.sleep() included to prolong the fun.
1.2 Use Akka's Logging instead of the Console. Additionally, switch on DEBUG logging level and see what you get.
1.3 Use Akka's Remoting feature to run the actors in separate JVMs.

2. Broadcast
Given messages:
type PayloadId = Int
case class Payload(id: PayloadId, content: Any)
case class PayloadBroadcast(howMany: Int, payloads: Seq[Payload])
case class PayloadAck(id: PayloadId)
case object Done
case class Payloads(payloads: Seq[Payload]], chunkSize: Int)
2.1 Write a PayloadConsumer actor that upon reception of a Payload sends back an PayloadAck message to the sender.
2.2 Write a Broadcaster actor that for a PayloadBroadcast message creates howMany PayloadConsumer child actors and then sends the Payloads to them, respectively. After that it should wait for all PayloadAck messages to arrive and then send a Done message back to the sender and stop all children.
2.2.1 You might try to use Akka's Routing feature for the Broadcaster.
2.3 Write a ChunkBroadcaster actor that receives a Payloads message, then creates chunks of size chunkSize from the payloads. It creates child Broadcaster actors (one per chunk) and sends the chunks to them. Finally, after all children have sent their Done message it itself sends a Done message to its sender and stop all children.
Hint: For creating a Seq[Payload] you can use this function
def payloads(howMany: Int): Seq[Payload] =
  Stream.range(0, howMany).map(id => Payload(id , Payload(id, s"content$id")))
