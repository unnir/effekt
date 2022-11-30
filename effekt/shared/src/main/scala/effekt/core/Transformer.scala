package effekt
package core

import scala.collection.mutable.ListBuffer
import effekt.context.{ Annotations, Context, ContextOps }
import effekt.symbols.*
import effekt.symbols.builtins.*
import effekt.context.assertions.*
import effekt.source.MatchPattern

object Transformer extends Phase[Typechecked, CoreTransformed] {

  val phaseName = "transformer"

  def run(input: Typechecked)(using Context) =
    val Typechecked(source, tree, mod) = input
    Context.initTransformerState()
    Some(CoreTransformed(source, tree, mod, transform(mod, tree)))

  def transform(mod: Module, tree: source.ModuleDecl)(using Context): ModuleDecl = {
    val source.ModuleDecl(path, imports, defs) = tree
    val exports = mod.terms.flatMap {
      case (name, syms) => syms.collect {
        // TODO export valuebinders properly
        case sym: Callable if !sym.isInstanceOf[Operation] && !sym.isInstanceOf[Field] => sym
        case sym: ValBinder => sym
      }
    }.toList

    val toplevelDeclarations = defs.flatMap(d => transformToplevel(d))

    val definitions = toplevelDeclarations.collect { case d: Definition => optimize(d) }
    val externals = toplevelDeclarations.collect { case d: Extern => d }
    val declarations = toplevelDeclarations.collect { case d: Declaration => d }

    // We use the imports on the symbol (since they include the prelude)
    ModuleDecl(path, mod.imports.map { _.path }, declarations, externals, definitions, exports)
  }

  def transformToplevel(d: source.Def)(using Context): List[Definition | Declaration | Extern] = d match {
    case f @ source.FunDef(id, _, vps, bps, _, body) =>
      val sym = f.symbol
      val ps = (vps map transform) ++ (bps map transform)
      List(Definition.Def(sym, BlockLit(ps, transform(body))))

    case d @ source.DataDef(id, _, ctors) =>
      List(Data(d.symbol, ctors.map { c => c.symbol }))

    case d @ source.RecordDef(id, _, _) =>
      val rec = d.symbol
      List(core.Record(rec, rec.constructor.fields))

    case v @ source.ValDef(id, _, binding) if pureOrIO(binding) =>
      List(Definition.Let(v.symbol, Run(transform(binding))))

    case v @ source.ValDef(id, _, binding) =>
      Context.at(d) { Context.abort("Effectful bindings not allowed on the toplevel") }

    case v @ source.DefDef(id, annot, binding) =>
      val sym = v.symbol
      val (definition, bindings) = Context.withBindings {
        Definition.Def(sym, transformAsBlock(binding))
      }

      // convert binding into Definition.
      val additionalDefinitions = bindings.toList.map {
        case Binding.Let(name, binding) => Definition.Let(name, binding)
        case Binding.Def(name, binding) => Definition.Def(name, binding)
        case Binding.Val(name, binding) => Context.at(d) { Context.abort("Effectful bindings not allowed on the toplevel") }
      }
      additionalDefinitions ++ List(definition)

    case v @ source.VarDef(id, _, reg, binding) =>
      Context.at(d) { Context.abort("Mutable variable bindings currently not allowed on the toplevel") }

    case d @ source.InterfaceDef(id, tparams, ops, isEffect) =>
      List(core.Interface(d.symbol, ops.map { e => e.symbol }))

    case f @ source.ExternDef(pure, id, tps, vps, bps, ret, body) =>
      val sym = f.symbol
      List(Extern.Def(sym, Context.functionTypeOf(sym), (vps map transform) ++ (bps map transform), body))

    case e @ source.ExternInclude(path, contents, _) =>
      List(Extern.Include(contents.get))

    // For now we forget about all of the following definitions in core:
    case d: source.Def.Extern => Nil
    case d: source.Def.Alias => Nil
  }

  def transform(tree: source.Stmt)(using Context): Stmt = tree match {
    // { e; stmt } --> { let _ = e; stmt }
    case source.ExprStmt(e, rest) if pureOrIO(e) =>
      val (expr, bs) = Context.withBindings { transformAsExpr(e) }
      val let = Let(Wildcard(), expr, transform(rest))
      if (bs.isEmpty) { let }
      else { Context.reifyBindings(let, bs) }

    // { e; stmt } --> { val _ = e; stmt }
    case source.ExprStmt(e, rest) =>
      Val(Wildcard(), insertBindings { Return(transformAsPure(e)) }, transform(rest))

    // return e
    case source.Return(e) =>
      insertBindings { Return(transformAsPure(e)) }

    // simply drop superfluous {}s
    case source.BlockStmt(b) =>
      transform(b)

    case source.DefStmt(d, rest) => d match {
      case f @ source.FunDef(id, _, vps, bps, _, body) =>
        val sym = f.symbol
        val ps = (vps map transform) ++ (bps map transform)
        Def(sym, BlockLit(ps, transform(body)), transform(rest))

      case v @ source.ValDef(id, _, binding) if pureOrIO(binding) =>
        Let(v.symbol, Run(transform(binding)), transform(rest))

      case v @ source.ValDef(id, _, binding) =>
        Val(v.symbol, transform(binding), transform(rest))

      case v @ source.DefDef(id, annot, binding) =>
        val sym = v.symbol
        insertBindings {
          Def(sym, transformAsBlock(binding), transform(rest))
        }

      case v @ source.VarDef(id, _, reg, binding) =>
        val sym = v.symbol
        insertBindings {
          State(sym, Context.bind(transform(binding)), sym.region, transform(rest))
        }

      case d: source.Def.Extern => Context.panic("Only allowed on the toplevel")
      case d: source.Def.Declaration => Context.panic("Only allowed on the toplevel")

      // For now we forget about all of the following definitions in core:
      case d: source.Def.Alias => transform(rest)
    }
  }

  def transformUnbox(tree: source.Term)(implicit C: Context): Block =
    Unbox(transformAsPure(tree))

  def transformBox(tree: source.Term)(implicit C: Context): Pure =
    Box(transformAsBlock(tree))

  def transformAsBlock(tree: source.Term)(using Context): Block = tree match {
    case v: source.Var => v.definition match {
      case sym: ValueSymbol => transformUnbox(tree)
      case sym: BlockSymbol => BlockVar(sym)
    }
    case s @ source.Select(receiver, selector) =>
      Member(transformAsBlock(receiver), s.definition)

    case s @ source.New(h @ source.Implementation(tpe, members)) =>
      // we need to establish a canonical ordering of methods
      // we use the order in which they are declared in the signature (like with handlers)
      val clauses = members.map { cl => (cl.definition, cl) }.toMap
      val sig = h.definition

      New(Implementation(sig, sig.ops.map(clauses.apply).map {
        case op @ source.OpClause(id, tparams, vparams, ret, body, resume) =>
          val vps = vparams map transform
          // currently the operations don't take block params
          val opBlock: BlockLit = BlockLit(vps, transform(body))
          core.Operation(op.definition, opBlock)
      }))

    case source.Unbox(b) =>
      Unbox(transformAsPure(b))

    case source.BlockLiteral(tps, vps, bps, body) =>
      BlockLit((vps map transform) ++ (bps map transform), transform(body))

    case _ =>
      transformUnbox(tree)
  }

  def transformAsPure(tree: source.Term)(using Context): Pure = transformAsExpr(tree) match {
    case p: Pure => p
    case e: Expr => Context.bind(e)
  }

  def transformAsExpr(tree: source.Term)(using Context): Expr = tree match {
    case v: source.Var => v.definition match {
      case sym: VarBinder => DirectApp(Member(BlockVar(sym), TState.get), Nil, Nil)
      case sym: ValueSymbol => ValueVar(sym)
      case sym: BlockSymbol => transformBox(tree)
    }

    case source.Literal(value, tpe) => Literal(value, transform(tpe))

    case s @ source.Select(receiver, selector) =>
      Select(transformAsPure(receiver), s.definition)

    case source.Box(capt, block) =>
      transformBox(block)

    case source.New(impl) =>
      transformBox(tree)

    case source.Unbox(b) =>
      transformBox(tree)

    case source.BlockLiteral(tps, vps, bps, body) =>
      transformBox(tree)

    case source.If(cond, thn, els) =>
      val c = transformAsPure(cond)
      Context.bind(If(c, transform(thn), transform(els)))

    // [[ while(cond) { body } ]] =
    //   def loop$13() = if ([[cond]]) { [[ body ]]; loop$13() } else { return () }
    //   loop$13()
    case source.While(cond, body) =>
      val loopName = TmpBlock()
      val loopCall = Stmt.App(BlockVar(loopName), Nil, Nil)

      val loop = Block.BlockLit(Nil,
        insertBindings {
          Stmt.If(transformAsPure(cond),
            Stmt.Val(TmpValue(), transform(body), loopCall),
            Return(Literal((), core.Type.TUnit)))
        }
      )

      Context.bind(loopName, loop)

      // captures???
      if (Context.inferredCapture(cond) == CaptureSet.empty) {
        Context.at(cond) { Context.warning(pp"Condition to while loop is pure, which might not be intended.") }
      }

      Context.bind(loopCall)

    case source.Match(sc, cs) =>
      // (1) Bind scrutinee and all clauses so we do not have to deal with sharing on demand.
      val scrutinee: ValueVar = Context.bind(transformAsPure(sc))
      val clauses = cs.map(c => preprocess(scrutinee, c))
      val compiledMatch = Context.at(tree) { compileMatch(clauses) }
      Context.bind(compiledMatch)

    case source.TryHandle(prog, handlers) =>
      val caps = handlers.map { h =>
        val cap = h.capability.get.symbol
        BlockParam(cap)
      }
      val body = BlockLit(caps, transform(prog))

      // to obtain a canonical ordering of operation clauses, we use the definition ordering
      def transformHandler: source.Handler => core.Implementation = {
        case h @ source.Handler(cap, source.Implementation(eff, cls)) =>
          val clauses = cls.map { cl => (cl.definition, cl) }.toMap

          Implementation(h.definition, h.definition.ops.map(clauses.apply).map {
            case op @ source.OpClause(id, tps, vps, ret, body, resume) =>
              val ps = vps map transform

              // introduce a block parameter for resume
              val resumeSymbol = resume.symbol.asInstanceOf[BlockSymbol]
              val resumeParam = BlockParam(resumeSymbol)

              val opBlock: BlockLit = BlockLit(ps :+ resumeParam, transform(body))
              core.Operation(op.definition, opBlock)
          })
      }

      Context.bind(Try(body, handlers map transformHandler))

    case r @ source.Region(name, body) =>
      val sym = r.symbol
      val tpe = sym match {
        case b: BlockParam => b.tpe
        case b: SelfParam => builtins.TRegion
        case _ => Context.panic("Continuations cannot be regions")
      }
      val cap = core.BlockParam(sym, transform(tpe))
      Context.bind(Region(BlockLit(List(cap), transform(body))))

    case source.Hole(stmts) =>
      Context.bind(Hole)

    case a @ source.Assign(id, expr) =>
      val e = transformAsPure(expr)
      val sym = a.definition
      DirectApp(Member(BlockVar(sym), TState.put), Nil, List(e))

    // methods are dynamically dispatched, so we have to assume they are `control`, hence no PureApp.
    case c @ source.MethodCall(receiver, id, targs, vargs, bargs) =>
      val rec = transformAsBlock(receiver)
      val typeArgs = Context.typeArguments(c)
      val valueArgs = vargs.map(transformAsPure)
      val blockArgs = bargs.map(transformAsBlock)

      c.definition match {
        case sym if sym == TState.put || sym == TState.get =>
          DirectApp(Member(rec, sym), Nil, valueArgs ++ blockArgs)
        case sym =>
          Context.bind(App(Member(rec, sym), typeArgs, valueArgs ++ blockArgs))
      }

    case c @ source.Call(source.ExprTarget(source.Unbox(expr)), targs, vargs, bargs) =>

      val (funTpe, capture) = Context.inferredTypeOf(expr) match {
        case BoxedType(s: FunctionType, c: CaptureSet) => (s, c)
        case _ => Context.panic("Should be a boxed function type with a known capture set.")
      }
      val e = transformAsPure(expr)
      val typeArgs = Context.typeArguments(c)
      val valueArgs = vargs.map(transformAsPure)
      val blockArgs = bargs.map(transformAsBlock)

      if (pureOrIO(capture) && bargs.forall { pureOrIO }) {
        Run(App(Unbox(e), typeArgs, valueArgs ++ blockArgs))
      } else {
        Context.bind(App(Unbox(e), typeArgs, valueArgs ++ blockArgs))
      }

    case c @ source.Call(fun: source.IdTarget, _, vargs, bargs) =>
      // assumption: typer removed all ambiguous references, so there is exactly one
      makeFunctionCall(c, fun.definition, vargs, bargs)

    case c @ source.Call(s: source.ExprTarget, targs, vargs, bargs) =>
      Context.panic("Should not happen. Unbox should have been inferred.")

    case source.Do(effect, id, targs, vargs) =>
      Context.panic("Should have been translated away (to explicit selection `@CAP.op()`) by capability passing.")
  }

  def makeFunctionCall(call: source.CallLike, sym: TermSymbol, vargs: List[source.Term], bargs: List[source.Term])(using Context): Expr = {
    // the type arguments, inferred by typer
    val targs = Context.typeArguments(call)

    val vargsT = vargs.map(transformAsPure)
    val bargsT = bargs.map(transformAsBlock)
    val as = vargsT ++ bargsT

    sym match {
      case f: ExternFunction if isPure(f.capture) =>
        // if (bargs.nonEmpty) Context.abort("Pure builtin functions cannot take block arguments.")
        PureApp(BlockVar(f), targs, vargsT)
      case f: ExternFunction if pureOrIO(f.capture) =>
        DirectApp(BlockVar(f), targs, as)
      case r: Constructor =>
        if (bargs.nonEmpty) Context.abort("Constructors cannot take block arguments.")
        PureApp(BlockVar(r), targs, vargsT)
      case f: Operation =>
        Context.panic("Should have been translated to a method call!")
      case f: Field =>
        Context.panic("Should have been translated to a select!")
      case f: BlockSymbol if pureOrIO(f) && bargs.forall { pureOrIO } =>
        Run(App(BlockVar(f), targs, as))
      case f: BlockSymbol =>
        Context.bind(App(BlockVar(f), targs, as))
      case f: ValueSymbol =>
        Context.bind(App(Unbox(ValueVar(f)), targs, as))
    }
  }

  def transform(p: source.BlockParam)(using Context): core.BlockParam = BlockParam(p.symbol)

  def transform(p: source.ValueParam)(using Context): core.ValueParam = ValueParam(p.symbol)

  def insertBindings(stmt: => Stmt)(using Context): Stmt = {
    val (body, bindings) = Context.withBindings { stmt }
    Context.reifyBindings(body, bindings)
  }

  // Translation on Types
  // --------------------
  def transform(sc: ValueType)(using Context): core.ValueType = ???

  def transform(sc: BlockType)(using Context): core.BlockType = ???

  def transform(sc: Captures)(using Context): core.Captures = ???


  // Match Compiler
  // --------------
  // The implementation of the match compiler follows closely the short paper:
  //   Jules Jacob
  //   How to compile pattern matching
  //   https://julesjacobs.com/notes/patternmatching/patternmatching.pdf
  //
  // There also is a more advanced Rust implementation that we could look at:
  //   https://gitlab.com/yorickpeterse/pattern-matching-in-rust/-/tree/main/jacobs2021

  // case pats => label(args...)
  private case class Clause(patterns: Map[ValueVar, source.MatchPattern], label: BlockVar, args: List[ValueVar])

  // Uses the bind effect to bind the right hand sides of clauses!
  private def preprocess(sc: ValueVar, clause: source.MatchClause)(using Context): Clause = {
    def boundVars(p: source.MatchPattern): List[ValueParam] = p match {
      case p @ source.AnyPattern(id) => List(p.symbol)
      case source.TagPattern(id, patterns) => patterns.flatMap(boundVars)
      case _ => Nil
    }
    val params = boundVars(clause.pattern).map { p => (p, transform(Context.valueTypeOf(p))) }
    val body = transform(clause.body)
    val blockLit = BlockLit(params.map(core.ValueParam.apply), body)
    val returnType = Context.inferredTypeOf(clause.body)

    ??? // TODO construct core.BlockType
    // val blockTpe = symbols.FunctionType(Nil, Nil, params.map { case (_, tpe) => tpe }, Nil, returnType, Effects.Pure)

    // TODO Do we also need to annotate the capture???
    val joinpoint = Context.bind(???, blockLit)
    Clause(Map(sc -> clause.pattern), joinpoint, params.map(core.ValueVar.apply))
  }

  /**
   * The match compiler works with
   * - a sequence of clauses that represent alternatives (disjunction)
   * - each sequence contains a list of patterns that all have to match (conjunction).
   */
  private def compileMatch(clauses: Seq[Clause])(using Context): core.Stmt = {

    // matching on void will result in this case
    if (clauses.isEmpty) return core.Hole

    val normalizedClauses = clauses.map(normalize)

    def jumpToBranch(target: BlockVar, args: List[ValueVar]) =
      core.App(target, Nil, args)

    // (1) Check whether we are already successful
    val Clause(patterns, target, args) = normalizedClauses.head
    if (patterns.isEmpty) { return jumpToBranch(target, args) }

    def branchingHeuristic =
      patterns.keys.maxBy(v => normalizedClauses.count {
        case Clause(ps, _, _) => ps.contains(v)
      })

    // (2) Choose the variable to split on
    val splitVar = branchingHeuristic

    def mentionedVariants = normalizedClauses
      .flatMap(_.patterns.get(splitVar))
      .collect { case p : source.TagPattern => p.definition }

    val variants = mentionedVariants.distinct

    val clausesFor = collection.mutable.Map.empty[Constructor, Vector[Clause]]
    var defaults = Vector.empty[Clause]

    def addClause(c: Constructor, cl: Clause): Unit =
      val clauses = clausesFor.getOrElse(c, Vector.empty)
      clausesFor.update(c, clauses :+ cl)

    def addDefault(cl: Clause): Unit =
      defaults = defaults :+ cl

    def freshFields(c: Constructor): List[core.ValueVar] = c.fields.map { f =>
      val tmp = TmpValue.apply()
      // TODO We need to perform substitution here, no?
      //  or we need to annotate the types in typer.
      core.ValueVar(tmp, transform(f.returnType))
    }
    val varsFor: Map[Constructor, List[core.ValueVar]] = variants.map { v => v -> freshFields(v) }.toMap

    normalizedClauses.foreach {
      case c @ Clause(patterns, target, args) => patterns.get(splitVar) match {
        case Some(p @ source.TagPattern(id, ps)) =>
          val constructor = p.definition
          addClause(constructor, Clause(patterns - splitVar ++ varsFor(constructor).zip(ps), target, args))
        case Some(_) => Context.panic("Should not happen")
        case None =>
          // Clauses that don't match on that var are duplicated.
          // So we want to choose our branching heuristic to minimize this
          addDefault(c)
          // THIS ONE IS NOT LINEAR
          variants.foreach { v => addClause(v, c) }
      }
    }

    // TODO order variants by declaration of constructor.
    val branches = variants.toList.map { v =>
      // TODO only add defaultVar as `self`, if it is free in the body.
      val body = compileMatch(clausesFor.getOrElse(v, Vector.empty))
      val params = varsFor(v).map { case ValueVar(id, tpe) => core.ValueParam(id, tpe) }
      val blockLit: BlockLit = BlockLit(params, body)
      (v, blockLit)
    }

    val default = if defaults.isEmpty then None else Some(compileMatch(defaults))

    core.Match(splitVar, branches, default)
  }

  /**
   * Substitutes IdPatterns and removes wildcards (and literal patterns -- which are already ruled out by Typer);
   * only TagPatterns are left.
   */
  private def normalize(clause: Clause)(using Context): Clause = {
    val Clause(patterns, target, args) = clause
    val substitution = patterns.collect { case (v, source.AnyPattern(id)) => id.symbol -> v }
    val tagPatterns = patterns.collect { case (v, p: source.TagPattern) => v -> p }

    Clause(tagPatterns, target, args.map(v => substitution.getOrElse(v.id, v)))
  }


  // Helpers
  // -------

  // Helpers to constructed typed trees
  def ValueParam(id: ValueSymbol)(using Context): core.ValueParam =
    core.ValueParam(id, transform(Context.valueTypeOf(id)))

  def BlockParam(id: BlockSymbol)(using Context): core.BlockParam =
    core.BlockParam(id, transform(Context.blockTypeOf(id)))

  def ValueVar(id: ValueSymbol)(using Context): core.ValueVar =
    core.ValueVar(id, transform(Context.valueTypeOf(id)))

  def BlockVar(id: BlockSymbol)(using Context): core.BlockVar =
    core.BlockVar(id, transform(Context.blockTypeOf(id)), transform(Context.captureOf(id)))

  def Val(id: ValueSymbol, binding: Stmt, body: Stmt)(using Context): core.Val =
    core.Val(id, binding, body)

  def optimize(s: Definition)(using Context): Definition = {

    // a very small and easy post processing step...
    // reduces run-return pairs
    object eliminateReturnRun extends core.Tree.Rewrite {
      override def expr = {
        case core.Run(core.Return(p)) => rewrite(p)
      }
    }

    // rewrite (Val (Return e) s) to (Let e s)
    object directStyleVal extends core.Tree.Rewrite {
      override def stmt = {
        case core.Val(id, core.Return(expr), body) =>
          Let(id, rewrite(expr), rewrite(body))
      }
    }
    val opt = eliminateReturnRun.rewrite(s)
    directStyleVal.rewrite(opt)
  }

  def asConcreteCaptureSet(c: Captures)(using Context): CaptureSet = c match {
    case c: CaptureSet => c
    case _ => Context.panic("All capture unification variables should have been replaced by now.")
  }

  // we can conservatively approximate to false, in order to disable the optimizations
  def pureOrIO(t: source.Tree)(using Context): Boolean =
    Context.inferredCaptureOption(t) match {
      case Some(capt) => pureOrIO(asConcreteCaptureSet(capt))
      case _         => false
    }

  def isPure(t: source.Tree)(using Context): Boolean = Context.inferredCaptureOption(t) match {
    case Some(capt) => asConcreteCaptureSet(capt).captures.isEmpty
    case _         => false
  }

  def pureOrIO(t: BlockSymbol)(using Context): Boolean = pureOrIO(asConcreteCaptureSet(Context.captureOf(t)))

  def isPure(r: CaptureSet): Boolean = r.captures.isEmpty

  def pureOrIO(r: CaptureSet): Boolean = r.captures.forall {
    c =>
      def isIO = c == builtins.IOCapability.capture
      def isMutableState = c.isInstanceOf[LexicalRegion]
      def isResource = c.isInstanceOf[Resource]
      def isControl = c == builtins.ControlCapability.capture
      !isControl && (isIO || isMutableState || isResource)
  }
}

private[core] enum Binding {
  case Val(name: TmpValue, binding: Stmt)
  case Let(name: TmpValue, binding: Expr)
  case Def(name: BlockSymbol, binding: Block)
}

trait TransformerOps extends ContextOps { Context: Context =>


  /**
   * A _mutable_ ListBuffer that stores all bindings to be inserted at the current scope
   */
  private var bindings: ListBuffer[Binding] = ListBuffer()

  private[core] def initTransformerState() = {
    bindings = ListBuffer()
  }

  /**
   * Introduces a binding for the given statement.
   *
   * @param tpe the type of the bound statement
   * @param s the statement to be bound
   */
  private[core] def bind(s: Stmt): ValueVar = {

    // create a fresh symbol and assign the type
    val x = TmpValue()

    val binding = Binding.Val(x, s)
    bindings += binding

    ValueVar(x, s.tpe)
  }

  private[core] def bind(e: Expr): ValueVar = {

    // create a fresh symbol and assign the type
    val x = TmpValue()

    val binding = Binding.Let(x, e)
    bindings += binding

    ValueVar(x, e.tpe)
  }

  private[core] def bind(b: Block): BlockVar = {
    bind(TmpBlock(), b)
  }

  private[core] def bind(name: BlockSymbol, b: Block): BlockVar = {
    val binding = Binding.Def(name, b)
    bindings += binding
    BlockVar(name, b.tpe, b.capt)
  }

  private[core] def withBindings[R](block: => R): (R, ListBuffer[Binding]) = Context in {
    val before = bindings
    val b = ListBuffer.empty[Binding]
    bindings = b
    val result = block
    bindings = before
    (result, b)
  }

  // TODO, when reifying bindings, insert let bindings and use RUN when statement is pure or IO!
  private[core] def reifyBindings(body: Stmt, bindings: ListBuffer[Binding]): Stmt = {
    bindings.foldRight(body) {
      // optimization: remove unnecessary binds
      case (Binding.Val(x, b), Return(ValueVar(y, _))) if x == y => b
      case (Binding.Val(x, b), body) => Val(x, b, body)
      case (Binding.Let(x, Run(s)), Return(ValueVar(y, _))) if x == y => s
      case (Binding.Let(x, b: Pure), Return(ValueVar(y, _))) if x == y => Return(b)
      case (Binding.Let(x, b), body) => Let(x, b, body)
      case (Binding.Def(x, b), body) => Def(x, b, body)
    }
  }
}
