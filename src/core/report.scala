package probably

import rudiments.*
import gossamer.*
import escritoire.*
import escapade.*
import iridescence.*

import scala.collection.mutable as scm

enum DebugInfo:
  case Throws(exception: Throwable)
  case AssertThrows(exception: Throwable)
  case Capture(ident: Text, value: Text)
  case Compare(comparison: Comparison)

trait Inclusion[ReportType, DataType]:
  def include(report: ReportType, testId: TestId, data: DataType): ReportType

trait TestReporter[ReportType]:
  def make(): ReportType
  def declareSuite(report: ReportType, suite: TestSuite): Unit
  def complete(report: ReportType): Unit

object TestReporter:
  given TestReporter[TestReport] with
    def make(): TestReport = TestReport()
    def declareSuite(report: TestReport, suite: TestSuite): Unit = report.declareSuite(suite)
    def complete(report: TestReport): Unit = report.complete()

object TestReport:
  given Inclusion[TestReport, Outcome] = _.addOutcome(_, _)
  given Inclusion[TestReport, DebugInfo] = _.addDebugInfo(_, _)

class TestReport():
  private val tests: scm.SortedMap[TestId, scm.ArrayBuffer[Outcome]] =
    scm.TreeMap[TestId, scm.ArrayBuffer[Outcome]]().withDefault { _ => scm.ArrayBuffer[Outcome]() }
  
  private val details: scm.HashMap[TestId, scm.ArrayBuffer[DebugInfo]] = scm.HashMap()

  def declareSuite(suite: TestSuite): TestReport =
    this.tap { _ => tests(suite.id) = scm.ArrayBuffer[Outcome]() }

  def addOutcome(testId: TestId, outcome: Outcome): TestReport =
    this.tap { _ => tests(testId) = tests(testId).append(outcome) }
  
  def addDebugInfo(testId: TestId, info: DebugInfo): TestReport = this.tap { _ => details(testId).append(info) }

  enum Status:
    case Pass, Fail, Throws, CheckThrows, Mixed, Suite

    def symbol: AnsiText = this match
      case Pass        => ansi"${Bg(rgb"#8abd00")}( $Bold(${colors.Black}(✓)) )"
      case Fail        => ansi"${Bg(colors.Tomato)}( $Bold(${colors.Black}(✗)) )"
      case Throws      => ansi"${Bg(colors.DarkOrange)}( $Bold(${colors.Black}(!)) )"
      case CheckThrows => ansi"${Bg(rgb"#dd40a0")}( $Bold(${colors.Black}(‼)) )"
      case Mixed       => ansi"${Bg(rgb"#ddd700")}( $Bold(${colors.Black}(?)) )"
      case Suite       => ansi"   "

  case class Summary(status: Status, id: TestId, count: Int, min: Long, max: Long, avg: Long):
    def indentedName: AnsiText =
      val depth = id.suite.mm(_.id.depth).or(0) + 1
      val title = if status == Status.Suite then ansi"${colors.Silver}($Bold(${id.name}))" else ansi"${id.name}"
      ansi"${t"  "*(depth - 2)}$title"

    val unitsSeq: List[AnsiText] = List(
      ansi"${colors.BurlyWood}(µs)",
      ansi"${colors.Goldenrod}(ms)",
      ansi"${colors.Sienna}(s) "
    )
    
    protected def time(n: Long, units: List[AnsiText] = unitsSeq): AnsiText = units match
      case Nil =>
        n.show.ansi
      
      case unit :: rest =>
        if n > 100000L then time(n/1000L, rest) else
          val sig = (n/1000L).show
          val frac = (n%1000).show.pad(3, Rtl, '0')
          ansi"${colors.Silver}(${sig}.$frac) ${unit}"

    def minTime: AnsiText = if min == 0L then ansi"" else time(min)
    def maxTime: AnsiText = if max == 0L then ansi"" else time(max)
    def avgTime: AnsiText = if avg == 0L then ansi"" else time(avg)
    def iterations: AnsiText = if count == 0 then ansi"" else count.ansi

  def complete(): Unit =
    val summaries: List[Summary] = tests.to(List).map: (id, buf) =>
      val status =
        if buf.isEmpty then Status.Suite
        else if buf.forall(_.is[Outcome.Pass]) then Status.Pass
        else if buf.forall(_.is[Outcome.Fail]) then Status.Fail
        else if buf.forall(_.is[Outcome.Throws]) then Status.Throws
        else if buf.forall(_.is[Outcome.CheckThrows]) then Status.CheckThrows
        else Status.Mixed
      
      if buf.length == 0 then Summary(status, id, 0, 0, 0, 0) else
        val avg: Long = buf.foldLeft(0L)(_ + _.duration)/buf.length
        Summary(status, id, buf.length, buf.map(_.duration).min, buf.map(_.duration).max, avg)
    
    val table: Table[Summary] =
      val stats = !summaries.forall(_.count < 2)
      Table[Summary](
        Column(ansi"")(_.status.symbol),
        Column(ansi"$Bold(Hash)"): s =>
          ansi"${colors.CadetBlue}(${s.id.id})",
        Column(ansi"$Bold(Test)")(_.indentedName),
        Column(ansi"$Bold(Count)", align = Alignment.Right, hide = !stats): s =>
          ansi"${colors.SteelBlue}(${s.iterations})",
        Column(ansi"$Bold(Min)", align = Alignment.Right, hide = !stats): s =>
          if s.count < 2 then ansi"" else s.minTime,
        Column(ansi"$Bold(${if stats then t"Avg" else t"Time"})", align = Alignment.Right) { s => s.avgTime },
        Column(ansi"$Bold(Max)", align = Alignment.Right, hide = !stats): s =>
          if s.count < 2 then ansi"" else s.maxTime
      )
      
    import tableStyles.rounded
    
    table.tabulate(summaries, 120).map(_.render).foreach(println(_))