package org.sandbox.akka.counts

import java.util.concurrent.atomic.AtomicInteger

trait CountProvider {

  private val count = new AtomicInteger(0)

  def getNext: Int = count.incrementAndGet
}