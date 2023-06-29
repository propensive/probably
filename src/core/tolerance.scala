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

import scala.compiletime.*

object Tolerance:
  erased given CanEqual[Tolerance, Double] = ###
  erased given CanEqual[Double, Tolerance] = ###

case class Tolerance(value: Double, tolerance: Double):
  override def equals(that: Any): Boolean = that.asMatchable match
    case double: Double => value >= (double - tolerance) && value <= (double + tolerance)
    case _              => false

  override def hashCode: Int = value.hashCode

extension (value: Double)
  @targetName("plusOrMinus")
  def +/-(tolerance: Double): Tolerance = Tolerance(value, tolerance)