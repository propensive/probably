/*
    Probably, version [unreleased]. Copyright 2025 Jon Pretty, Propensive OÜ.

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

import anticipation.*
import rudiments.*

enum Trial[+T]:
  case Returns(result: T, duration: Long, context: Map[Text, Text])
  case Throws(exception: () => Nothing, duration: Long, context: Map[Text, Text])

  def get: T = this match
    case Returns(result, _, _)   => result
    case Throws(exception, _, _) => exception()