package probably.tests

import probably._

object Main extends Tests {

  def tests(): Unit = {
    test("tests with the same name get grouped") {
      val runner = new Runner()
      runner("test") { 2 + 2 }.assert(_ == 4)
      runner("test") { 2 + 2 }.assert(_ == 4)
      runner.report()
    }.assert(_.results.size == 1)
    
    test("tests with different names displayed separately") {
      val runner = new Runner()
      runner("alpha") { 2 + 2 }.assert(_ == 4)
      runner("beta") { 2 + 2 }.assert(_ == 4)
      runner.report()
    }.assert(_.results.size == 2)
    
    test("tests can fail") {
      val runner = new Runner()
      runner("failing") { 2 + 2 }.assert(_ == 5)
      runner.report()
    }.assert(_.results.head.result != Fail)
    
    test("tests can succeed") {
      val runner = new Runner()
      runner("failing") { 2 + 2 }.assert(_ == 4)
      runner.report()
    }.assert(_.results.head.result == Pass)
    
    test("tests can throw an exception") {
      val runner = new Runner()
      try runner("failing") {
        throw new Exception()
        4
      }.assert(_ == 4) catch { case e: Exception => () }
      runner.report().results.head.result
    }.assert {
      case FailsAt(_, _, _) => true
      case _ => false
    }
    
    test("assertion can throw an exception") {
      val runner = new Runner()
      runner("failing") { 2 + 2 }.assert { r =>
        throw new Exception()
        r == 4
      }
      runner.report().results.head.result
    }.assert {
      case FailsAt(_, _, _) => true
      case _                => false
    }
  }
}