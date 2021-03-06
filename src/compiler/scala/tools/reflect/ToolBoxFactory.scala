package scala
package tools
package reflect

import scala.tools.nsc.EXPRmode
import scala.tools.nsc.reporters._
import scala.tools.nsc.CompilerCommand
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.util.AbstractFileClassLoader
import scala.reflect.internal.Flags._
import scala.reflect.internal.util.{BatchSourceFile, NoSourceFile, NoFile}
import java.lang.{Class => jClass}
import scala.compat.Platform.EOL
import scala.reflect.NameTransformer
import scala.reflect.api.JavaUniverse

abstract class ToolBoxFactory[U <: JavaUniverse](val u: U) { factorySelf =>

  val mirror: u.Mirror

  def mkToolBox(frontEnd: FrontEnd = mkSilentFrontEnd(), options: String = ""): ToolBox[U] =
    new ToolBoxImpl(frontEnd, options)

  private class ToolBoxImpl(val frontEnd: FrontEnd, val options: String) extends ToolBox[U] { toolBoxSelf =>

    val u: factorySelf.u.type = factorySelf.u

    lazy val classLoader = new AbstractFileClassLoader(virtualDirectory, factorySelf.mirror.classLoader)
    lazy val mirror: u.Mirror = u.runtimeMirror(classLoader)

    class ToolBoxGlobal(settings: scala.tools.nsc.Settings, reporter0: Reporter)
    extends ReflectGlobal(settings, reporter0, toolBoxSelf.classLoader) {
      import definitions._

      private val trace = scala.tools.nsc.util.trace when settings.debug.value

      private var wrapCount = 0

      private final val wrapperMethodName = "wrapper"

      private def nextWrapperModuleName() = {
        wrapCount += 1
        // we need to use UUIDs here, because our toolbox might be spawned by another toolbox
        // that already has, say, __wrapper$1 in its virtual directory, which will shadow our codegen
        newTermName("__wrapper$" + wrapCount + "$" + java.util.UUID.randomUUID.toString.replace("-", ""))
      }

      // should be called after every use of ToolBoxGlobal in order to prevent leaks
      // there's the `withCleanupCaches` method defined below, which provides a convenient interface for that
      def cleanupCaches(): Unit = {
        perRunCaches.clearAll()
        undoLog.clear()
        analyzer.lastTreeToTyper = EmptyTree
        lastSeenSourceFile = NoSourceFile
        lastSeenContext = null
      }

      def withCleanupCaches[T](body: => T): T =
        try body
        finally cleanupCaches()

      def verify(expr: Tree): Unit = {
        // Previously toolboxes used to typecheck their inputs before compiling.
        // Actually, the initial demo by Martin first typechecked the reified tree,
        // then ran it, which typechecked it again, and only then launched the
        // reflective compiler.
        //
        // However, as observed in https://issues.scala-lang.org/browse/SI-5464
        // current implementation typechecking is not always idempotent.
        // That's why we cannot allow inputs of toolboxes to be typechecked,
        // at least not until the aforementioned issue is closed.
        val typed = expr filter (t => t.tpe != null && t.tpe != NoType && !t.isInstanceOf[TypeTree])
        if (!typed.isEmpty) throw ToolBoxError("reflective toolbox has failed: cannot operate on trees that are already typed")

        if (expr.freeTypes.nonEmpty) {
          val ft_s = expr.freeTypes map (ft => s"  ${ft.name} ${ft.origin}") mkString "\n  "
          throw ToolBoxError(s"""
            |reflective toolbox failed due to unresolved free type variables:
            |$ft_s
            |have you forgotten to use TypeTag annotations for type parameters external to a reifee?
            |if you have troubles tracking free type variables, consider using -Xlog-free-types
            """.stripMargin.trim)
        }
      }

      def wrapIntoTerm(tree: Tree): Tree =
        if (!tree.isTerm) Block(List(tree), Literal(Constant(()))) else tree

      def unwrapFromTerm(tree: Tree): Tree = tree match {
        case Block(List(tree), Literal(Constant(()))) => tree
        case tree => tree
      }

      def extractFreeTerms(expr0: Tree, wrapFreeTermRefs: Boolean): (Tree, scala.collection.mutable.LinkedHashMap[FreeTermSymbol, TermName]) = {
        val freeTerms = expr0.freeTerms
        val freeTermNames = scala.collection.mutable.LinkedHashMap[FreeTermSymbol, TermName]()
        freeTerms foreach (ft => {
          var name = ft.name.toString
          val namesakes = freeTerms takeWhile (_ != ft) filter (ft2 => ft != ft2 && ft.name == ft2.name)
          if (namesakes.length > 0) name += ("$" + (namesakes.length + 1))
          freeTermNames += (ft -> newTermName(name + nme.REIFY_FREE_VALUE_SUFFIX))
        })
        val expr = new Transformer {
          override def transform(tree: Tree): Tree =
            if (tree.hasSymbolField && tree.symbol.isFreeTerm) {
              tree match {
                case Ident(_) =>
                  val freeTermRef = Ident(freeTermNames(tree.symbol.asFreeTerm))
                  if (wrapFreeTermRefs) Apply(freeTermRef, List()) else freeTermRef
                case _ =>
                  throw new Error("internal error: %s (%s, %s) is not supported".format(tree, tree.productPrefix, tree.getClass))
              }
            } else {
              super.transform(tree)
            }
        }.transform(expr0)
        (expr, freeTermNames)
      }

      def transformDuringTyper(expr0: Tree, withImplicitViewsDisabled: Boolean, withMacrosDisabled: Boolean)(transform: (analyzer.Typer, Tree) => Tree): Tree = {
        verify(expr0)

        // need to wrap the expr, because otherwise you won't be able to typecheck macros against something that contains free vars
        var (expr, freeTerms) = extractFreeTerms(expr0, wrapFreeTermRefs = false)
        val dummies = freeTerms.map{ case (freeTerm, name) => ValDef(NoMods, name, TypeTree(freeTerm.info), Select(Ident(PredefModule), newTermName("$qmark$qmark$qmark"))) }.toList
        expr = Block(dummies, wrapIntoTerm(expr))

        // [Eugene] how can we implement that?
        // !!! Why is this is in the empty package? If it's only to make
        // it inaccessible then please put it somewhere designed for that
        // rather than polluting the empty package with synthetics.
        val ownerClass    = rootMirror.EmptyPackageClass.newClassSymbol(newTypeName("<expression-owner>"))
        build.setTypeSignature(ownerClass, ClassInfoType(List(ObjectClass.tpe), newScope, ownerClass))
        val owner         = ownerClass.newLocalDummy(expr.pos)
        val currentTyper  = analyzer.newTyper(analyzer.rootContext(NoCompilationUnit, EmptyTree).make(expr, owner))
        val wrapper1      = if (!withImplicitViewsDisabled) (currentTyper.context.withImplicitsEnabled[Tree] _) else (currentTyper.context.withImplicitsDisabled[Tree] _)
        val wrapper2      = if (!withMacrosDisabled) (currentTyper.context.withMacrosEnabled[Tree] _) else (currentTyper.context.withMacrosDisabled[Tree] _)
        def wrapper       (tree: => Tree) = wrapper1(wrapper2(tree))

        phase = (new Run).typerPhase // need to set a phase to something <= typerPhase, otherwise implicits in typedSelect will be disabled
        currentTyper.context.setReportErrors() // need to manually set context mode, otherwise typer.silent will throw exceptions
        reporter.reset()

        val expr1 = wrapper(transform(currentTyper, expr))
        var (dummies1, unwrapped) = expr1 match {
          case Block(dummies, unwrapped) => ((dummies, unwrapped))
          case unwrapped                 => ((Nil, unwrapped))
        }
        val invertedIndex = freeTerms map (_.swap)
        // todo. also fixup singleton types
        unwrapped = new Transformer {
          override def transform(tree: Tree): Tree =
            tree match {
              case Ident(name: TermName) if invertedIndex contains name =>
                Ident(invertedIndex(name)) setType tree.tpe
              case _ =>
                super.transform(tree)
            }
        }.transform(unwrapped)
        new TreeTypeSubstituter(dummies1 map (_.symbol), dummies1 map (dummy => SingleType(NoPrefix, invertedIndex(dummy.symbol.name.toTermName)))).traverse(unwrapped)
        unwrapped = if (expr0.isTerm) unwrapped else unwrapFromTerm(unwrapped)
        unwrapped
      }

      def typeCheck(expr: Tree, pt: Type, silent: Boolean, withImplicitViewsDisabled: Boolean, withMacrosDisabled: Boolean): Tree =
        transformDuringTyper(expr, withImplicitViewsDisabled = withImplicitViewsDisabled, withMacrosDisabled = withMacrosDisabled)(
          (currentTyper, expr) => {
            trace("typing (implicit views = %s, macros = %s): ".format(!withImplicitViewsDisabled, !withMacrosDisabled))(showAttributed(expr, true, true, settings.Yshowsymkinds.value))
            currentTyper.silent(_.typed(expr, EXPRmode, pt)) match {
              case analyzer.SilentResultValue(result) =>
                trace("success: ")(showAttributed(result, true, true, settings.Yshowsymkinds.value))
                result
              case error @ analyzer.SilentTypeError(_) =>
                trace("failed: ")(error.err.errMsg)
                if (!silent) throw ToolBoxError("reflective typecheck has failed: %s".format(error.err.errMsg))
                EmptyTree
            }
          })

      def inferImplicit(tree: Tree, pt: Type, isView: Boolean, silent: Boolean, withMacrosDisabled: Boolean, pos: Position): Tree =
        transformDuringTyper(tree, withImplicitViewsDisabled = false, withMacrosDisabled = withMacrosDisabled)(
          (currentTyper, tree) => {
            trace("inferring implicit %s (macros = %s): ".format(if (isView) "view" else "value", !withMacrosDisabled))(showAttributed(pt, true, true, settings.Yshowsymkinds.value))
            analyzer.inferImplicit(tree, pt, isView, currentTyper.context, silent, withMacrosDisabled, pos, (pos, msg) => throw ToolBoxError(msg))
          })

      def compile(expr0: Tree): () => Any = {
        val expr = wrapIntoTerm(expr0)

        val freeTerms = expr.freeTerms // need to calculate them here, because later on they will be erased
        val thunks = freeTerms map (fte => () => fte.value) // need to be lazy in order not to distort evaluation order
        verify(expr)

        def wrap(expr0: Tree): ModuleDef = {
          val (expr, freeTerms) = extractFreeTerms(expr0, wrapFreeTermRefs = true)

          val (obj, _) = rootMirror.EmptyPackageClass.newModuleAndClassSymbol(
            nextWrapperModuleName())

          val minfo = ClassInfoType(List(ObjectClass.tpe), newScope, obj.moduleClass)
          obj.moduleClass setInfo minfo
          obj setInfo obj.moduleClass.tpe

          val meth = obj.moduleClass.newMethod(newTermName(wrapperMethodName))
          def makeParam(schema: (FreeTermSymbol, TermName)) = {
            // see a detailed explanation of the STABLE trick in `GenSymbols.reifyFreeTerm`
            val (fv, name) = schema
            meth.newValueParameter(name, newFlags = if (fv.hasStableFlag) STABLE else 0) setInfo appliedType(definitions.FunctionClass(0).tpe, List(fv.tpe.resultType))
          }
          meth setInfo MethodType(freeTerms.map(makeParam).toList, AnyClass.tpe)
          minfo.decls enter meth
          def defOwner(tree: Tree): Symbol = tree find (_.isDef) map (_.symbol) match {
            case Some(sym) if sym != null && sym != NoSymbol => sym.owner
            case _ => NoSymbol
          }
          trace("wrapping ")(defOwner(expr) -> meth)
          val methdef = DefDef(meth, expr changeOwner (defOwner(expr) -> meth))

          val moduledef = ModuleDef(
              obj,
              Template(
                  List(TypeTree(ObjectClass.tpe)),
                  emptyValDef,
                  NoMods,
                  List(),
                  List(methdef),
                  NoPosition))
          trace("wrapped: ")(showAttributed(moduledef, true, true, settings.Yshowsymkinds.value))

          val cleanedUp = resetLocalAttrs(moduledef)
          trace("cleaned up: ")(showAttributed(cleanedUp, true, true, settings.Yshowsymkinds.value))
          cleanedUp.asInstanceOf[ModuleDef]
        }

        val mdef = wrap(expr)
        val pdef = PackageDef(Ident(mdef.name), List(mdef))
        val unit = new CompilationUnit(NoSourceFile)
        unit.body = pdef

        val run = new Run
        reporter.reset()
        run.compileUnits(List(unit), run.namerPhase)
        throwIfErrors()

        val className = mdef.symbol.fullName
        if (settings.debug) println("generated: "+className)
        def moduleFileName(className: String) = className + "$"
        val jclazz = jClass.forName(moduleFileName(className), true, classLoader)
        val jmeth = jclazz.getDeclaredMethods.find(_.getName == wrapperMethodName).get
        val jfield = jclazz.getDeclaredFields.find(_.getName == NameTransformer.MODULE_INSTANCE_NAME).get
        val singleton = jfield.get(null)

        // @odersky writes: Not sure we will be able to drop this. I forgot the reason why we dereference () functions,
        // but there must have been one. So I propose to leave old version in comments to be resurrected if the problem resurfaces.
        // @Eugene writes: this dates back to the days when one could only reify functions
        // hence, blocks were translated into nullary functions, so
        // presumably, it was useful to immediately evaluate them to get the result of a block
        // @Eugene writes: anyways, I'll stash the old sources here in comments in case anyone wants to revive them
        // val result = jmeth.invoke(singleton, freeTerms map (sym => sym.asInstanceOf[FreeTermVar].value.asInstanceOf[AnyRef]): _*)
        // if (etpe.typeSymbol != FunctionClass(0)) result
        // else {
        //   val applyMeth = result.getClass.getMethod("apply")
        //   applyMeth.invoke(result)
        // }
        () => {
          val result = jmeth.invoke(singleton, thunks map (_.asInstanceOf[AnyRef]): _*)
          if (jmeth.getReturnType == java.lang.Void.TYPE) ()
          else result
        }
      }

      def parse(code: String): Tree = {
        val run = new Run
        reporter.reset()
        val wrappedCode = "object wrapper {" + EOL + code + EOL + "}"
        val file = new BatchSourceFile("<toolbox>", wrappedCode)
        val unit = new CompilationUnit(file)
        phase = run.parserPhase
        val parser = newUnitParser(unit)
        val wrappedTree = parser.parse()
        throwIfErrors()
        val PackageDef(_, List(ModuleDef(_, _, Template(_, _, _ :: parsed)))) = wrappedTree
        parsed match {
          case expr :: Nil => expr
          case stats :+ expr => Block(stats, expr)
        }
      }

      def showAttributed(artifact: Any, printTypes: Boolean = true, printIds: Boolean = true, printKinds: Boolean = false): String = {
        val saved1 = settings.printtypes.value
        val saved2 = settings.uniqid.value
        val saved3 = settings.Yshowsymkinds.value
        try {
          settings.printtypes.value = printTypes
          settings.uniqid.value = printIds
          settings.Yshowsymkinds.value = printKinds
          artifact.toString
        } finally {
          settings.printtypes.value = saved1
          settings.uniqid.value = saved2
          settings.Yshowsymkinds.value = saved3
        }
      }

      // reporter doesn't accumulate errors, but the front-end does
      def throwIfErrors() = {
        if (frontEnd.hasErrors) throw ToolBoxError(
          "reflective compilation has failed: " + EOL + EOL + (frontEnd.infos map (_.msg) mkString EOL)
        )
      }
    }

    // todo. is not going to work with quoted arguments with embedded whitespaces
    lazy val arguments = options.split(" ")

    lazy val virtualDirectory =
      (arguments zip arguments.tail).collect{ case ("-d", dir) => dir }.lastOption match {
        case Some(outDir) => scala.tools.nsc.io.AbstractFile.getDirectory(outDir)
        case None => new VirtualDirectory("(memory)", None)
      }

    lazy val compiler: ToolBoxGlobal = {
      try {
        val errorFn: String => Unit = msg => frontEnd.log(scala.reflect.internal.util.NoPosition, msg, frontEnd.ERROR)
        val command = new CompilerCommand(arguments.toList, errorFn)
        command.settings.outputDirs setSingleOutput virtualDirectory
        val instance = new ToolBoxGlobal(command.settings, frontEndToReporter(frontEnd, command.settings))
        if (frontEnd.hasErrors) {
          throw ToolBoxError(
            "reflective compilation has failed: cannot initialize the compiler: " + EOL + EOL +
            (frontEnd.infos map (_.msg) mkString EOL)
          )
        }
        instance
      } catch {
        case ex: Throwable =>
          throw ToolBoxError(s"reflective compilation has failed: cannot initialize the compiler due to $ex", ex)
      }
    }

    lazy val importer = compiler.mkImporter(u)
    lazy val exporter = importer.reverse

    def typeCheck(tree: u.Tree, expectedType: u.Type, silent: Boolean = false, withImplicitViewsDisabled: Boolean = false, withMacrosDisabled: Boolean = false): u.Tree = compiler.withCleanupCaches {
      if (compiler.settings.verbose) println("importing "+tree+", expectedType = "+expectedType)
      val ctree: compiler.Tree = importer.importTree(tree)
      val cexpectedType: compiler.Type = importer.importType(expectedType)

      if (compiler.settings.verbose) println("typing "+ctree+", expectedType = "+expectedType)
      val ttree: compiler.Tree = compiler.typeCheck(ctree, cexpectedType, silent = silent, withImplicitViewsDisabled = withImplicitViewsDisabled, withMacrosDisabled = withMacrosDisabled)
      val uttree = exporter.importTree(ttree)
      uttree
    }

    def inferImplicitValue(pt: u.Type, silent: Boolean = true, withMacrosDisabled: Boolean = false, pos: u.Position = u.NoPosition): u.Tree = {
      inferImplicit(u.EmptyTree, pt, isView = false, silent = silent, withMacrosDisabled = withMacrosDisabled, pos = pos)
    }

    def inferImplicitView(tree: u.Tree, from: u.Type, to: u.Type, silent: Boolean = true, withMacrosDisabled: Boolean = false, pos: u.Position = u.NoPosition): u.Tree = {
      val viewTpe = u.appliedType(u.definitions.FunctionClass(1).toTypeConstructor, List(from, to))
      inferImplicit(tree, viewTpe, isView = true, silent = silent, withMacrosDisabled = withMacrosDisabled, pos = pos)
    }

    private def inferImplicit(tree: u.Tree, pt: u.Type, isView: Boolean, silent: Boolean, withMacrosDisabled: Boolean, pos: u.Position): u.Tree = compiler.withCleanupCaches {
      if (compiler.settings.verbose) println(s"importing pt=$pt, tree=$tree, pos=$pos")
      val ctree: compiler.Tree = importer.importTree(tree)
      val cpt: compiler.Type = importer.importType(pt)
      val cpos: compiler.Position = importer.importPosition(pos)

      if (compiler.settings.verbose) println("inferring implicit %s of type %s, macros = %s".format(if (isView) "view" else "value", pt, !withMacrosDisabled))
      val itree: compiler.Tree = compiler.inferImplicit(ctree, cpt, isView = isView, silent = silent, withMacrosDisabled = withMacrosDisabled, pos = cpos)
      val uitree = exporter.importTree(itree)
      uitree
    }

    def resetAllAttrs(tree: u.Tree): u.Tree = {
      val ctree: compiler.Tree = importer.importTree(tree)
      val ttree: compiler.Tree = compiler.resetAllAttrs(ctree)
      val uttree = exporter.importTree(ttree)
      uttree
    }

    def resetLocalAttrs(tree: u.Tree): u.Tree = {
      val ctree: compiler.Tree = importer.importTree(tree)
      val ttree: compiler.Tree = compiler.resetLocalAttrs(ctree)
      val uttree = exporter.importTree(ttree)
      uttree
    }

    def parse(code: String): u.Tree = {
      if (compiler.settings.verbose) println("parsing "+code)
      val ctree: compiler.Tree = compiler.parse(code)
      val utree = exporter.importTree(ctree)
      utree
    }

    def compile(tree: u.Tree): () => Any = {
      if (compiler.settings.verbose) println("importing "+tree)
      val ctree: compiler.Tree = importer.importTree(tree)

      if (compiler.settings.verbose) println("compiling "+ctree)
      compiler.compile(ctree)
    }

    def eval(tree: u.Tree): Any = compile(tree)()
  }
}
