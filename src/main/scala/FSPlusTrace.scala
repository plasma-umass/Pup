package rehearsal

import FSPlusSyntax.{FileState, LangPath}
import PrettyFSPlusTrace._

object FSPlusTrace {
  sealed trait Pred {
    def pretty: String = prettyPred(this)
    override def toString: String = this.pretty
  }

  case object PTrue extends Pred
  case object PFalse extends Pred
  case class PAnd(lhs: Pred, rhs: Pred) extends Pred
  case class POr(lhs: Pred, rhs: Pred) extends Pred
  case class PNot(pred: Pred) extends Pred
  case class PTestFileState(path: Expr, state: FileState) extends Pred
  case class PTestFileContains(path: Expr, contents: Expr) extends Pred

  sealed trait Type
  
  case object TPath extends Type
  case object TString extends Type

  sealed trait Expr {
    def pretty: String = prettyExpr(this)
    override def toString: String = this.pretty
  }

  sealed trait Const {
    def pretty: String = prettyConst(this)
    override def toString: String = this.pretty
  }

  case class CPath(path: LangPath) extends Const
  case class CString(str: String) extends Const

  case class EHole(typ: Type, loc: Int, org: Const) extends Expr
  case class EParent(e: Expr) extends Expr
  case class EConcat(lhs: Expr, rhs: Expr) extends Expr
  case class EIf(p: Pred, e1: Expr, e2: Expr) extends Expr

  sealed trait Statement {
    def pretty: String = prettyStmt(this)
    override def toString: String = this.pretty
  }

  case object SError extends Statement
  case object SSkip extends Statement
  case class SIf(p: Pred, s1: Statement, s2: Statement) extends Statement
  case class SSeq(s1: Statement, s2: Statement) extends Statement
  case class SMkdir(path: Expr) extends Statement
  case class SCreateFile(path: Expr, contents: Expr) extends Statement
  case class SRm(path: Expr) extends Statement
  case class SCp(src: Expr, dst: Expr) extends Statement
}
