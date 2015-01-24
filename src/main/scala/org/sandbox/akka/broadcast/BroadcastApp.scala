package org.sandbox.akka.broadcast

import akka.actor.Actor

object BroadcastApp extends App {
  type PayloadId = Int
  case class Payload(id: PayloadId, content: Any)
  case class PayloadBroadcast(howMany: Int, payloads: Seq[Payload])
  case class PayloadAck(id: PayloadId)
  case object Done
  case class Payloads(payloads: Seq[Payload], chunkSize: Int)

  class PayloadConsumer extends Actor {
    def receive: Actor.Receive = {
      case Payload(id, _) => sender ! PayloadAck(id)
    }
  }
}
