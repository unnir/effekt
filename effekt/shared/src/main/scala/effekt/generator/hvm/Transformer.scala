package effekt
package generator
package hvm

import effekt.core.Id

import scala.collection.mutable.{Map => MutableMap}
import effekt.util.intercalate
import effekt.core.CoreParsers.definition





//cps to hvm
def transform(mod: cps.ModuleDecl): Book = {
  val decls = mod.decls.flatMap(transform)
  val externs = mod.externs.map(transform)
  val defns = mod.definitions.map(transform)
  val defMap: MutableMap[Name, Definition] = MutableMap()
  decls.foreach({case Definition(name, rules, builtin) => defMap += (Name(name) -> Definition(name, rules, builtin))})
  defns.foreach({case Definition(name, rules, builtin) => defMap += (Name(name) -> Definition(name, rules, builtin))})
  Book(defMap, externs, MutableMap(), MutableMap(), Some(Name(mod.path)))
}


def transform(decl: cps.Declaration): List[Definition] = decl match {
  case cps.Declaration.Data(id, ctors) => List(Definition(id.name.name, transformConstructors(List(id), ctors), false))
  case cps.Declaration.Interface(id, operations) => List(Definition(id.name.name, transformProperties(List(id), operations), false))
}

def transform(decl: cps.Extern): Verbatim = decl match {
  case cps.Extern.Def(id, params, body) =>
    Verbatim.Def(id.name.name, params map idToPattern, transform(body))//let tmp für jedes body.args
  case cps.Extern.Include(contents) => Verbatim.Include(contents)
}

def transform(template: Template[cps.Expr]): String = 
  //intercalate(template.strings, template.args).mkString // keine map
  intercalate(template.strings, template.args map exprToString).mkString // keine map

def transform(definition: cps.Definition): Definition = definition match {
  //params + body => rule(patterns, body)
  case cps.Definition.Function(name, params, cont, body) => 
    Definition(name.name.name, List(Rule((params map idToPattern) :+ VarPattern(Some(cont.name.name)), transform(body))), false)
  case cps.Definition.Let(id, expr) => Definition(id.name.name, List(Rule(List(VarPattern(Some(id.name.name))), transform(expr))), false)
}
 

def transform(term: cps.Term): Term = term match {
  case cps.Term.AppCont(id, arg) => App(Auto, idToVar(id), transform(arg))
  case cps.Term.App(id, args, cont) => chainApp(idToVar(cont)::(args map transform))
  case cps.Term.Scope(definitions, body) => transform(definitions, body)
  case cps.Term.If(cond, thn, els) => 
    Swt(List(transform(cond)), List(Rule(List(NumPattern(NumCtr.Num(0))), transform(els)), Rule(List(VarPattern(Some("_"))), transform(thn))))
  case cps.Term.Match(scrutinee, clauses, None) => Mat(List(transform(scrutinee)), clauses map ((_, blockLit) => transform(blockLit)))
  case cps.Term.Match(scrutinee, clauses, Some(default)) =>Mat(List(transform(scrutinee)), (clauses map ((_, blockLit) => transform(blockLit))) :+ Rule(List(VarPattern(Some("_"))), transform(default)))
  case cps.Term.Let(name, expr, rest) => Let(idToPattern(name), transform(expr), transform(rest))
  case cps.Term.LetCont(name, param, body, rest) => Let(idToPattern(name), Lam(Auto, Some(name.name.name), transform(body)), transform(rest))
  case cps.Term.Val(id, binding, body) => println(term); ??? 
  case cps.Term.Fun(name, params, cont, body) => println(term); ???
}

def transform(blockLit: cps.BlockLit): Rule = blockLit match {
  case cps.Expr.BlockLit(params, body) => Rule(params map idToPattern, transform(body))
}

def transform(definitions: List[cps.Definition], body: cps.Term): Term = definitions match {
  case Nil => transform(body)
  case _ => definitions.head match {
    case cps.Definition.Let(id, bindings) => Let(idToPattern(id), transform(bindings), transform(definitions.tail, body))
    case cps.Definition.Function(name, params, cont, body) => ???
  }
}

def transform(expr: cps.Expr): Term = expr match {
  case cps.Expr.Lit(n) => Num(n)
  case cps.Expr.Var(name) => idToVar(name)
  case cps.Expr.PureApp(b, args) => chainApp(transform(b) :: (args map transform))
  case cps.Expr.Box(b) => transform(b)
  case cps.Expr.Run(t) => transform(t)
  case cps.Expr.BlockLit(params, body) => transform(cps.Expr.BlockLit(params, body))
  case _ => ???
}

def transformConstructors(tparams: List[cps.Id], constructors: List[cps.Constructor]): List[Rule] = (tparams, constructors) match {
  case (List(), constructors) => Rule(List(), Var(constructors.head.id.name.name)) :: transformConstructors(List(), constructors.tail)
  case (tparams, List()) => Rule(List(VarPattern(Some(tparams.head.name.name))), Var(" ")) :: transformConstructors(tparams, List())
  case (tparams, constructors) => Rule(List(VarPattern(Some(tparams.head.name.name))), Var(constructors.head.id.name.name)) :: transformConstructors(tparams.tail, constructors.tail)
}

def transformProperties(tparams: List[cps.Id], properties: List[cps.Id]): List[Rule] = (tparams, properties) match {
  case (List(), properties) => Rule(List(), Var(properties.head.name.name)) :: transformProperties(List(), properties.tail)
  case (tparams, List()) => Rule(List(VarPattern(Some(tparams.head.name.name))), Var(" ")) :: transformProperties(tparams, List())
  case (tparams, properties) => Rule(List(VarPattern(Some(tparams.head.name.name))), Var(properties.head.name.name)) :: transformProperties(tparams.tail, properties.tail)
}

//helper functions:
def chainApp(args: List[Term]): Term = {
  val reverseArgs = args.reverse
  chainAppHelper(reverseArgs)
}

def chainAppHelper(args: List[Term]): Term = args match {
 case Nil => Err
 case head :: Nil => head
 case head:: tail => App(Auto, chainAppHelper(tail), head)
}

def exprToString(expr: cps.Expr): String = expr match {
    case cps.Var(name) => name.name.name
    case cps.Expr.Lit(n) => n.toString()
    case _ => ???
}

def idToPattern(id: Id): Pattern = VarPattern(Some(id.name.name))

def idToVar(id: Id): Var = Var(id.name.name)