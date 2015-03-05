package org.sandbox.akka.stream

import akka.actor.ActorSystem
import java.io.{ FileOutputStream, PrintWriter }
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Source
import akka.stream.ActorFlowMaterializer

object Smoketest extends App {

    // actor system and implicit materializer
    implicit val system = ActorSystem("Sys")
    // execution context
    import system.dispatcher

    implicit val materializer = ActorFlowMaterializer()

    val roundtripFile = io.Source.fromFile("src/main/resources/roundtrips.txt", "utf-8")
    val roundtripJsonSource: Source[String, Unit] = Source(() => roundtripFile.getLines)

    type Roundtrip = Int
    val roundtripSource: Source[Roundtrip, Unit] = roundtripJsonSource map (_.toInt)
}