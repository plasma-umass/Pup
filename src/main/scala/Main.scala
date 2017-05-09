package pup

import java.io._
import scala.util.{Try, Success, Failure}
import benchmarks._

object Main extends App {

  case class Config(
    command: Config => Unit,
    string: Map[String, String],
    bool: Map[String, Boolean],
    int: Map[String, Int]
  )

  def usage(config: Config): Unit = {
    println(parser.usage)
    System.exit(1)
  }

  def watch(config: Config): Unit = {
    import java.nio.file.{Files, Paths, StandardOpenOption}
    import java.nio.charset._

    val fileName = config.string("filename")
    val shell = config.string("shell")

    val strace = STrace(fileName, shell).start()
  }

  def shell(config: Config): Unit = {
    val fileName = config.string("filename")
    PupShell(fileName).start()
  }

  def synthesize(config: Config): Unit = {
    import Implicits._

    val fileName = config.string("filename")
    val constraintString = config.string("constraints")

    Try({
      val manifest = PuppetParser.parseFile(fileName)
      println(s"Original manifest:\n\n${manifest.pretty}\n")

      val labeledManifest = manifest.labeled
      val constraints = ConstraintParser.parse(constraintString)
      val prog = labeledManifest.compile

      val progPaths = FSVisitors.collectPaths(manifest.labeled.compile).flatMap(_.ancestors)
      val constraintPaths = constraints.flatMap(_.paths.flatMap(_.ancestors)).toSet
      val paths = progPaths -- constraintPaths

      val substs = Synthesizer.synthesizeAll(
        prog, constraints, SynthTransformers.doNotEditPaths(paths)
      )

      if (substs.size > 0) {
        Some(UpdateRanker.promptRankedChoice(substs)(labeledManifest))
      } else {
        None
      }
    }) match {
      case Success(Some(result)) => {
        println("Update synthesis was successful!\nThe updated manifest is:")
        println()
        println(result.pretty)
      }
      case Success(None) => {
        println("Failed to synthesize an update to the specified manifest given those constraints.")
      }
      case Failure(exn) => throw exn
    }
  }

  def sizeBenchmark(config: Config): Unit = {
    val inFile = config.string("infile")
    val outFile = config.string("outfile")
    val constraintString = config.string("constraints")
    val trials = config.int("trials")
    val max = config.int("max")

    val manifest = PuppetParser.parseFile(inFile)
    val constraints = ConstraintParser.parse(constraintString)

    val res = SizeScaling.benchmark(manifest, constraints, trials, max)

    outputBenchmark(outFile, trials, res)
  }

  def updateBenchmark(config: Config): Unit = {
    val outFile = config.string("outfile")
    val trials = config.int("trials")
    val max = config.int("max")

    val res = UpdateScaling.benchmark(trials, max)

    outputBenchmark(outFile, trials, res)
  }

  def outputBenchmark(outFile: String, trials: Int, res: Map[Int, Seq[Long]]): Unit = {
    val header = 1.to(trials).foldLeft("size") {
      case (acc, trial) => s"$acc,trial$trial"
    }

    val entries = res.toSeq.sortBy(_._1).map {
      case (key, value) => {
        val values = value.foldRight("") {
          case (time, acc) => s",$time$acc"
        }
        s"$key$values"
      }
    }

    printToFile(new File(outFile)) {
      p => {
        p.println(header)
        entries.foreach(p.println)
      }
    }
  }

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  val parser = new scopt.OptionParser[Config]("pup") {

    def string(name: String) = opt[String](name).required.action {
      (x, c)  => c.copy(string = c.string + (name -> x))
    }

    def bool(name: String) =  opt[Boolean](name).required.action {
      (x, c)  => c.copy(bool = c.bool + (name -> x))
    }

    def int(name: String) = opt[Int](name).required.action {
      (x, c)  => c.copy(int = c.int + (name -> x))
    }

    head("pup", "0.1")

    cmd("synth")
      .action((_, c) => c.copy(command = synthesize))
      .text("Synthesize an update to the specified Puppet manifest.")
      .children(string("filename"), string("constraints"))

    cmd("shell")
      .action((_, c) => c.copy(command = shell))
      .text("Starts a simulated shell for updating the specified Puppet manifest.")
      .children(string("filename"))

    cmd("watch")
      .action((_, c) => c.copy(command = watch))
      .text("Instruments the specified shell for updating the specified Puppet manifest.")
      .children(string("filename"), string("shell"))

    cmd("size-bench")
      .action((_, c) => c.copy(command = sizeBenchmark))
      .text("Runs the size scalability benchmark for the specified manifest and constraints for a number of trials up to a max size.")
      .children(
        string("infile"), string("outfile"), string("constraints"), int("trials"), int("max")
      )

    cmd("update-bench")
      .action((_, c) => c.copy(command = updateBenchmark))
      .text("Runs the size scalability benchmark for the specified manifest and constraints for a number of trials up to a max size.")
      .children(string("outfile"), int("trials"), int("max"))
  }

  parser.parse(args, Config(usage, Map(), Map(), Map())) match {
    case None => {
      println(parser.usage)
      System.exit(1)
    }
    case Some(config) => {
      config.command(config)
    }
  }
}
