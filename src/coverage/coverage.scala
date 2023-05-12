/*
    Probably, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

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

import rudiments.*
import gossamer.*

import scala.runtime.coverage.*
import scala.io.*
import scala.collection.mutable.BitSet

case class CodeBranch(id: Int, path: Text)
case class CoverageResults(path: Text, spec: IArray[CodeBranch], hits: Set[Int])

object Coverage:

  def apply(): Option[CoverageResults] = currentDir.map: dir =>
    CoverageResults(dir, spec(dir), measurements(dir))

  private def currentDir: Option[Text] =
    Option(System.getProperty("scalac.coverage")).map(_.nn).map(Text(_))

  private def spec(dir: Text): IArray[CodeBranch] =
    val file = java.io.File(java.io.File(dir.s), "scoverage.coverage")
    val lines = Source.fromFile(file).getLines.to(LazyList).map(Text(_))

    def recur(lines: LazyList[Text], branches: List[CodeBranch] = Nil): List[CodeBranch] =
      lines match
        case As[Int](id) #:: path #:: pkg #:: tail =>
          recur(tail.dropWhile(!_.starts(t"\f")).tail, CodeBranch(id, path) :: branches)
        case _ =>
          branches.reverse
      
    IArray.from(recur(lines.dropWhile(_.starts(t"#"))))
  
  private def measurements(dir: Text): Set[Int] =
    val ids = BitSet()
    
    Source.fromFile(Invoker.measurementFile(dir.s)).getLines.to(LazyList).foreach: id =>
      ids(id.toInt) = true
    
    ids.to(Set)
