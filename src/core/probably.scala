/*

    Probably, version 0.8.0. Copyright 2017-20 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
    compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the License is
    distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and limitations under the License.

*/
package probably

import scala.collection.immutable.ListMap
import scala.util.*, control.NonFatal

import language.dynamics

import Runner._

object Runner:
  object Showable:
    implicit def show[T: Show](value: T): Showable[T] = Showable[T](value, summon[Show[T]])

  case class Showable[T](value: T, show: Show[T]):
    def apply(): String = show.show(value)

  case class TestId private[probably](value: String)
  
  enum Outcome:
    case FailsAt(datapoint: Datapoint, count: Int)
    case Passed
    case Mixed

    def failed: Boolean = !passed
    def passed: Boolean = this == Passed
    
    def debug: String = this match
      case FailsAt(datapoint, count) => datapoint.map.map { case (k, v) => s"$k=$v" }.mkString(" ")
      case _                         => ""


  enum Datapoint(val map: Map[String, String]):
    case Pass extends Datapoint(Map())
    case Fail(failMap: Map[String, String], index: Int) extends Datapoint(failMap)
    case Throws(exception: Throwable, throwMap: Map[String, String]) extends Datapoint(throwMap)
    case ThrowsInCheck(exception: Exception, throwMap: Map[String, String], index: Int) extends Datapoint(throwMap)

  object Show:
    given Show[Int] = _.toString
    given Show[String] = identity(_)
    given Show[Boolean] = _.toString
    given Show[Long] = _.toString
    given Show[Byte] = _.toString
    given Show[Short] = _.toString
    given Show[Char] = _.toString

  trait Show[T] { def show(value: T): String }

  def shortDigest(text: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(text.getBytes)
    md.digest.take(3).map(b => f"$b%02x").mkString

class Runner(specifiedTests: Set[TestId] = Set()) extends Dynamic {

  final def runTest(testId: TestId): Boolean = specifiedTests.isEmpty || specifiedTests(testId)

  def applyDynamic[T](method: String)(name: String)(fn: => T): Test { type Type = T } =
    applyDynamicNamed[T]("")("" -> name)(fn)

  def applyDynamicNamed[T]
                       (method: String)
                       (name: (String, String), args: (String, Showable[_])*)
                       (fn: => T)
                       : Test { type Type = T } =
    new Test(name._2, args.map(_ -> _()).to(Map)):
      type Type = T
      def action(): T = fn

  def time[T](name: String)(fn: => T): T = applyDynamicNamed[T]("")("" -> name)(fn).check { _ => true }

  def suite(testSuite: TestSuite): Unit = suite(testSuite.name)(testSuite.run(_))

  def suite(name: String)(fn: Runner => Unit): Unit =
    val report = new Test(name, Map()) {
      type Type = Report

      def action(): Report = {
        val runner = Runner()
        fn(runner)
        runner.report()
      }
    }.check(_.results.forall(_.outcome.passed))

    if report.results.exists(_.outcome.passed) && report.results.exists(_.outcome.failed)
    then synchronized { results = results.updated(name, results(name).copy(outcome = Outcome.Mixed)) }

    report.results.foreach { result => record(result.copy(indent = result.indent + 1)) }

  def assert(name: String)(fn: => Boolean): Unit = applyDynamicNamed("")("" -> name)(fn).assert(identity)

  abstract class Test(val name: String, map: => Map[String, String]):
    type Type

    def id: TestId = TestId(Runner.shortDigest(name))
    def action(): Type
    def assert(pred: (Type => Boolean)*): Unit = try if(runTest(id)) check(pred*) catch case NonFatal(_) => ()

    def check(preds: (Type => Boolean)*): Type =
      def handler(index: Int): PartialFunction[Throwable, Datapoint] =
        case e: Exception => Datapoint.ThrowsInCheck(e, map, index)

      def makeDatapoint(preds: Seq[Type => Boolean], count: Int, datapoint: Datapoint, value: Type): Datapoint =
        try
          if preds.isEmpty then datapoint
          else if preds.head(value) then makeDatapoint(preds.tail, count + 1, Datapoint.Pass, value)
          else Datapoint.Fail(map, count)
        catch handler(count)


      val t0 = System.currentTimeMillis()
      val value = Try(action())
      val time = System.currentTimeMillis() - t0

      value match
        case Success(value) =>
          record(this, time, makeDatapoint(preds, 0, Datapoint.Pass, value))
          value
        case Failure(exception) =>
          record(this, time, Datapoint.Throws(exception, map))
          throw exception

  def report(): Report = Report(results.values.to(List))
  def clear(): Unit = results = emptyResults()

  protected def record(test: Test, duration: Long, datapoint: Datapoint): Unit = synchronized {
    results = results.updated(test.name, results(test.name).append(test.name, duration, datapoint))
  }

  protected def record(summary: Summary) = synchronized {
    results = results.updated(summary.name, summary)
  }

  private[this] def emptyResults(): Map[String, Summary] = ListMap().withDefault { name =>
    Summary(TestId(shortDigest(name)), name, 0, Int.MaxValue, 0L, Int.MinValue, Outcome.Passed)
  }

  @volatile
  protected var results: Map[String, Summary] = emptyResults()
}

case class Summary(id: TestId, name: String, count: Int, tmin: Long, ttot: Long, tmax: Long, outcome: Outcome,
                   indent: Int = 0):
  def avg: Double = ttot.toDouble/count/1000.0
  def min: Double = tmin.toDouble/1000.0
  def max: Double = tmax.toDouble/1000.0

  def append(test: String, duration: Long, datapoint: Datapoint): Summary =
    Summary(id, name, count + 1, tmin min duration, ttot + duration, tmax max duration, outcome match
      case Outcome.FailsAt(dp, c) => Outcome.FailsAt(dp, c)
      case Outcome.Passed         => datapoint match
        case Datapoint.Pass         => Outcome.Passed
        case other                  => Outcome.FailsAt(other, count + 1)
      case Outcome.Mixed          => Outcome.FailsAt(datapoint, 0)
    )

case class Report(results: List[Summary]):
  val passed: Int = results.count(_.outcome == Outcome.Passed)
  val failed: Int = results.count(_.outcome != Outcome.Passed)
  val total: Int = failed + passed

trait TestSuite:
  def run(test: Runner): Unit
  def name: String

object global:
  object test extends Runner()