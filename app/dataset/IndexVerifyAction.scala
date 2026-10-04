//===========================================================================
//    Copyright 2026 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package dataset

/**
 * What to do about the gap Hub3's index verification reports.
 *
 * Pulled out of DatasetActor as a pure decision because the bug it answers to
 * was a missing branch, not a wrong one: the self-heal covered gaps up to 1000
 * and nothing covered the rest, so the system went quiet exactly when the
 * problem got big (#3626, enb-101-bidprentje at 285 of 3374).
 */
sealed trait IndexVerifyAction

object IndexVerifyAction {

  /** Nothing to do: the counts match, or Hub3 named the failing records and
    * those are reset for re-send by their own path. */
  case object Nothing extends IndexVerifyAction

  /** Small enough to diff the registry against the index and re-send. */
  case class Reconcile(gap: Int) extends IndexVerifyAction

  /** Too large to repair on a signal that may itself be wrong — tell someone. */
  case class Report(gap: Int) extends IndexVerifyAction

  /** The largest gap we are willing to close by reconciling. Above this a
    * reconcile would mass-resend a whole dataset on one verification result. */
  val MaxSelfHealGap = 1000

  def apply(recordsExpected: Int, recordsIndexed: Int, hasNamedErrors: Boolean): IndexVerifyAction = {
    val gap = math.abs(recordsExpected - recordsIndexed)
    if (recordsExpected <= 0 || gap == 0 || hasNamedErrors) Nothing
    else if (gap <= MaxSelfHealGap) Reconcile(gap)
    else Report(gap)
  }
}
