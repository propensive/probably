/*
    Probably, version 0.18.0. Copyright 2017-21 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package probably

import scala.collection.immutable.ListMap
import scala.collection.mutable.HashMap
import scala.util.*, control.NonFatal
import scala.quoted.*

import scala.reflect.Typeable

import wisteria.*
import escritoire.*
import rudiments.*
import gossamer.*
import eucalyptus.*
import iridescence.*
import escapade.*

import language.dynamics

given realm: Realm = Realm(str"probably")

import Runner.*

object Runner:
  case class TestId private[probably](value: Txt)
  
  enum Outcome:
    case FailsAt(datapoint: Datapoint, count: Int)
    case Passed
    case Mixed

    def failed: Boolean = !passed
    def passed: Boolean = this == Passed
    
    def filename: Txt = this match
      case FailsAt(datapoint, count) => datapoint.debugValue.filename.otherwise(str"(no source)")
      case _                         => str""
    
    def line: Txt = this match
      case FailsAt(datapoint, count) => datapoint.debugValue.line.otherwise(0).show
      case _                         => str""

    def debug: Txt = this match
      case FailsAt(datapoint, count) =>
        val padWidth = datapoint.debugValue.allInfo.map(_(0).length).max
        datapoint.debugValue.allInfo.map { case (k, v) =>
          val value: Txt = (v.cut(str"\n"): List[Txt]).join(str"\n"+" "*(padWidth + 3): Txt)
          str"${k.padLeft(padWidth, ' ')} = $value"
        }.join(str"\n")
      
      case _ =>
        str""

  enum Datapoint(val debugValue: Debug):
    case Pass extends Datapoint(Debug())
    case Fail(debug: Debug, index: Int) extends Datapoint(debug)
    case Throws(exception: Throwable, debug: Debug) extends Datapoint(debug)
    
    case PredicateThrows(exception: Exception, debug: Debug, index: Int) extends Datapoint(debug)

  def shortDigest[T: Show](text: T): Txt =
    val md = java.security.MessageDigest.getInstance("SHA-256").nn
    md.update(text.show.s.getBytes)
    md.digest.nn.take(3).map { b => Txt(f"$b%02x") }.join

class Runner(subset: Set[TestId] = Set()) extends Dynamic:

  val runner: Runner = this

  final def skip(testId: TestId): Boolean = !(subset.isEmpty || subset(testId))

  def apply[T](name: Txt)(fn: Test ?=> T): Test { type Type = T } =
    new Test(name):
      type Type = T
      def action(): T = fn(using this)

  def time[T](name: Txt)(fn: => T)(using Log): T = apply[T](name)(fn).check { _ => true }
  def suite(testSuite: TestSuite)(using Log): Unit = suite(testSuite.name)(testSuite.run)

  def suite(name: Txt)(fn: Runner ?=> Unit)(using Log): Unit =
    Log.info(ansi"Starting test suite ${colors.Gold}($name)")
    val test = new Test(name):
      type Type = Report

      def action(): Report =
        val runner = Runner()
        fn(using runner)
        runner.report()
    
    val t0 = System.currentTimeMillis
    val report = test.check(_.results.forall(_.outcome.passed))
    val t1 = System.currentTimeMillis - t0

    if report.results.exists(_.outcome.passed) && report.results.exists(_.outcome.failed)
    then synchronized {
      results = results.updated(name, results(name).copy(outcome = Outcome.Mixed))
    }

    report.results.foreach { result => record(result.copy(indent = result.indent + 1)) }
   
    Log.fine(ansi"Completed test suite ${colors.Gold}($name) in ${t1.show}ms")

  def assert(name: Txt)(fn: => Boolean)(using Log): Unit = apply(name)(fn).oldAssert(identity)

  object Test:
    given AnsiShow[Test] = test => ansi"${colors.Khaki}(${test.id.value})"

  abstract class Test(val name: Txt):
    type Type

    def id: TestId = TestId(Runner.shortDigest(name))
    def action(): Type
    
    def oldAssert(pred: Type => Boolean)(using Log): Unit =
      try if !skip(id) then check(pred)
      catch case NonFatal(e) =>
        Log.warn(ansi"A $e exception was thrown whilst checking the test ${this.ansi}")
    
    private val map: HashMap[Txt, Txt] = HashMap()

    def debug[T](name: Txt, expr: T): T =
      map(name) = Txt(expr.toString)
      expr

    inline def assert(inline pred: Type => Boolean)(using log: Log): Unit =
      ${Macros.assert[Type]('runner, 'this, 'pred, 'log)}

    def check(pred: Type => Boolean, debug: Option[Type] => Debug = v => Debug(v.map { v => Txt(v.toString) }))
             (using Log): Type =
      //val l = summon[Log]
      def handler(index: Int): PartialFunction[Throwable, Datapoint] =
        case e: Exception =>
          Log.warn(ansi"A $e exception was thrown in the test ${this.ansi}")
          Datapoint.PredicateThrows(e, Debug(), index)

      def makeDatapoint(pred: Type => Boolean, count: Int, datapoint: Datapoint, value: Type)
          : Datapoint =
        try if pred(value) then Datapoint.Pass else Datapoint.Fail(debug(Some(value)), count)
        catch handler(count)

      val t0 = System.currentTimeMillis()
      val value = Try(action())
      val time = System.currentTimeMillis() - t0

      value match
        case Success(value) =>
          record(this, time, makeDatapoint(pred, 0, Datapoint.Pass, value))
          value
        case Failure(e) =>
          val info = Option(e.getStackTrace).to(List)
            .flatMap(_.nn.to(List).map(_.nn))
            .takeWhile(_.getClassName != "probably.Suite")
            .map { frame => Txt(frame.nn.toString.nn) }
            .join(Option(Txt(e.getMessage.nn)).fold(str"null")(_+str"\n  at "), str"\n  at ", str"")
          
          record(this, time, Datapoint.Throws(e, debug(None).add(str"exception", info)))
          throw e

  def report(): Report = Report(results.values.to(List))
  def clear(): Unit = results = emptyResults()

  protected def record(test: Test, duration: Long, datapoint: Datapoint): Unit = synchronized {
    results = results.updated(test.name, results(test.name).append(test.name, duration, datapoint))
  }

  protected def record(summary: Summary) = synchronized {
    results = results.updated(summary.name, summary)
  }

  private def emptyResults(): Map[Txt, Summary] = ListMap().withDefault { name =>
    Summary(TestId(shortDigest(name)), name, 0, Int.MaxValue, 0L, Int.MinValue, Outcome.Passed, 0)
  }

  @volatile
  protected var results: Map[Txt, Summary] = emptyResults()
end Runner

case class Debug(found: Option[Txt] = None, filename: Maybe[Txt] = Unset, line: Maybe[Int] = Unset,
                     expected: Maybe[Txt] = Unset, info: Map[Txt, Txt] = Map()):
  def add(key: Txt, value: Txt): Debug = copy(info = info.updated(key, value))
  
  def allInfo: ListMap[Txt, Txt] =
    val basicInfo =
      List(str"found" -> found.getOrElse(Unset), str"expected" -> expected)
        .filter(_._2 != Unset)
        .to(ListMap)
        .view
        .mapValues { v => Txt(v.toString) }
        .to(ListMap)
    
    basicInfo ++ info

object Macros:
  import scala.reflect.*
  def assert[T: Type](runner: Expr[Runner], test: Expr[Runner#Test { type Type = T }], pred: Expr[T => Boolean], log: Expr[Log])(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    
    val filename: Expr[String] = Expr {
      val absolute = Position.ofMacroExpansion.toString.cut(":").head
      
      val pwd = try Sys.user.dir() catch case error@KeyNotFound(_) => throw Impossible(error)
      
      if absolute.startsWith(pwd) then absolute.drop(pwd.length + 1) else absolute
    }
    
    val line = Expr(Position.ofMacroExpansion.startLine + 1)

    def debugExpr[S: Type](expr: Expr[S]): Expr[Option[S] => Debug] =

      Expr.summon[Comparison[S]].fold {
        '{ (x: Option[S]) =>
          Debug(found = x.map(_.debug), filename = Txt($filename), line = $line, expected = $expr.debug)
        }
      } { comparison =>
        '{ (x: Option[S]) =>
          Debug(
            found = x.map(_.debug),
            filename = Txt($filename),
            line = $line,
            expected = $expr.debug,
            info = if x.isEmpty then Map()
                else Map(str"structure" -> Txt($comparison.compare(x.get, $expr).toString))
          )
        }
      }

    def interpret(pred: Expr[T => Boolean]): Expr[Option[T] => Debug] = pred match
      case '{ (x: T) => x == ($expr: T) }             => debugExpr(expr)
      case '{ (x: T) => ($expr: T) == x }             => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Double) }   => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Float) }    => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Long) }     => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Int) }      => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Short) }    => debugExpr(expr)
      case '{ (x: Double) => x == ($expr: Byte) }     => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Double) }    => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Float) }     => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Long) }      => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Int) }       => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Short) }     => debugExpr(expr)
      case '{ (x: Float) => x == ($expr: Byte) }      => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Double) }     => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Float) }      => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Long) }       => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Int) }        => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Short) }      => debugExpr(expr)
      case '{ (x: Long) => x == ($expr: Byte) }       => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Double) }      => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Float) }       => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Long) }        => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Int) }         => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Short) }       => debugExpr(expr)
      case '{ (x: Int) => x == ($expr: Byte) }        => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Double) }    => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Float) }     => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Long) }      => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Int) }       => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Short) }     => debugExpr(expr)
      case '{ (x: Short) => x == ($expr: Byte) }      => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Double) }     => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Float) }      => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Long) }       => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Int) }        => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Short) }      => debugExpr(expr)
      case '{ (x: Byte) => x == ($expr: Byte) }       => debugExpr(expr)
      case '{ (x: Boolean) => x == ($expr: Boolean) } => debugExpr(expr)
      case '{ (x: Double) => ($expr: Double) == x }   => debugExpr(expr)
      case '{ (x: Double) => ($expr: Float) == x }    => debugExpr(expr)
      case '{ (x: Double) => ($expr: Long) == x }     => debugExpr(expr)
      case '{ (x: Double) => ($expr: Int) == x }      => debugExpr(expr)
      case '{ (x: Double) => ($expr: Short) == x }    => debugExpr(expr)
      case '{ (x: Double) => ($expr: Byte) == x }     => debugExpr(expr)
      case '{ (x: Float) => ($expr: Double) == x }    => debugExpr(expr)
      case '{ (x: Float) => ($expr: Float) == x }     => debugExpr(expr)
      case '{ (x: Float) => ($expr: Long) == x }      => debugExpr(expr)
      case '{ (x: Float) => ($expr: Int) == x }       => debugExpr(expr)
      case '{ (x: Float) => ($expr: Short) == x }     => debugExpr(expr)
      case '{ (x: Float) => ($expr: Byte) == x }      => debugExpr(expr)
      case '{ (x: Long) => ($expr: Double) == x }     => debugExpr(expr)
      case '{ (x: Long) => ($expr: Float) == x }      => debugExpr(expr)
      case '{ (x: Long) => ($expr: Long) == x }       => debugExpr(expr)
      case '{ (x: Long) => ($expr: Int) == x }        => debugExpr(expr)
      case '{ (x: Long) => ($expr: Short) == x }      => debugExpr(expr)
      case '{ (x: Long) => ($expr: Byte) == x }       => debugExpr(expr)
      case '{ (x: Int) => ($expr: Double) == x }      => debugExpr(expr)
      case '{ (x: Int) => ($expr: Float) == x }       => debugExpr(expr)
      case '{ (x: Int) => ($expr: Long) == x }        => debugExpr(expr)
      case '{ (x: Int) => ($expr: Int) == x }         => debugExpr(expr)
      case '{ (x: Int) => ($expr: Short) == x }       => debugExpr(expr)
      case '{ (x: Int) => ($expr: Byte) == x }        => debugExpr(expr)
      case '{ (x: Short) => ($expr: Double) == x }    => debugExpr(expr)
      case '{ (x: Short) => ($expr: Float) == x }     => debugExpr(expr)
      case '{ (x: Short) => ($expr: Long) == x }      => debugExpr(expr)
      case '{ (x: Short) => ($expr: Int) == x }       => debugExpr(expr)
      case '{ (x: Short) => ($expr: Short) == x }     => debugExpr(expr)
      case '{ (x: Short) => ($expr: Byte) == x }      => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Double) == x }     => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Float) == x }      => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Long) == x }       => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Int) == x }        => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Short) == x }      => debugExpr(expr)
      case '{ (x: Byte) => ($expr: Byte) == x }       => debugExpr(expr)
      case '{ (x: Boolean) => ($expr: Boolean) == x } => debugExpr(expr)
      
      case expr =>
        '{ (opt: Option[?]) => Debug(found = opt.map(_.debug), filename = Txt($filename),
            line = $line) }
    
    '{
      try if !$runner.skip($test.id) then $test.check($pred, ${interpret(pred)})(using $log)
      catch case NonFatal(_) => ()
    }

enum Differences:
  case Same
  case Structural(differences: Map[Txt, Differences])
  case Diff(left: Txt, right: Txt)

  def flatten: Seq[(List[Txt], Txt, Txt)] = this match
    case Same              => Nil
    case Structural(diffs) => diffs.to(List).flatMap { case (label, nested) =>
                                nested.flatten.map { case (path, left, right) =>
                                  (label :: path, left, right)
                                }
                              }
    case Diff(left, right) => List((Nil, left, right))

  override def toString(): String =
    val table = Tabulation[(List[Txt], Txt, Txt)](
      Column(str"Key", _(0).join(str".")),
      Column(str"Found", _(1)),
      Column(str"Expected", _(2)),
    )

    table.tabulate(100, flatten).join(str"\n").s

trait Comparison[-T]:
  def compare(left: T, right: T): Differences

object Comparison extends Derivation[Comparison]:
  given Comparison[String] =
    (a, b) => if a == b then Differences.Same else Differences.Diff(Txt(a), Txt(b))
  
  given [T: Show]: Comparison[T] =
    (a, b) => if a == b then Differences.Same else Differences.Diff(a.show, b.show)
  
  def join[T](caseClass: CaseClass[Comparison, T]): Comparison[T] = (left, right) =>
    if left == right then Differences.Same
    else Differences.Structural {
      caseClass.params
        .filter { p => p.deref(left) != p.deref(right) }
        .map { p => Txt(p.label) -> p.typeclass.compare(p.deref(left), p.deref(right)) }
        .to(Map)
    }
  
  def split[T](sealedTrait: SealedTrait[Comparison, T]): Comparison[T] = (left, right) =>
    val leftType = sealedTrait.choose(left)(identity(_))
    sealedTrait.choose(right) { subtype =>
      if leftType == subtype
      then subtype.typeclass.compare(subtype.cast(left), subtype.cast(right))
      else Differences.Diff(str"type: ${leftType.typeInfo.short}", str"type: ${subtype.typeInfo.short}")
    }

case class Summary(id: TestId, name: Txt, count: Int, tmin: Long, ttot: Long, tmax: Long,
                       outcome: Outcome, indent: Int):
  def avg: Double = ttot.toDouble/count/1000.0
  def min: Double = tmin.toDouble/1000.0
  def max: Double = tmax.toDouble/1000.0

  def aggregate(datapoint: Datapoint) = outcome match
    case Outcome.FailsAt(dp, c) => Outcome.FailsAt(dp, c)
    case Outcome.Passed         => datapoint match
      case Datapoint.Pass         => Outcome.Passed
      case other                  => Outcome.FailsAt(other, count + 1)
    case Outcome.Mixed          => Outcome.FailsAt(datapoint, 0)

  def append(test: Txt, duration: Long, datapoint: Datapoint): Summary =
    Summary(id, name, count + 1, tmin min duration, ttot + duration, tmax max duration,
        aggregate(datapoint), 0)

case class Report(results: List[Summary]):
  val passed: Int = results.count(_.outcome == Outcome.Passed)
  val failed: Int = results.count(_.outcome != Outcome.Passed)
  val total: Int = failed + passed

object TestSuite:
  given AnsiShow[TestSuite] = ts => ansi"${colors.Gold}(${ts.name})"

trait TestSuite:
  def run(using Runner): Unit
  def name: Txt

object global:
  object test extends Runner()

def test[T](name: Txt)(fn: Runner#Test ?=> T)(using runner: Runner, log: Log)
    : runner.Test { type Type = T } =
  runner(name)(fn)

def suite(name: Txt)(fn: Runner ?=> Unit)(using runner: Runner, log: Log): Unit =
  runner.suite(name)(fn)

def suite(suite: TestSuite)(using runner: Runner, log: Log): Unit = runner.suite(suite)
def time[T](name: Txt)(fn: => T)(using Runner, Log): T = test(name)(fn).check { _ => true }

case class UnexpectedSuccessError(value: Any)
extends Exception("probably: the expression was expected to throw an exception, but did not")

def capture(fn: => CanThrow[Exception] ?=> Any): Exception throws UnexpectedSuccessError =
  try
    val result = fn
    throw UnexpectedSuccessError(result)
  catch
    case error@UnexpectedSuccessError(_) => throw error
    case error: Exception                => error
