package pup

import java.nio.file.Paths
import scalaj.http.Http
import FSPlusSyntax._
import Implicits._
import PlusHelpers.NotPresentMap

object ResourceModelPlus {
  sealed trait Content
  case class CInline(data: String) extends Content
  case class CFile(src: String) extends Content

  sealed trait Res {
    def compile(
      locMap: Map[String, Int], interpMap: Map[String, Seq[String]], distro: String
    ): Statement = {
      ResourceModelPlus.compile(this, locMap, interpMap, distro)
    }
  }

  case class File(path: Path, content: Content, force: Boolean) extends Res
  case class EnsureFile(path: Path, content: Content) extends Res
  case class AbsentPath(path: Path, force: Boolean) extends Res
  case class Directory(path: Path) extends Res
  case class Package(name: String, present: Boolean) extends Res
  case class Group(name: String, present: Boolean) extends Res
  case class User(name: String, present: Boolean, manageHome: Boolean) extends Res
  case class Service(name: String) extends Res {
    val path = s"/etc/init.d/$name"
  }
  case class Cron(
    present: Boolean, command: String, user: String, hour: String, minute: String, month: String,
    monthday: String
  ) extends Res
  case class Host(ensure: Boolean, name: String, ip: String, target: String) extends Res

  case class SshAuthorizedKey(
    user: String, present: Boolean, name: String, key: String
  ) extends Res {
    val keyPath = s"/home/$user/.ssh/$name"
  }

  case object Notify extends Res

  def queryPackage(distro: String, pkg: String): Option[Set[Path]] = {
    val resp = logTime(s"Fetching package listing for $pkg ($distro)") {
      val addr = s"http://${Settings.packageHost}/query/$distro/$pkg"
      Http(addr).timeout(2 * 1000, 60 * 1000).asString
    }
    if (resp.isError) {
      None
    } else {
      Some(resp.body.lines.map(s => Paths.get(s)).toSet)
    }
  }

  implicit class InterpMap(map: Map[String, Seq[String]]) {
    def toFSPlus(str: String, locMap: Map[String, Int]): Expr = map.get(str) match {
      case Some(terms) => {
        val fsTerms: Seq[Expr] = terms.map(str => EString(cstr(str, locMap(str))))
        fsTerms.reduce {
          (leftTerm, rightTerm) => EConcat(leftTerm, rightTerm)
        }
      }
      case None => EString(cstr(str, locMap.getOrElse(str, -1)))
    }

    def toFSPlus(path: Path, locMap: Map[String, Int]): Expr = map.get(path.toString) match {
      case Some(terms) => {
        val fsTerms: Seq[Expr] = terms.map(str => EPath(cpath(JavaPath(str), locMap(str))))
        fsTerms.reduce {
          (leftTerm, rightTerm) => EConcat(leftTerm, rightTerm)
        }
      }
      case None => EPath(cpath(JavaPath(path), locMap.getOrElse(path.toString, -1)))
    }
  }

  def cpath(p: LangPath, loc: Int): Const = check(CPath(p, loc))
  def cstr(s: String, loc: Int): Const = check(CString(s, loc))

  def check(const: Const): Const = const match {
    case CPath(p, -1) => CPath(p, NotPresentMap.fresh(const))
    case CString(s, -1) => CString(s, NotPresentMap.fresh(const))
    case _ => const
  }

  def compile(
    res: Res, locMap: Map[String, Int], interpMap: Map[String, Seq[String]], distro: String
  ): Statement = res match {

    case EnsureFile(p, CInline(c)) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        ite(PTestFileState(EId("path"), IsFile("")), rm(EId("path")), SSkip) >>
        mkfile(EId("path"), EString(cstr(c, locMap(c))))
      )
    }

    case EnsureFile(p, CFile(s)) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        SLet("srcPath", EPath(cpath(JavaPath(Paths.get(s)), locMap(s))),
          ite(PTestFileState(EId("path"), IsFile("")), rm(EId("path")), SSkip) >>
          cp(EId("srcPath"), EId("path"))
        )
      )
    }

    case File(p, CInline(c), false) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        SLet("content", EString(cstr(c, locMap(c))),
          ite(PTestFileState(EId("path"), IsFile("")),
            rm(EId("path")) >> mkfile(EId("path"), EId("content")),
            ite(PTestFileState(EId("path"), DoesNotExist),
              mkfile(EId("path"), EId("content")),
              SError
            )
          )
        )
      )
    }

    case File(p, CInline(c), true) => {
      // Uses a loop in generation.
      ???
    }

    case File(p, CFile(s), false) => {
      SLet("srcPath", EPath(cpath(JavaPath(Paths.get(s)), locMap(s))),
        SLet("dstPath", interpMap.toFSPlus(p, locMap),
          ite(PTestFileState(EId("dstPath"), IsFile("")),
            rm(EId("dstPath")) >> cp(EId("srcPath"), EId("dstPath")),
            ite(PTestFileState(EId("dstPath"), DoesNotExist),
              cp(EId("srcPath"), EId("dstPath")),
              SError
            )
          )
        )
      )
    }

    case File(p, CFile(s), true) => {
      SLet("srcPath", EPath(cpath(JavaPath(Paths.get(s)), locMap(s))),
        SLet("dstPath", interpMap.toFSPlus(p, locMap),
          ite(PTestFileState(EId("dstPath"), IsDir) || PTestFileState(EId("dstPath"), IsFile("")),
            rm(EId("dstPath")),
            SSkip
          ) >> cp(EId("srcPath"), EId("dstPath"))
        )
      )
    }

    case AbsentPath(p, false) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        ite(PTestFileState(EId("path"), IsFile("")),
          rm(EId("path")),
          SSkip
        )
      )
    }

    case AbsentPath(p, true) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        ite(PTestFileState(EId("path"), DoesNotExist),
          SSkip,
          rm(EId("path"))
        )
      )
    }

    case Directory(p) => {
      SLet("path", interpMap.toFSPlus(p, locMap),
        ite(PTestFileState(EId("path"), IsDir),
          SSkip,
          ite(PTestFileState(EId("path"), IsFile("")),
            rm(EId("path")),
            SSkip
          ) >> mkdir(EId("path"))
        )
      )
    }

    case User(name, present, manageHome) => {
      val (uRoot, gRoot, hRoot, sub) = ("/etc/users", "/etc/groups", "/home", name)
      val (u, g, h) = (EId("userPath"), EId("groupPath"), EId("homePath"))
      val (ul, gl, hl, sl) = (-1, -1, -1, locMap(sub))
      val subPath = JavaPath(sub)

      SLet("userPath", EConcat(EPath(cpath(JavaPath(uRoot), ul)), EPath(cpath(subPath, sl))),
        SLet("groupPath", EConcat(EPath(cpath(JavaPath(gRoot), gl)), EPath(cpath(subPath, sl))),
          SLet("homePath", EConcat(EPath(cpath(JavaPath(hRoot), hl)), EPath(cpath(subPath, sl))),
            if (present) {
              val homeCmd = if (manageHome) {
                ite(PTestFileState(h, DoesNotExist), mkdir(h), SSkip)
              } else {
                SSkip
              }
              ite(PTestFileState(u, DoesNotExist), mkdir(u), SSkip) >>
              ite(PTestFileState(g, DoesNotExist), mkdir(g), SSkip) >>
              homeCmd
            } else {
              val homeCmd = if (manageHome) {
                ite(PTestFileState(h, DoesNotExist), SSkip, rm(h))
              } else {
                SSkip
              }
              ite(PTestFileState(u, DoesNotExist), SSkip, rm(u)) >>
              ite(PTestFileState(g, DoesNotExist), SSkip, rm(g)) >>
              homeCmd
            }
          )
        )
      )
    }

    case Group(name, present) => {
      val (root, sub) = ("/etc/groups", name)
      val (rLoc, sLoc) = (-1, locMap(sub))

      SLet("path", EConcat(EPath(cpath(JavaPath(root), rLoc)), EPath(cpath(JavaPath(sub), sLoc))),
        if (present) {
          ite(PTestFileState(EId("path"), DoesNotExist),
            mkdir(EId("path")),
            SSkip
          )
        } else {
          ite(PTestFileState(EId("path"), DoesNotExist),
            SSkip,
            rm(EId("path"))
          )
        }
      )
    }

    case Package(name, true) => {
      val paths = queryPackage(distro, name).getOrElse(throw PackageNotFound(distro, name))
      val dirs = paths.map(_.ancestors).reduce(_ union _) - root -- Settings.assumedDirs.toSet
      val files = paths -- dirs

      val mkdirs = dirs.toSeq.sortBy(_.getNameCount).map { p =>
        SLet("path", interpMap.toFSPlus(p, locMap),
          ite(PTestFileState(EId("path"), IsDir),
            SSkip,
            mkdir(EId("path"))
          )
        )
      }

      val content = "arbitrary content"
      val mkfiles = files.toSeq.map { p =>
        mkfile(interpMap.toFSPlus(p, locMap), EString(cstr(content, -1)))
      }

      val stmts = mkdirs ++ mkfiles
      val (main, sub) = ("/packages/", name)
      val (mLoc, sLoc) = (-1, locMap(sub))

      // apt does not remove pre-existing conflicting files.
      SLet("path", EConcat(EPath(cpath(JavaPath(main), mLoc)), EPath(cpath(JavaPath(sub), sLoc))),
        ite(PTestFileState(EId("path"), IsFile("")),
          SSkip,
          mkfile(EId("path"), EString(cstr(content, -1))) >> seq(stmts: _*)
        )
      )
    }

    case Package(name, false) => {
      val files = queryPackage(distro, name).getOrElse(throw PackageNotFound(distro, name)).toSeq
      val stmts: Seq[Statement] = files.map { p =>
        SLet("path", interpMap.toFSPlus(p, locMap),
          ite(PTestFileState(EId("path"), DoesNotExist),
            SSkip,
            rm(EId("path"))
          )
        )
      }

      val (root, sub) = ("/packages/", name)
      val (rLoc, sLoc) = (-1, locMap(sub))

      SLet("path", EConcat(EPath(cpath(JavaPath(root), rLoc)), EPath(cpath(JavaPath(sub), sLoc))),
        ite(PTestFileState(EId("path"), DoesNotExist),
          SSkip,
          rm(EId("path")) >> seq(stmts: _*)
        )
      )
    }

    case self@SshAuthorizedKey(user, present, _, key) => {
      SLet("path", EPath(cpath(JavaPath(self.keyPath), locMap(self.keyPath))),
        if (present) {
          ite(PTestFileState(EId("path"), IsFile("")),
            rm(EId("path")),
            SSkip
          ) >> mkfile(EId("path"), EString(cstr(key, locMap(key))))
        } else {
          ite(PTestFileState(EId("path"), IsFile("")),
            rm(EId("path")),
            SSkip
          )
        }
      )
    }

    case self@Service(name) => {
      ite(PTestFileState(EPath(cpath(JavaPath(self.path), locMap(self.path))), IsFile("")),
        SSkip,
        SError
      )
    }

    case Cron(present, cmd, user, hour, minute, month, monthday) => {
      val name = cmd.hashCode.toString + "-" + cmd.toLowerCase.filter(c => c >= 'a' && c <= 'z')
      val root = Settings.modelRoot
      val content = "arbitrary content"

      val pathProg = EConcat(
        EPath(cpath(JavaPath(root), -1)),
        EConcat(
          EPath(cpath(JavaPath("crontab-"), -1)),
          EPath(cpath(JavaPath(name), locMap(name)))
        )
      )
      SLet("path", pathProg,
        if (present) {
          ite(PTestFileState(EId("path"), DoesNotExist),
            mkfile(EId("path"), EString(cstr(content, locMap(content)))),
            SSkip
          )
        } else {
          ite(PTestFileState(EId("path"), DoesNotExist),
            SSkip,
            rm(EId("path"))
          )
        }
      )
    }

    case Host(ensure, name, ip, target) => {
      val root = Settings.modelRoot
      val content = "Managed by Rehearsal."

      val s1 = {
        ite(PTestFileState(EId("target"), DoesNotExist),
          SSkip,
          rm(EId("target"))
        ) >> mkfile(EId("target"), EString(cstr(content, locMap(content))))
      }

      val s2 = if (ensure) {
        ite(PTestFileState(EId("path"), DoesNotExist),
          mkfile(EId("path"), EString(cstr(ip, locMap(ip)))),
          SSkip
        )
      } else {
        ite(PTestFileState(EId("path"), DoesNotExist),
          SSkip,
          rm(EId("path"))
        )
      }

      val pathProg = EConcat(
        EPath(cpath(JavaPath(root), -1)),
        EConcat(
          EPath(cpath(JavaPath("host-"), -1)),
          EPath(cpath(JavaPath(name), locMap(name)))
        )
      )

      SLet("path", pathProg,
        SLet("target", EPath(cpath(JavaPath(target), locMap(target))),
          s1 >> s2
        )
      )
    }

    case Notify => SSkip

    case _ => ???

  }
}
