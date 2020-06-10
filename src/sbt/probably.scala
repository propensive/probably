/*

    Probably, version 0.3.0. Copyright 2017-20 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
    compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the License is
    distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and limitations under the License.

*/
package probably.sbt

import sbt.testing
import probably._

class Framework extends testing.Framework {

  def name(): String = "Probably"

  def fingerprints(): Array[testing.Fingerprint] =
    Array(new testing.SubclassFingerprint {
      def isModule: Boolean = true
      def superclassName(): String = "probably.Suite"
      def requireNoArgConstructor(): Boolean = true
    })
  
  def runner(args: Array[String],
             remoteArgs: Array[String],
             testClassLoader: ClassLoader): testing.Runner =
    new testing.Runner {
      def args(): Array[String] = args
      def remoteArgs(): Array[String] = remoteArgs
      def done(): String = ""
      def tasks(list: Array[testing.TaskDef]): Array[testing.Task] =
        list.map(t => Task(t, args))
      def receiveMessage(msg: String): Option[String] = None  
    }
}

case class Task(
    taskDef: testing.TaskDef, 
    args: Array[String]) extends testing.Task {

  val suite: Suite =
    Class
      .forName(taskDef.fullyQualifiedName() + "$")
      .getDeclaredField("MODULE$")
      .get(null)
      .asInstanceOf[Suite]    
  
  def execute(
      eh: testing.EventHandler, 
      ls: Array[testing.Logger]): Array[testing.Task] = {

    def log(l: testing.Logger => Unit): Unit = ls.foreach(l) 

    val test = new Runner(args.map(Runner.TestId).to[Set])
    suite.run(test)
    test.report().results.foreach(
      s =>
        if (s.outcome.passed) {
          log(passed(s.indent * 2, s.name))
          eh.handle(Success(taskDef, s.ttot))
        } else if (s.outcome.failed) {
          val debug =
            if (s.outcome.debug.isEmpty) "" else s" (${s.outcome.debug})"
          log(passed(s.indent * 2, s"${s.name}$debug"))
          eh.handle(Failure(taskDef, s.ttot))
      }
    )
    Array.empty  
  } 

  def tags(): Array[String] = 
    Array.empty

  def indent(i: Int): String = 
    (Iterable.fill(i)(' ')).mkString

  def passed(i: Int, msg: String): testing.Logger => Unit = 
    _.info(indent(i) + msg)
  

  def failed(i: Int, msg: String): testing.Logger => Unit = 
    _.info(indent(i) + msg + " ***Failed***")
}

sealed abstract class Event extends testing.Event {
  def value: Either[Throwable, testing.Status]
  def taskDef: testing.TaskDef
  def fullyQualifiedName(): String = taskDef.fullyQualifiedName
  def fingerprint(): testing.Fingerprint = taskDef.fingerprint
  def selector(): testing.Selector = taskDef.selectors.head
  def status(): testing.Status = value.left.map(_ => testing.Status.Error).merge
  def throwable(): testing.OptionalThrowable =
    value.fold(t => new testing.OptionalThrowable(t), _ => new testing.OptionalThrowable())
}

case class Error(ex: Throwable, taskDef: testing.TaskDef, duration: Long)
    extends Event {
  def value: Either[Throwable, testing.Status] = Left(ex)
}

case class Success(taskDef: testing.TaskDef, duration: Long) extends Event {
  def value: Either[Throwable, testing.Status] = Right(testing.Status.Success)
}

case class Failure(taskDef: testing.TaskDef, duration: Long) extends Event {
  def value: Either[Throwable, testing.Status] = Right(testing.Status.Failure)
}

case class Pending(taskDef: testing.TaskDef, duration: Long) extends Event {
  def value: Either[Throwable, testing.Status] = Right(testing.Status.Pending)
}
