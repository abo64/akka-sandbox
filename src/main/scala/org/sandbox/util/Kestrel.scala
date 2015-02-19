package org.sandbox.util

/**
 * Kestrel operator for side effects before returning a value,
 * cf. http://stackoverflow.com/questions/9671620/how-to-keep-return-value-when-logging-in-scala
 * and https://github.com/raganwald-deprecated/homoiconic/blob/master/2008-10-29/kestrel.markdown#readme
 */
object Kestrel {
  def kestrel[A](x: A)(f: A => Unit): A = { f(x); x }

  // perhaps have this even simpler w/ a macro as in
  // http://www.warski.org/blog/2012/12/starting-with-scala-macros-a-short-tutorial/
  def debug[A](description: String, x: A): A =
    kestrel(x) { y => println(s"$description: y") }
}
