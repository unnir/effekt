package effekt
package context

import effekt.util.messages.ErrorReporter
import kiama.util.{ Memoiser, Source }

case class Annotation[K, V](name: String, description: String) {
  type Value = V
  override def toString = name
}

/**
 * "local" annotations that can be backtracked
 *
 * Local annotations can be backed-up and restored to allow backtracking
 * (mostly for typer and overload resolution).
 *
 * Local annotations can be "comitted" to become global ones in the DB,
 * that are assumed to not change anymore.
 */
class Annotations private (private var annotations: Annotations.DB) {
  import Annotations._

  def copy: Annotations = new Annotations(annotations)

  private def annotationsAt(key: Any): Map[Annotation[_, _], Any] =
    annotations.getOrElse(new Key(key), Map.empty)

  private def updateAnnotations(key: Any, annos: Map[Annotation[_, _], Any]): Unit =
    annotations = annotations.updated(new Key(key), annos)

  def annotate[K, V](ann: Annotation[K, V], key: K, value: V): Unit = {
    val anns = annotationsAt(key)
    updateAnnotations(key, anns + (ann -> value))
  }

  def annotationOption[K, V](ann: Annotation[K, V], key: K): Option[V] =
    annotationsAt(key).get(ann).asInstanceOf[Option[V]]

  def annotation[K, V](ann: Annotation[K, V], key: K)(implicit C: ErrorReporter): V =
    annotationOption(ann, key).getOrElse { C.abort(s"Cannot find ${ann.name} '${key}'") }

  def commit()(implicit global: AnnotationsDB): Unit =
    annotations.foreach {
      case (k, annos) =>
        global.annotate(k.key, annos)
    }

  override def toString = s"Annotations(${annotations})"
}
object Annotations {

  def empty: Annotations = new Annotations(Map.empty)

  private type DB = Map[Annotations.Key[Any], Map[Annotation[_, _], Any]]

  private class Key[T](val key: T) {
    override val hashCode = System.identityHashCode(key)
    override def equals(o: Any) = o match {
      case k: Key[_] => hashCode == k.hashCode
      case _         => false
    }
  }

  /**
   * The type as inferred by typer at a given position in the tree
   *
   * Important for finding the types of temporary variables introduced by transformation
   * Can also be used by LSP server to display type information for type-checked trees
   */
  val InferredValueType = Annotation[source.Tree, symbols.ValueType](
    "ValueType",
    "the inferred value type of"
  )

  val InferredBlockType = Annotation[source.Tree, symbols.BlockType](
    "BlockType",
    "the inferred block type of"
  )

  val InferredCapture = Annotation[source.Tree, symbols.CaptureSet](
    "CaptureSet",
    "the inferred capture of"
  )

  /**
   * Used by LSP to list all captures
   */
  val CaptureForFile = Annotation[symbols.Module, List[(source.Tree, symbols.CaptureSet)]](
    "CaptureSet",
    "all inferred captures for file"
  )

  /**
   * Type arguments of a _function call_ as inferred by typer
   */
  val TypeArguments = Annotation[source.Call, List[symbols.ValueType]](
    "TypeArguments",
    "the inferred or annotated type arguments of"
  )

  /**
   * Value type of symbols like value binders or value parameters
   */
  val ValueType = Annotation[symbols.ValueSymbol, symbols.ValueType](
    "ValueType",
    "the type of value symbol"
  )

  /**
   * Block type of symbols like function definitions, block parameters, or continuations
   */
  val BlockType = Annotation[symbols.BlockSymbol, symbols.BlockType](
    "BlockType",
    "the type of block symbol"
  )

  /**
   * Block symbols are annotated with their capture set
   *
   * Block symbols without annotation are assumed to be tracked
   */
  val CaptureSet = Annotation[symbols.BlockSymbol, symbols.CaptureSet](
    "CaptureSet",
    "the capture set of block symbol"
  )

  /**
   * The module a given symbol is defined in
   */
  val SourceModule = Annotation[symbols.Symbol, symbols.Module](
    "SourceModule",
    "the source module of symbol"
  )

  /**
   * The definition tree of a symbol in source
   *
   * Annotated by namer and used by the LSP server for jump-to-definition
   *
   * TODO maybe store the whole definition tree instead of the name, which requries refactoring of assignSymbol
   */
  val DefinitionTree = Annotation[symbols.Symbol, source.IdDef](
    "DefinitionTree",
    "the tree identifying the definition site of symbol"
  )

  /**
   * Approximate list of all references pointing to a symbol
   *
   * Filled by namer and used for reverse lookup in LSP server
   */
  val References = Annotation[symbols.Symbol, List[source.Reference]](
    "References",
    "the references referring to symbol"
  )

  /**
   * The symbol for an identifier as resolved by namer
   *
   * Id can be the definition-site (IdDef) or use-site (IdRef) of the
   * specific symbol
   */
  val Symbol = Annotation[source.Id, symbols.Symbol](
    "Symbol",
    "the symbol for identifier"
  )

  /**
   * The resolved type for a type tree that appears in the source program
   *
   * Resolved and annotated by namer and used by typer.
   */
  val Type = Annotation[source.Type, symbols.Type](
    "Type",
    "the resolved type for"
  )

  val Capture = Annotation[source.CaptureSet, symbols.CaptureSet](
    "Capture",
    "the resolved capture set for"
  )
}

/**
 * A global annotations database
 *
 * This database is mixed into the compiler `Context` and is
 * globally visible across all phases. If you want to hide changes in
 * subsequent phases, consider using an instance of `Annotions`, instead.
 *
 * Calling `Annotations.commit` transfers all annotations into this global DB.
 *
 * The DB is also "global" in the sense, that modifications cannot be backtracked.
 * It should thus only be used to store a "ground" truth that will not be changed again.
 */
trait AnnotationsDB { self: Context =>

  private type Annotations = Map[Annotation[_, _], Any]
  private val annotations: Memoiser[Any, Annotations] = Memoiser.makeIdMemoiser()
  private def annotationsAt(key: Any): Annotations = annotations.getOrDefault(key, Map.empty)

  /**
   * Copies annotations, keeping existing annotations at `to`
   */
  def copyAnnotations(from: Any, to: Any): Unit = {
    val existing = annotationsAt(to)
    val source = annotationsAt(from)
    annotate(to, source ++ existing)
  }

  /**
   * Bulk annotating the key
   *
   * Used by Annotations.commit to commit all temporary annotations to the DB
   */
  def annotate[K, V](key: K, value: Map[Annotation[_, _], Any]): Unit = {
    val anns = annotationsAt(key)
    annotations.put(key, anns ++ value)
  }

  def annotate[K, V](ann: Annotation[K, V], key: K, value: V): Unit = {
    val anns = annotationsAt(key)
    annotations.put(key, anns + (ann -> value))
  }

  def annotationOption[K, V](ann: Annotation[K, V], key: K): Option[V] =
    annotationsAt(key).get(ann).asInstanceOf[Option[V]]

  def annotation[K, V](ann: Annotation[K, V], key: K): V =
    annotationOption(ann, key).getOrElse { panic(s"Cannot find ${ann.description} for '${key}'") }

  def hasAnnotation[K, V](ann: Annotation[K, V], key: K): Boolean =
    annotationsAt(key).isDefinedAt(ann)

  // Customized Accessors
  // ====================
  import symbols.{ Symbol, Type, ValueType, FunctionType, BlockType, ValueSymbol, BlockSymbol, Module, CaptureSet }

  // Types
  // -----

  def typeArguments(c: source.Call): List[symbols.ValueType] =
    annotation(Annotations.TypeArguments, c)

  def inferredTypeOption(t: source.Tree): Option[ValueType] =
    annotationOption(Annotations.InferredValueType, t)

  def inferredTypeOf(t: source.Tree): ValueType =
    inferredTypeOption(t).getOrElse {
      panic(s"Internal Error: Missing type of source expression: '${t}'")
    }

  def inferredCaptureOption(t: source.Tree): Option[CaptureSet] =
    annotationOption(Annotations.InferredCapture, t)

  def inferredCaptureOf(t: source.Tree): CaptureSet =
    inferredCaptureOption(t).getOrElse {
      panic(s"Internal Error: Missing capture of source expression: '${t}'")
    }

  // TODO maybe move to TyperOps
  def assignType(s: Symbol, tpe: BlockType): Unit = s match {
    case b: BlockSymbol => annotate(Annotations.BlockType, b, tpe)
    case _              => panic(s"Trying to store a block type for non block '${s}'")
  }

  def assignType(s: Symbol, tpe: ValueType): Unit = s match {
    case b: ValueSymbol => annotate(Annotations.ValueType, b, tpe)
    case _              => panic(s"Trying to store a value type for non value '${s}'")
  }

  def assignCaptureSet(s: Symbol, capt: CaptureSet): Unit = s match {
    case b: BlockSymbol => annotate(Annotations.CaptureSet, b, capt)
    case _              => panic(s"Trying to store a capture set for non block '${s}'")
  }

  def captureOf(s: Symbol): CaptureSet = captureOption(s) getOrElse panic(s"Cannot find capture set for '${s}'")

  def captureOption(s: Symbol): Option[CaptureSet] = s match {
    case b: BlockSymbol => annotationOption(Annotations.CaptureSet, b)
    case _              => panic(s"Trying to lookup a capture set for non block '${s}'")
  }

  def annotateResolvedType(tree: source.Type)(tpe: tree.resolved): Unit =
    annotate(Annotations.Type, tree, tpe)

  def resolvedType(tree: source.Type): tree.resolved =
    annotation(Annotations.Type, tree).asInstanceOf[tree.resolved]

  def annotateResolvedCapture(tree: source.CaptureSet)(capt: tree.resolved): Unit =
    annotate(Annotations.Capture, tree, capt)

  def resolvedCapture(tree: source.CaptureSet): tree.resolved =
    annotation(Annotations.Capture, tree)

  def typeOf(s: Symbol): Type = s match {
    case s: ValueSymbol => valueTypeOf(s)
    case s: BlockSymbol => interfaceTypeOf(s)
    case _              => panic(s"Cannot find a type for symbol '${s}'")
  }

  def functionTypeOf(s: Symbol): FunctionType =
    functionTypeOption(s) getOrElse { panic(s"Cannot find function type for '${s}'") }

  def functionTypeOption(s: Symbol): Option[FunctionType] =
    blockTypeOption(s) flatMap {
      case f: FunctionType => Some(f)
      case _               => None
    }

  def blockTypeOf(s: Symbol): BlockType =
    blockTypeOption(s) getOrElse { panic(s"Cannot find type for block '${s}'") }

  def blockTypeOption(s: Symbol): Option[BlockType] =
    s match {
      case b: BlockSymbol => annotationOption(Annotations.BlockType, b) flatMap {
        case b: BlockType => Some(b)
        case _            => None
      }
      case v: ValueSymbol => valueTypeOption(v).flatMap {
        case symbols.BoxedType(tpe: BlockType, capt) => Some(tpe)
        case _ => None
      }
    }

  def interfaceTypeOf(s: Symbol): BlockType =
    interfaceTypeOption(s) getOrElse { panic(s"Cannot find interface type for block '${s}'") }

  def interfaceTypeOption(s: Symbol): Option[BlockType] =
    s match {
      case b: BlockSymbol => annotationOption(Annotations.BlockType, b) flatMap {
        case b: BlockType => Some(b)
        case _            => None
      }
      case _ => panic(s"Trying to find a interface type for non block '${s}'")
    }

  def valueTypeOf(s: Symbol): ValueType =
    valueTypeOption(s) getOrElse { panic(s"Cannot find value binder for ${s}") }

  def valueTypeOption(s: Symbol): Option[ValueType] = s match {
    case s: ValueSymbol => annotationOption(Annotations.ValueType, s)
    case _              => panic(s"Trying to find a value type for non-value '${s}'")
  }

  // Symbols
  // -------

  /**
   * Stores symbol `sym` as the corresponding symbol for `id`
   *
   * Almost all calls to this method are performed by Namer, which
   * resolves identifier and then assigns the symbols.
   *
   * Typer also calls this method to resolve overloads and store
   * the result of overload resolution.
   */
  def assignSymbol(id: source.Id, sym: Symbol): Unit = id match {
    case id: source.IdDef =>
      annotate(Annotations.DefinitionTree, sym, id)
      annotate(Annotations.Symbol, id, sym)
      annotate(Annotations.SourceModule, sym, module)
    case _ =>
      annotate(Annotations.Symbol, id, sym)
      annotate(Annotations.SourceModule, sym, module)
  }

  def symbolOf(id: source.Id): Symbol = symbolOption(id) getOrElse {
    panic(s"Internal Compiler Error: Cannot find symbol for ${id}")
  }
  def symbolOption(id: source.Id): Option[Symbol] =
    annotationOption(Annotations.Symbol, id)

  def sourceModuleOf(sym: Symbol): Module =
    annotation(Annotations.SourceModule, sym)

  /**
   * Searching the defitions for a Reference
   *
   * This one can fail.
   */
  def symbolOf(tree: source.Reference): tree.symbol = {
    val sym = symbolOf(tree.id).asInstanceOf[tree.symbol]

    val refs = annotationOption(Annotations.References, sym).getOrElse(Nil)
    annotate(Annotations.References, sym, tree :: refs)
    sym
  }

  /**
   * Searching the symbol for a definition
   *
   * These lookups should not fail (except there is a bug in the compiler)
   */
  def symbolOf(tree: source.Definition): tree.symbol =
    symbolOf(tree.id).asInstanceOf[tree.symbol]

  /**
   * Searching the definition for a symbol
   */
  def definitionTreeOption(s: Symbol): Option[source.IdDef] =
    annotationOption(Annotations.DefinitionTree, s)

  /**
   * List all symbols that have a source module
   *
   * Used by the LSP server to generate outline
   */
  def sourceSymbols: Vector[Symbol] =
    annotations.keys.collect {
      case s: Symbol if hasAnnotation(Annotations.SourceModule, s) => s
    }

  // TODO running frontend is NOT the job of Annotations and should be moved to intelligence
  def allCaptures(s: Source) =
    tryModuleOf(s).flatMap { mod => annotationOption(Annotations.CaptureForFile, mod) }.getOrElse(Nil)

  /**
   * List all references for a symbol
   *
   * Used by the LSP server for reverse lookup
   */
  def distinctReferencesTo(sym: Symbol): List[source.Reference] =
    annotationOption(Annotations.References, sym)
      .getOrElse(Nil)
      .distinctBy(r => System.identityHashCode(r))

}
