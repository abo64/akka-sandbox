object Worksheet {
  val x = Stream.range(1, 5).take(2)              //> x  : scala.collection.immutable.Stream[Int] = Stream(1, ?)
  x.force.toSeq                                   //> res0: scala.collection.immutable.Seq[Int] = Stream(1, 2)
  
  type PayloadId = Int
case class Payload(id: PayloadId, content: Any)
case class Broadcast(howMany: Int, payloads: Seq[Payload])

 val group =
 Stream.range(0, 5).map(id => Payload(id , Payload(id, s"content$id"))).grouped(3).map(_.force)
                                                  //> group  : Iterator[scala.collection.immutable.Stream[Worksheet.Payload]] = no
                                                  //| n-empty iterator
 (Stream.range(0, 5).map(id => Payload(id , Payload(id, s"content$id"))).grouped(3) map (_.toSeq)).toSeq foreach(println(_))
                                                  //> Stream(Payload(0,Payload(0,content0)), ?)
                                                  //| Stream(Payload(3,Payload(3,content3)), ?)
def payloads(howMany: Int): Seq[Payload] =
  Stream.range(0, howMany).map(id => Payload(id , Payload(id, s"content$id")))
                                                  //> payloads: (howMany: Int)Seq[Worksheet.Payload]

payloads(5) foreach(println)                      //> Payload(0,Payload(0,content0))
                                                  //| Payload(1,Payload(1,content1))
                                                  //| Payload(2,Payload(2,content2))
                                                  //| Payload(3,Payload(3,content3))
                                                  //| Payload(4,Payload(4,content4))

def chunks(total: Int, chunkSize: Int): Seq[Seq[Payload]] =
  (Stream.range(0, total).map(id => Payload(id , Payload(id, s"content$id"))).grouped(chunkSize) map (_.toSeq)).toSeq
                                                  //> chunks: (total: Int, chunkSize: Int)Seq[Seq[Worksheet.Payload]]

chunks(5, 3) foreach(println)                     //> Stream(Payload(0,Payload(0,content0)), ?)
                                                  //| Stream(Payload(3,Payload(3,content3)), ?)
}