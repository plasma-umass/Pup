package pup

import scala.util.parsing.combinator._
import java.nio.file.Paths
import FSPlusSyntax._
import PlusHelpers.NotPresentMap

private class FSPlusParser extends RegexParsers with PackratParsers {
  type P[T] = PackratParser[T]

  override protected val whiteSpace = """(\s|#.*|(/\*((\*[^/])|[^*])*\*/))+""".r

  lazy val id: P[String] = "" ~> "[a-z_][a-zA-Z0-9_]*".r
  lazy val quotedText: P[String] = "\"" ~> "[^\"]*".r <~ "\""
  lazy val angleQuotedText: P[String] = "<" ~> "[^>]*".r <~ ">"
  lazy val num: P[Int] = """-?\d+""".r ^^ { _.toInt }

  lazy val predAtom: P[Pred] =
    "true" ^^ { _ => PTrue } |
    "false" ^^ { _ => PFalse } |
    "file?(" ~> expr <~ ")" ^^ { case path => PTestFileState(path, IsFile("")) } |
    "contains?(" ~> expr ~ ("," ~> expr <~ ")") ^^ { case path ~ conts => PTestFileContains(path, conts) } |
    "dir?(" ~> expr <~ ")" ^^ { case path => PTestFileState(path, IsDir) } |
    "dne?(" ~> expr <~ ")" ^^ { case path => PTestFileState(path, DoesNotExist) } |
    "(" ~> pred <~ ")"

  lazy val not: P[Pred] =
    "!" ~> not ^^ { p => !p } |
    predAtom

  lazy val and: P[Pred] =
    (not <~ "&&") ~ and ^^ { case lhs ~ rhs => lhs && rhs } |
    not

  lazy val or: P[Pred] =
    (and <~ "||") ~ or ^^ { case lhs ~ rhs => lhs || rhs } |
    and

  lazy val pred: P[Pred] = or

  var loc = 0
  def freshLoc(): Int = {
    loc += 1
    loc
  }

  lazy val location: P[Int] = "[" ~> num <~ "]"

  lazy val path: P[Const] =
    opt("!") ~ angleQuotedText ~ opt(location) ^^ {
      case Some(_) ~ path ~ _ => {
        val p = JavaPath(Paths.get(path))
        CPath(p, NotPresentMap.fresh(CPath(p, -1)))
      }
      case None ~ path ~ Some(loc) => CPath(JavaPath(Paths.get(path)), loc)
      case None ~ path ~ None => CPath(JavaPath(Paths.get(path)), freshLoc())
    }

  lazy val str: P[Const] =
    opt("!") ~ quotedText ~ opt(location) ^^ {
      case Some(_) ~ str ~ _ => CString(str, NotPresentMap.fresh(CString(str, -1)))
      case None ~ str ~ Some(loc) => CString(str, loc)
      case None ~ str ~ None => CString(str, freshLoc())
    }

  lazy val exprBase: P[Expr] =
    path ^^ { EPath(_) } |
    str ^^ { EString(_) } |
    id ^^ { EId(_) } |
    "parent(" ~> expr <~ ")" ^^ { EParent(_) } |
    ("if" ~> pred <~ "then") ~ expr ~ ("else" ~> expr) ^^ { case p ~ e1 ~ e2 => EIf(p, e1, e2) }

  lazy val concat: P[Expr] =
    concat ~ ("+" ~> exprBase) ^^ { case lhs ~ rhs => EConcat(lhs, rhs) } |
    exprBase

  lazy val expr: P[Expr] =
    concat

  lazy val stmtAtom: P[Statement] =
    "error" ^^ { _ => SError } |
    "skip" ^^ { _ => SSkip } |
    "mkdir(" ~> expr <~ ")" ^^ { mkdir(_) } |
    ("mkfile(" ~> expr <~ ",") ~ (expr <~ ")") ^^ { case p ~ c => mkfile(p, c) } |
    "rm(" ~> expr <~ ")" ^^ { rm(_) } |
    ("cp(" ~> expr <~ ",") ~ (expr <~ ")") ^^ { case src ~ dst => cp(src, dst) }

  lazy val stmt: P[Statement] =
    ("if" ~> pred <~ "then") ~ stmt ~ ("else" ~> stmt) ^^ { case p ~ s1 ~ s2 => ite(p, s1, s2) } |
    ("let" ~> id <~ "=") ~ expr ~ ("in" ~> stmt) ^^ { case id ~ e ~ body => SLet(id, e, body) } |
    stmtAtom ~ (";" ~> stmt) ^^ { case s1 ~ s2 => seq(s1, s2) } |
    stmtAtom

  lazy val fileState: P[FileState] =
    ("file" | "File") ^^ { _ => IsFile("")   } |
    ("dir"  | "Dir")  ^^ { _ => IsDir        } |
    ("null" | "Null") ^^ { _ => DoesNotExist }

  lazy val pathConstraint: P[PathConstraint] =
    angleQuotedText ~ ("->" ~> fileState) ^^ {
      case path ~ st => PathConstraint(Paths.get(path), st)
    }

  lazy val stringConstraint: P[StringConstraint] =
    angleQuotedText ~ ("=>" ~> quotedText) ^^ {
      case path ~ str => StringConstraint(Paths.get(path), str)
    }

  lazy val constraint: P[ValueConstraint] =
    stringConstraint | pathConstraint

  lazy val constraints: P[Seq[ValueConstraint]] =
    repsep(constraint, ",")
}

object FSPlusParser {
  private val parser = new FSPlusParser()
  import parser._

  def parseConstraints(str: String): Seq[ValueConstraint] = parseAll(constraints, str) match {
    case Success(r, _) => r
    case m => throw new ParseError(s"$m")
  }

  def parse(str: String): Statement = parseAll(stmt, str) match {
    case Success(r, _) => r
    case m => throw new ParseError(s"$m")
  }

  def parseFile(fileName: String): Statement = {
    import java.nio.file.Files
    parse(new String(Files.readAllBytes(Paths.get(fileName))))
  }
}
