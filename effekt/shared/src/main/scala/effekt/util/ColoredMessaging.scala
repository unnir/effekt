package effekt
package util

import effekt.symbols.{ Capture, Captures, Effects, ErrorMessageInterpolator, LocalName, Name, NoName, QualifiedName, TypePrinter }
import effekt.util.messages.*
import kiama.util.{ Messaging, Position, Positions, Severities }
import kiama.util.Severities._

trait ColoredMessaging extends EffektMessaging {

  // Colors
  // ------

  def yellow(s: String): String
  def red(s: String): String
  def white(s: String): String
  def bold(s: String): String
  def bold_red(s: String): String
  def highlight(s: String): String
  def underlined(s: String): String

  def indent(s: String): String = s.linesIterator.map(l => "  " + l).mkString("\n")

  def severityToWord(severity: Severity): String =
    severity match {
      case Error       => red("error")
      case Warning     => yellow("warning")
      case Information => white("info")
      case Hint        => "hint"
    }

  override def formatMessage(message: EffektError): String =
    (message.startPosition, message.finishPosition) match {
      case (Some(from), Some(to)) if from.line == to.line => formatMessage(message, from, to)
      case (Some(from), _) => formatMessage(message, from)
      case (None, _) =>
        val severity = severityToWord(message.severity)
        s"[$severity] ${formatContent(message)}\n"
    }

  def formatMessage(message: EffektError, from: Position): String = {
    val severity = severityToWord(message.severity)
    val context = util.AnsiHighlight(from.optContext.getOrElse(""))
    s"[$severity] ${from.format} ${formatContent(message)}\n$context\n"
  }

  def formatMessage(message: EffektError, from: Position, to: Position): String = {
    val severity = severityToWord(message.severity)
    val context = util.AnsiHighlight(from.source.optLineContents(from.line).map { src =>
      src + "\n" + (" " * (from.column - 1)) + ("^" * (to.column - from.column))
    }.getOrElse(""))
    s"[$severity] ${from.format} ${formatContent(message)}\n$context\n"
  }

  /**
   * To allow uniform testing on all platforms, we homogenize the paths to Unix-style.
   *
   * This way the negative tests look the same on Windows and Linux
   */
  private def homogenizePath(label: String): String =
    label.replace('\\', '/')

  // Filter out duplicates
  // TODO this is a hack and should be solved in typer, where the messages are generated by unification
  override def formatMessages(messages: Messages): String =
    messages.sorted.map(formatMessage).distinct.mkString("")

  override def formatContent(err: EffektError): String = homogenizePath(err match {
    case ParseError(msg, range)               => msg
    case PlainTextError(msg, range, severity) => msg
    case StructuredError(StructuredMessage(sc, args), _, _) => sc.s(args.map {
      case id: source.IdDef    => highlight(TypePrinter.show(id))
      case id: source.IdRef    => highlight(TypePrinter.show(id))
      case name: Name          => highlight(name.name)
      case t: symbols.Type     => highlight(TypePrinter.show(t))
      case t: Capture          => highlight(TypePrinter.show(t))
      case t: Captures         => highlight(TypePrinter.show(t))
      case t: Effects          => highlight(TypePrinter.show(t))
      case n: Int              => highlight(n.toString)
      case nested: EffektError => formatContent(nested)
      case other               => other.toString
    }: _*)
    case AmbiguousOverloadError(matches, range) =>
      val title = bold("Ambiguous overload.\n")
      val mainMessage = s"${title}There are multiple overloads, which all would type check:"

      val longestNameLength = matches.map { case (sym, tpe) => fullname(sym.name).size }.max

      val explanations = matches map {
        case (sym, tpe) =>
          val name = fullname(sym.name)
          val padding = " " * (longestNameLength - name.size)
          pp"- ${highlight(name)}: ${padding}${tpe}"
      }

      mainMessage + "\n" + explanations.mkString("\n")
    case FailedOverloadError(failedAttempts, range) =>
      val title = bold("Cannot typecheck call.\n")
      val mainMessage = s"${title}There are multiple overloads, which all fail to check:"

      val explanations = failedAttempts map {
        case (sym, tpe, msgs) =>
          val nestedErrors = msgs.map { msg => formatContent(msg) }.mkString("\n")

          val header = underlined(pp"Possible overload: ${highlight(fullname(sym.name))}") + underlined(pp" of type ${tpe}")
          s"$header\n${indent(nestedErrors)}"
      }

      mainMessage + "\n\n" + explanations.mkString("\n\n") + "\n"
  })

  def fullname(n: Name): String = n match {
    case n: QualifiedName => n.qualifiedName
    case n: Name          => n.name
  }
}

class PlainMessaging extends ColoredMessaging {
  def yellow(s: String): String = s
  def red(s: String): String = s
  def white(s: String): String = s
  def bold(s: String): String = s
  def bold_red(s: String): String = s
  def highlight(s: String): String = s
  def underlined(s: String): String = s

  // Don't show context in plain messaging.
  override def formatMessage(message: EffektError, from: Position): String = {
    val severity = severityToWord(message.severity)
    s"[$severity] ${from.format} ${formatContent(message)}\n"
  }

  override def formatMessage(message: EffektError, from: Position, to: Position): String =
    formatMessage(message, from)
}

class AnsiColoredMessaging extends ColoredMessaging {
  def yellow(s: String): String = Console.YELLOW + s + Console.RESET
  def red(s: String): String = Console.RED + s + Console.RESET
  def white(s: String): String = Console.WHITE + s + Console.RESET
  def bold(s: String): String = Console.BOLD + s + Console.RESET
  def bold_red(s: String): String = Console.BLACK + Console.RED_B + s + Console.RESET
  def highlight(s: String): String = Console.BLACK + Console.WHITE_B + s + Console.RESET
  def underlined(s: String): String = Console.UNDERLINED + s + Console.RESET
}

