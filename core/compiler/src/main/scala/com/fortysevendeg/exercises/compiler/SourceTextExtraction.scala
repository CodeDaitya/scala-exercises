/*
 * scala-exercises-exercise-compiler
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises
package compiler

import scala.annotation.tailrec
import scala.language.postfixOps

import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc._
import scala.tools.nsc.doc.{ Settings ⇒ _, _ }

import scala.tools.nsc.doc.base.CommentFactoryBase
import scala.tools.nsc.doc.base.MemberLookupBase
import scala.tools.nsc.doc.base.LinkTo
import scala.tools.nsc.doc.base.LinkToExternal
import scala.tools.nsc.doc.base.comment.Comment

import cats._
import cats.std.all._
import cats.syntax.flatMap._
import cats.syntax.semigroup._

class SourceTextExtraction {
  private lazy val docGlobal = new DocExtractionGlobal()
  private lazy val commentParser = SourceTextExtraction.makeDocCommentParser(docGlobal)
  private lazy val boundExtractRaw = SourceTextExtraction.extractRaw(docGlobal)(_)
  private lazy val boundReadCode = MethodBodyReader.read(docGlobal)(_)

  import docGlobal._

  class MethodBody private[SourceTextExtraction] (lazyCode: ⇒ String) {
    lazy val code = lazyCode
  }

  class ExtractedComment private[SourceTextExtraction] (
      lazyRaw:     ⇒ String,
      lazyComment: ⇒ Comment
  ) {
    lazy val raw = lazyRaw
    lazy val comment = lazyComment
  }

  case class Extracted(
    comments:     Map[List[String], ExtractedComment],
    methodBodies: Map[List[String], MethodBody]
  )

  def extractAll(sources: List[String]): Extracted = {
    new docGlobal.Run() compileSources sources.map(code ⇒ new BatchSourceFile("(internal)", code))
    val extractions = docGlobal.currentRun.units.map(_.body).map(boundExtractRaw)
      .toList // only iterable once without this call

    def nameToString(name: Name) = name match {
      case TermName(value) ⇒ value
      case TypeName(value) ⇒ value
    }

    Extracted(
      extractions >>= {
        _.comments.map { kv ⇒
          kv._1.map(nameToString) → new ExtractedComment(kv._2.raw, commentParser.parseComment(kv._2))
        }
      } toMap,
      extractions >>= {
        _.methodExprs.map { kv ⇒ kv._1.map(nameToString) → new MethodBody(boundReadCode(kv._2)) }
      } toMap
    )

  }

  def extractAllComments(sources: List[String]): Map[List[String], String] = {
    new docGlobal.Run() compileSources sources.map(code ⇒ new BatchSourceFile("(internal)", code))
    val extractions = docGlobal.currentRun.units.map(_.body).map(boundExtractRaw)

    extractions.flatMap { extraction ⇒
      extraction.comments.map {
        case (k, v) ⇒ k.collect {
          case TermName(value) ⇒ value
          case TypeName(value) ⇒ value
        } → v.raw
      }
    }.toMap
  }

}

/** Utility to find doc exercise-worthy comments and source code blobs
  * in a tree.
  */
object SourceTextExtraction {

  private[compiler] sealed trait DocCommentParser[G <: Global] {
    val global: G
    def parseComment(comment: global.DocComment): Comment
    // at the moment, this is provided for testing
    def parseComment(comment: String): Comment
  }

  private[compiler] def makeDocCommentParser[G <: Global](g: G): DocCommentParser[g.type] = {
    new CommentFactoryBase with MemberLookupBase with DocCommentParser[g.type] {
      override val global: g.type = g
      import global._
      override def parseComment(comment: DocComment) = {
        val nowarnings = settings.nowarn.value
        settings.nowarn.value = true
        try parseAtSymbol(comment.raw, comment.raw, comment.pos)
        finally settings.nowarn.value = nowarnings
      }
      override def parseComment(comment: String) = parseComment(DocComment(comment))
      override def internalLink(sym: Symbol, site: Symbol): Option[LinkTo] = None
      override def chooseLink(links: List[LinkTo]): LinkTo = links.headOption.orNull
      override def toString(link: LinkTo): String = "No link"
      override def findExternalLink(sym: Symbol, name: String): Option[LinkToExternal] = None
      override def warnNoLink: Boolean = false
    }
  }

  type Path[G <: Global] = List[G#Name]
  case class RawAcc[G <: Global](
    comments:    List[(Path[G], G#DocComment)] = Nil,
    methodExprs: List[(Path[G], G#Tree)]       = Nil
  )

  def extractRaw[G <: Global](g: G)(rootTree: g.Tree): RawAcc[g.type] = {
    import g._

    /** Define generic accumulating traversal that visits all the nodes of
      * interest.
      */
    def traverse[A: Semigroup](
      trees0:          List[(Path[g.type], Tree)],
      acc0:            A,
      visitDocComment: (Path[g.type], g.DocComment) ⇒ A,
      visitMethodExpr: (Path[g.type], g.Tree) ⇒ A
    ): A = {

      // a nested function so that we don't have to include visitDocComment and
      // visitMethodExpr as trailing params on each recursive call
      @tailrec def traversal(trees: List[(Path[g.type], Tree)], acc: A): A = trees match {
        case Nil ⇒ acc
        case (path, tree) :: rs ⇒ tree match {

          case DocDef(comment, moduleDef @ ModuleDef(mods, _, impl)) ⇒
            val nextPath = moduleDef.name :: path
            traversal(
              impl.body.map(nextPath → _) ::: rs,
              visitDocComment(nextPath.reverse, comment) |+| acc
            )

          case DocDef(comment, classDef @ ClassDef(mods, _, Nil, impl)) ⇒
            val nextPath = classDef.name :: path
            traversal(
              impl.body.map(nextPath → _) ::: rs,
              visitDocComment(nextPath.reverse, comment) |+| acc
            )

          case DocDef(comment, q"def $tname(...$paramss): $tpt = $expr") ⇒
            val nextPath = tname :: path
            val nextPathReversed = nextPath.reverse
            traversal(
              rs,
              visitMethodExpr(nextPathReversed, expr) |+|
                visitDocComment(nextPathReversed, comment) |+| acc
            )

          case moduleDef @ ModuleDef(mods, _, impl) ⇒
            val nextPath = moduleDef.name :: path
            traversal(
              impl.body.map(nextPath → _) ::: rs,
              acc
            )

          case classDef @ ClassDef(mods, _, Nil, impl) ⇒
            val nextPath = classDef.name :: path
            traversal(
              impl.body.map(nextPath → _) ::: rs,
              acc
            )

          case q"def $tname(...$paramss): $tpt = $expr" ⇒
            val nextPath = tname :: path
            traversal(
              (nextPath → expr) :: rs,
              acc
            )

          case q"package $ref { ..$topstats }" ⇒
            val nextPath =
              if (ref.name == termNames.EMPTY_PACKAGE_NAME) path
              else ref.name :: path
            traversal(
              topstats.map(nextPath → _) ::: rs,
              acc
            )

          case _ ⇒
            traversal(
              rs,
              acc
            )
        }
      }
      // go
      traversal(trees0, acc0)
    }

    implicit def accumulatorSemigroup[G <: Global] = new Semigroup[RawAcc[G]] {
      override def combine(x: RawAcc[G], y: RawAcc[G]) =
        RawAcc(
          comments = x.comments ::: y.comments,
          methodExprs = x.methodExprs ::: y.methodExprs
        )
    }

    traverse[RawAcc[g.type]](
      trees0 = List(Nil → rootTree),
      acc0 = RawAcc[g.type](Nil, Nil),
      visitDocComment = { (path, comment) ⇒
        RawAcc(comments = (path → comment) :: Nil)
      },
      visitMethodExpr = { (path, expr) ⇒
        RawAcc(methodExprs = (path → expr) :: Nil)
      }
    )
  }

}

/** Scala compiler global needed for extracting doc comments. This uses the
  * ScaladocSyntaxAnalyzer, which keeps DocDefs in the parsed AST.
  *
  * It would be ideal to do this as a compiler plugin. Unfortunately there
  * doesn't seem to be a way to replace the syntax analyzer phase (named
  * "parser") with a plugin.
  */
class DocExtractionGlobal(settings: Settings = DocExtractionGlobal.defaultSettings) extends Global(settings) {

  override lazy val syntaxAnalyzer = new ScaladocSyntaxAnalyzer[this.type](this) {
    val runsAfter = List[String]()
    val runsRightAfter = None
    override val initial = true
  }

  override def newUnitParser(unit: CompilationUnit) = new syntaxAnalyzer.ScaladocUnitParser(unit, Nil)

  override protected def computeInternalPhases() {
    phasesSet += syntaxAnalyzer
    phasesSet += analyzer.namerFactory
  }

}

object DocExtractionGlobal {
  def defaultSettings = new Settings {
    embeddedDefaults[DocExtractionGlobal.type]
    // this flag is crucial for method body extraction
    Yrangepos.value = true
  }
}