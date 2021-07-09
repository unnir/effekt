package effekt

import effekt.symbols.scopes._
import effekt.source.{ Def, FunDef, ModuleDecl, ValDef, VarDef }
import effekt.context.Context
import effekt.regions.{ Region, RegionSet, RegionVar }
import org.bitbucket.inkytonik.kiama.util.Source
import effekt.substitutions._
import effekt.symbols.Name
import effekt.symbols.Name.Word

/**
 * The symbol table contains things that can be pointed to:
 * - function definitions
 * - type definitions
 * - effect definitions
 * - parameters
 * - value / variable binders
 * - ...
 */
package object symbols {

  // reflecting the two namespaces
  sealed trait TypeSymbol extends Symbol
  sealed trait TermSymbol extends Symbol

  // the two universes of values and blocks
  trait ValueSymbol extends TermSymbol
  trait BlockSymbol extends TermSymbol

  sealed trait Synthetic extends Symbol {
    override def synthetic = true
  }

  sealed trait ModuleSymbol extends BlockSymbol {
    /** import definitions into scope. */
    def load(scp: Scope = EmptyScope()): Scope
  }

  /** Module Base Class */
  sealed abstract class Module extends ModuleSymbol {
    type TypeMap = Map[Name.Word, TypeSymbol]
    type TermMap = Map[Name.Word, Set[TermSymbol]]

    var types: TypeMap = Map.empty
    var terms: TermMap = Map.empty

    /** declared effects */
    def effects: Effects = Effects(types.values.collect {
      case e: Effect => e
    })

    /** lookup submodule. */
    def mod(name: Name): Option[UserModule] = name match {
      case Name.Blk          => None
      case w: Name.Word      => trm(w).collect { case mod: UserModule => mod }.headOption
      case Name.Link(ln, rn) => mod(ln).flatMap { m => m.mod(rn) }
    }

    /** lookup type. */
    def typ(name: Name): Option[TypeSymbol] = name match {
      case Name.Blk          => None
      case w: Name.Word      => types.get(w)
      case Name.Link(ln, rn) => mod(ln).flatMap { m => m.typ(rn) }
    }

    /** lookup terms. */
    def trm(name: Name): Set[TermSymbol] = name match {
      case Name.Blk          => Set.empty
      case w: Name.Word      => terms.getOrElse(w, Set.empty)
      case Name.Link(ln, rn) => mod(ln).map { m => m.trm(rn) }.getOrElse { Set.empty }
    }

    /** export definitions from scope. */
    def save(scp: Scope): Unit = {
      types ++= scp.types.map { kv => (Name.Word(kv._1), kv._2) }
      terms ++= scp.terms.map { kv => (Name.Word(kv._1), kv._2) }
    }

    /** import definitions into scope. */
    override def load(scp: Scope = EmptyScope()): Scope = {
      val s = BlockScope(scp)
      val typ = types.map { kv => (kv._1.str, kv._2) }
      val trm = terms.map { kv => (kv._1.str, kv._2) }
      s.defineAll(trm, typ)
      return s
    }

    /** Find or create submodule with given name relative to this. */
    /*
    def bind(name: Name): UserModule = mod(name).getOrElse {
      name match {
        case w: Name.Word => {
          val m = new UserModule(this, w)
          mods = mods.updated(w, m)
          return m
        }
        case Name.Link(lft, rgt) => bind(lft).bind(rgt)
        case _                   => throw new IllegalArgumentException()
      }
    }*/
  }

  /** User-defined module. */
  class UserModule(val parent: Module, val short: Name.Word) extends Module {
    var impls: List[ModuleType] = List.empty
    def name = Name(parent.name, short)

    /** user name relative to its source module */
    def user: Name = parent match {
      case mod: UserModule => Name(mod.user, short)
      case _: SourceModule => short
    }

    def root: SourceModule = parent match {
      case mod: UserModule   => mod.root
      case src: SourceModule => src
    }

    override def load(scp: Scope = EmptyScope()) = super.load(parent.load(scp))
  }

  /**
   * The result of running the frontend on a module.
   * Symbols and types are stored globally in CompilerContext.
   */
  class SourceModule(val decl: ModuleDecl, val source: Source) extends Module {
    def name = path
    def path = decl.path

    var imports: List[SourceModule] = List.empty

    // a topological ordering of all transitive dependencies
    // this is the order in which the modules need to be compiled / loaded
    lazy val dependencies: List[SourceModule] = imports.flatMap { im => im.dependencies :+ im }.distinct

    /** lookup main function. */
    def main(): Option[TermSymbol] = terms.getOrElse(Name.main, Set.empty).headOption

    override def load(scp: Scope): Scope = {
      // Loads imports first so they can be shadowed
      super.load(imports.foldLeft(scp) { (s, i) => i.load(s) })
    }

    override def mod(name: Name): Option[UserModule] = super.mod(name).orElse { lookup { sm => sm.mod(name) } }
    override def typ(name: Name): Option[TypeSymbol] = super.typ(name).orElse { lookup { sm => sm.typ(name) } }
    override def trm(name: Name): Set[TermSymbol] = dependencies.reverseIterator.foldLeft(super.trm(name)) { (ts, sm) =>
      ts ++ sm.trm(name)
    }

    /** helper function to delegate to imports. */
    private def lookup[T](f: SourceModule => Option[T]): Option[T] = dependencies.reverseIterator.collectFirst { sm =>
      f(sm) match {
        case Some(t) => t
      }
    }

    // the transformed ast after frontend
    private var _ast = decl
    def ast = _ast

    /**
     * Should be called once after frontend
     */
    def setAst(ast: ModuleDecl): this.type = {
      _ast = ast
      this
    }

    /**
     * It is actually possible, that exports is invoked on a single module multiple times:
     * The dependencies of a module might change, which triggers frontend on the same module
     * again. It is the same, since the source and AST did not change.
     */
    def export(
      imports: List[SourceModule],
      terms: TermMap,
      types: TypeMap
    ): this.type = {
      this.imports = imports
      this.terms = terms
      this.types = types
      this
    }
  }

  sealed trait Param extends TermSymbol
  case class ValueParam(name: Name, tpe: Option[ValueType]) extends Param with ValueSymbol
  case class BlockParam(name: Name, tpe: BlockType) extends Param with BlockSymbol
  case class CapabilityParam(name: Name, tpe: CapabilityType) extends Param with Capability {
    def effect = tpe.eff
    override def toString = s"@${tpe.eff.name}"
  }
  case class ResumeParam(module: SourceModule) extends Param with BlockSymbol { val name = module.name.nest(Name("resume")) }
  case class ModuleParam(name: Name, tpe: ModuleType) extends Param with ModuleSymbol {
    def load(scp: Scope): Scope = {
      // Load ops from interface
      val s = BlockScope(scp)
      tpe.ops.foreach { op =>
        s.define(op.name.local, op)
      }
      return s
    }
  }

  /**
   * Right now, parameters are a union type of a list of value params and one block param.
   */
  // TODO Introduce ParamSection also on symbol level and then use Params for types
  type Params = List[List[Param]]

  def paramsToTypes(ps: Params): Sections =
    ps map {
      _ map {
        case ModuleParam(_, tpe)     => tpe
        case BlockParam(_, tpe)      => tpe
        case CapabilityParam(_, tpe) => tpe
        case v: ValueParam           => v.tpe.get
        case r: ResumeParam          => sys error "Internal Error: No type annotated on resumption parameter"
      }
    }

  trait Fun extends BlockSymbol {
    def tparams: List[TypeVar]
    def params: Params
    def ret: Option[Effectful]

    // invariant: only works if ret is defined!
    def toType: BlockType = BlockType(tparams, paramsToTypes(params), ret.get)
    def toType(ret: Effectful): BlockType = BlockType(tparams, paramsToTypes(params), ret)

    def effects(implicit C: Context): Effects =
      inferredReturnType.effects

    def inferredReturnType(implicit C: Context): Effectful =
      ret.orElse { C.blockTypeOption(this).map { _.ret } }.getOrElse {
        C.abort(s"Result type of recursive function ${name} needs to be annotated")
      }
  }

  object Fun {
    def unapply(f: Fun): Option[(Name, List[TypeVar], Params, Option[Effectful])] = Some((f.name, f.tparams, f.params, f.ret))
  }

  case class UserFunction(
    name: Name,
    tparams: List[TypeVar],
    params: Params,
    ret: Option[Effectful],
    decl: FunDef
  ) extends Fun

  /**
   * Anonymous symbols used to represent scopes / regions in the region checker
   */
  sealed trait Anon extends TermSymbol {
    val name = Name("<anon>")
    def decl: source.Tree
  }

  case class BlockArg(decl: source.Tree) extends Anon

  case class Lambda(params: Params, decl: source.Tree) extends Fun with Anon {
    // Lambdas currently do not have an annotated return type
    def ret = None

    // Lambdas currently do not take type parameters
    def tparams = Nil
  }

  /**
   * Binders represent local value and variable binders
   *
   * They also store a reference to the original defition in the source code
   */
  sealed trait Binder extends ValueSymbol {
    def tpe: Option[ValueType]
    def decl: Def
  }
  case class ValBinder(name: Name, tpe: Option[ValueType], decl: ValDef) extends Binder
  case class VarBinder(name: Name, tpe: Option[ValueType], decl: VarDef) extends Binder

  /**
   * Synthetic symbol representing potentially multiple call targets
   *
   * Refined by typer.
   */
  case class CallTarget(name: Name, symbols: List[Set[TermSymbol]]) extends Synthetic with BlockSymbol

  /**
   * Introduced by Transformer
   */
  case class Wildcard(module: SourceModule) extends ValueSymbol { val name = module.name.nest(Name("_")) }
  case class Tmp(module: SourceModule) extends ValueSymbol { val name = module.name.nest(Name("tmp" + Symbol.fresh.next())) }

  /**
   * A symbol that represents a termlevel capability
   */
  trait Capability extends BlockSymbol {
    def effect: Effect
  }

  /**
   * Types
   */
  sealed trait Type

  /**
   * like Params but without name binders
   */
  type Sections = List[List[Type]]

  sealed trait ValueType extends Type {
    def /(effs: Effects): Effectful = Effectful(this, effs)
    def dealias: ValueType = this
  }

  /**
   * Types of first-class functions
   */
  case class FunType(tpe: BlockType, region: Region) extends ValueType {
    override def toString: String = {

      val BlockType(_, params, Effectful(ret, effs)) = tpe
      // copy and paste from BlockType.toString
      val ps = params.map {
        case List(b: BlockType)             => s"{${b.toString}}"
        case ps: List[ValueType @unchecked] => s"(${ps.map { _.toString }.mkString(", ")})"
      }.mkString("")

      val effects = effs.toList
      val regs = region match {
        case RegionSet(r) => r.regions.toList
        // to not confuse users, we render uninstantiated region variables as ?
        case e            => List("?")
      }
      val both: List[String] = (effects ++ regs).map { _.toString }

      val tpeString = if (both.isEmpty) ret.toString else s"$ret / { ${both.mkString(", ")} }"

      s"$ps ⟹ $tpeString"
    }
  }

  class TypeVar(val name: Name) extends ValueType with TypeSymbol
  object TypeVar {
    def apply(name: Name): TypeVar = new TypeVar(name)
  }

  /**
   * Introduced when instantiating type schemes
   *
   * Should neither occur in source programs, nor in infered types
   */
  case class RigidVar(underlying: TypeVar) extends TypeVar(underlying.name) {
    override def toString = "?" + underlying.name + id
  }

  case class TypeApp(tpe: ValueType, args: List[ValueType]) extends ValueType {
    override def toString = s"${tpe}[${args.map { _.toString }.mkString(", ")}]"

    override def dealias: ValueType = tpe match {
      case TypeAlias(name, tparams, tpe) =>
        (tparams zip args).toMap.substitute(tpe).dealias
      case other => TypeApp(other.dealias, args.map { _.dealias })
    }
  }

  sealed trait InterfaceType extends Type

  case class ModuleType(name: Name, var ops: List[Method] = Nil) extends InterfaceType with TypeSymbol with MethodOwner

  case class CapabilityType(eff: Effect) extends InterfaceType

  case class BlockType(tparams: List[TypeVar], params: Sections, ret: Effectful) extends InterfaceType {
    override def toString: String = {
      val ps = params.map {
        case List(b: BlockType)             => s"{${b.toString}}"
        case ps: List[ValueType @unchecked] => s"(${ps.map { _.toString }.mkString(", ")})"
      }.mkString("")

      tparams match {
        case Nil => s"$ps ⟹ $ret"
        case tps => s"[${tps.map { _.toString }.mkString(", ")}] $ps ⟹ $ret"
      }
    }
  }

  case class TypeAlias(name: Name, tparams: List[TypeVar], tpe: ValueType) extends ValueType with TypeSymbol {
    override def dealias: ValueType =
      if (tparams.isEmpty) { tpe } else { sys error "Cannot delias unapplied type constructor" }
  }

  /**
   * Types that _can_ be used in type constructor position. e.g. >>>List<<<[T]
   */
  sealed trait TypeConstructor extends TypeSymbol with ValueType
  object TypeConstructor {
    def unapply(t: ValueType): Option[TypeConstructor] = t match {
      case t: TypeVar         => None
      case t: TypeAlias       => unapply(t.dealias)
      case t: TypeConstructor => Some(t)
      case TypeApp(tpe, args) => unapply(tpe)
      case t: BuiltinType     => None
      case t: FunType         => None
    }
  }

  case class DataType(name: Name, tparams: List[TypeVar], var variants: List[Record] = Nil) extends TypeConstructor

  /**
   * Structures are also function symbols to represent the constructor
   */
  case class Record(name: Name, tparams: List[TypeVar], var tpe: ValueType, var fields: List[Field] = Nil) extends TypeConstructor with Fun with Synthetic {
    // Parameter and return type of the constructor:
    lazy val params = List(fields.map { f => f.param })
    def ret = Some(Effectful(tpe, Pure))
  }

  /**
   * The record symbols is _both_ a type (record type) _and_ a term symbol (constructor).
   *
   * param: The underlying constructor parameter
   */
  case class Field(name: Name, param: ValueParam, rec: Record) extends Fun with Synthetic {
    val tparams = rec.tparams
    val tpe = param.tpe.get
    val params = List(List(ValueParam(rec.name, Some(if (rec.tparams.isEmpty) rec else TypeApp(rec, rec.tparams)))))
    val ret = Some(Effectful(tpe, Pure))
  }

  /** Effects */

  // TODO effects are only temporarily symbols to be resolved by namer
  sealed trait Effect {
    def name: Name
    def builtin: Boolean
    // invariant: no EffectAlias in this list
    def dealias: List[Effect] = List(this)
  }

  case class EffectApp(effect: Effect, args: List[ValueType]) extends Effect {
    override def toString = s"${effect}[${args.map { _.toString }.mkString(", ")}]"
    override def builtin = effect.builtin
    override val name = effect.name

    // override def dealias: List[Effect] = ??? // like dealiasing of TypeApp we potentially need to substitute

  }

  case class EffectAlias(name: Name, tparams: List[TypeVar], effs: Effects) extends Effect with TypeSymbol {
    override def dealias: List[Effect] = effs.dealias
  }

  /** Either a UserEffect or ModuleType. */
  sealed trait MethodOwner
  case class UserEffect(name: Name, tparams: List[TypeVar], var ops: List[Method] = Nil) extends Effect with TypeSymbol with MethodOwner
  case class Method(name: Name, tparams: List[TypeVar], params: List[List[ValueParam]], annotatedReturn: Effectful, owner: MethodOwner) extends Fun {
    def ret: Option[Effectful] = owner match {
      case _: ModuleType => Some(Effectful(annotatedReturn.tpe, otherEffects))
      case _: UserEffect => Some(Effectful(annotatedReturn.tpe, otherEffects + appliedEffect))
    }
    def appliedEffect = if (effect.tparams.isEmpty) effect else EffectApp(effect, effect.tparams)

    def effect: UserEffect = owner match {
      case e: UserEffect => e
      case t: ModuleType => sys error s"Method $name is owned by $t (not a UserEffect)."
    }

    // The effects as seen by the capability passing transformation
    def otherEffects: Effects = annotatedReturn.effects
    def isBidirectional: Boolean = otherEffects.nonEmpty
  }

  /**
   * symbols.Effects is like source.Effects, but with resolved effects
   *
   * Effect sets and effectful computations are themselves *not* symbols, they are just aggregates
   *
   * `effects` is dealiased by the smart constructors
   */
  class Effects private[symbols] (effects: List[Effect]) {

    lazy val toList: List[Effect] = effects.distinct

    // This is only used by typer
    def +(eff: Effect): Effects = this ++ Effects(eff)

    def ++(other: Effects): Effects = Effects((other.toList ++ this.toList).distinct)
    def --(other: Effects): Effects = Effects(this.toList.filterNot(other.contains))

    def isEmpty: Boolean = effects.isEmpty
    def nonEmpty: Boolean = effects.nonEmpty

    override def equals(other: Any): Boolean = other match {
      case other: Effects => this.contains(other.toList) && other.contains(this.toList)
      case _              => false
    }

    def contains(e: Effect): Boolean = contains(e.dealias)
    def contains(other: List[Effect]): Boolean = other.toList.forall {
      e => this.toList.flatMap(_.dealias).contains(e)
    }

    def filterNot(p: Effect => Boolean): Effects =
      Effects(effects.filterNot(p))

    def userDefined: Effects =
      filterNot(_.builtin)

    def userEffects: List[Effect] =
      effects collect {
        case u: UserEffect => u
        // we assume that only UserEffects can be applied to type arguments for now
        case u: EffectApp  => u
      }

    def dealias: List[Effect] = effects.flatMap { _.dealias }

    override def toString: String = toList match {
      case Nil        => "{}"
      case eff :: Nil => eff.toString
      case effs       => s"{ ${effs.mkString(", ")} }"
    }
  }
  object Effects {

    def apply(effs: Effect*): Effects =
      new Effects(effs.flatMap(_.dealias).toList)

    def apply(effs: Iterable[Effect]): Effects =
      new Effects(effs.flatMap(_.dealias).toList)
  }

  lazy val Pure = new Effects(Nil)

  case class Effectful(tpe: ValueType, effects: Effects) {
    override def toString = if (effects.isEmpty) tpe.toString else s"$tpe / $effects"
  }

  object / {
    def unapply(e: Effectful): Option[(ValueType, Effects)] = Some(e.tpe, e.effects)
  }

  /**
   * Builtins
   */
  sealed trait Builtin extends Symbol {
    override def builtin = true
  }

  case class BuiltinFunction(name: Name, tparams: List[TypeVar], params: Params, ret: Option[Effectful], pure: Boolean = true, body: String = "") extends Fun with BlockSymbol with Builtin
  case class BuiltinType(name: Name, tparams: List[TypeVar]) extends ValueType with TypeSymbol with Builtin
  case class BuiltinEffect(name: Name, tparams: List[TypeVar] = Nil) extends Effect with TypeSymbol with Builtin

  def isBuiltin(e: Symbol): Boolean = e.builtin
}
