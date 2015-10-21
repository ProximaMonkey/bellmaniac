package synth.tactics

import java.io.FileWriter

import semantics.TranslationError
import semantics.TypeTranslation.TypingSugar
import semantics.pattern.SimplePattern

import scala.collection.immutable.HashSet

import syntax.AstSugar._
import syntax.{Strip, Identifier}
import syntax.Piping._
import syntax.transform.Mnemonics
import semantics._
import semantics.transform.{Escalate, Explicate}
import synth.proof.Assistant



object Synth {

  def main(args: Array[String]) {

    implicit val env = examples.Paren.env
    implicit val scope = env.scope

    val (ψ, θ, i, j, k) = ($TV("ψ"), $TV("θ"), $TV("i"), $TV("j"), TV("k"))

    import semantics.Prelude.{R, min, ?, fix}
    import examples.Paren.{J, J0, J1, K1, K2}
    import examples.Paren.BreakDown.{APod, BPod}
    import TypedLambdaCalculus.pullOut

    val a = new Assistant()

    println("-" * 50)

    {
      val A = APod(J).program
      val f = (A :-/ "f") |> a.compile

      val AP = APod(J ∩ TV("P")).program
      val fP = (AP :-/ "f") |> a.compile

      val X = J0 x J0

      synthesize(f, fP, X -> R)
    }

    val * = TI("*")

    {
      val B = BPod(J0, J1).program
      val f = pullOut(B, (SimplePattern(fix(* :- ?)) find B).head(*)).get |> a.compile

      val BP = BPod(J ∩ TV("P1"), J ∩ TV("P2")).program
      val fP = pullOut(BP, (SimplePattern(fix(* :- ?)) find BP).head(*)).get |> a.compile

      val X = K1 x K2

      synthesize(f, fP, X -> R)
    }
  }

  def synthesize(f: Term, fP: Term, quadrant: Term)(implicit scope: Scope) {
    val escalate = new Escalate
    val explicate = new Explicate
    val codegen = new CodeGen

    val ir0 = f |> escalate.apply |> explicate.apply |> TypedTerm.raw |> TypedLambdaCalculus.simplify

    println(ir0 toPretty)

    val code0 = codegen(ir0, "h")

    println(code0 toPretty)

    val ir1 = fP |> escalate.apply |> explicate.apply |> TypedTerm.raw |> TypedLambdaCalculus.simplify

    println(ir1 toPretty)

    val code1 = codegen(ir1, "f_i")

    println(code1 toPretty)

    val sketch = new SketchOutput

    val outf = new FileWriter("synth-autogened.sk")
    def fprintln(s: String) = outf.write(s + "\n");

    fprintln("#include \"scalar.sk\"\n#include \"scope.sk\"\n\n")
    fprintln(sketch(code0))
    fprintln(sketch(code1))

    import Prelude._
    val builtin = Set(min, cons, nil, TV("+"))
    val decl = codegen.decl(LambdaCalculus.uncurry(ir0)._1 ++ (LambdaCalculus.freevars(ir0) -- builtin) map TypedTerm.raw)

    val codeX = codegen.pred(quadrant, "X")//, sized=false)
    println(codeX toPretty)
    fprintln("\n" + sketch(codeX) + "\n")

    fprintln("\n/* -- harness -- */\n")
    fprintln(sketch.harness(decl))

    outf.close()
  }


  class CodeGen(implicit scope: Scope) {

    import TypedTerm.{preserve, typeOf_!}
    import LambdaCalculus.{isApp, isAbs}
    import TypePrimitives.{args=>targs,ret=>tret,shape}
    import semantics.TypeTranslation.TypingSugar.qvars
    import TraceableException.trace
    import synth.pods.ConsPod.`⟨ ⟩?`
    import CodeGen._

    case class Context(vars: List[Term], innerFuncs: collection.mutable.Map[Identifier, (List[Term], Term)])

    object Context {
      def empty = Context(List(), collection.mutable.Map.empty)
    }

    def apply(t: Term, sized: Boolean=true) = {
      implicit val ctx = Context.empty
      trace(t) { toplevel(expr(t), sized) }
    }

    def apply(t: Term, name: String): Term = apply(t, name, true)
    def apply(t: Term, name: String, sized: Boolean): Term = rename(apply(t, sized), name)

    def expr(t: Term)(implicit ctx: Context): Term = {
      if (t.isLeaf) {
        if (!typeOf_!(t).isLeaf || (ctx.vars contains t)) t else TypedTerm(t, scalar)
      }
      else if (t =~ ("|!", 2)) {
        val v = expr(t.subtrees(0))
        TypedTerm((if (typeOf_!(v) == scalar) only else when)(t.subtrees(1), v), scalar)
      }
      else if (t =~ (":", 2)) expr(t.subtrees(1))
      else if (t =~ ("/", 2)) {
        assert(typeOf_!(t).isLeaf)
        preserve(t, slash(t.subtrees map expr))
      }
      else isApp(t) match {
        case Some((f, args)) =>
          if (!typeOf_!(t).isLeaf) throw new TranslationError(s"high-order return value for '${f}'") at t
          val ff = expr(f)
          if (args exists (`⟨ ⟩?`(_).isDefined)) reduction(ff, args)
          else app(ff, args)
        case _ =>
          isAbs(t) match {
            case Some((vars, body)) => preserve(t, abs(vars, body))
            case _ =>
              if (t.root.kind == "set" || t.root == "&") t
              else
                throw new TranslationError(s"don't quite know what to do with '${t.root}'") at t
          }
      }
    }

    def app(f: Term, args: List[Term])(implicit ctx: Context) = {
      val vals = args map expr
      if (vals forall (x => typeOf_!(x) != scalar))
        TypedTerm(f(vals), scalar)
      else
        TypedTerm(@:(f)(vals), scalar)
    }

    def abs(vars: List[Term], body: Term)(implicit ctx: Context) = {
      val subctx = Context(ctx.vars ++ vars, collection.mutable.Map.empty)
      val defn = expr(body)(subctx)
      val retType = typeOf_!(body)
      val f = TypedIdentifier($I("f", "function"), if (retType.isLeaf && retType != Prelude.B) scalar else retType)
      ctx.innerFuncs += (f -> (vars, emit(defn)(subctx)))
      T(f)
    }

    def reduction(f: Term, args: List[Term])(implicit ctx: Context) = {
      if (args.length != 1) throw new TranslationError("unimplemented") at f(args)
      `⟨ ⟩?`(args(0)) match {
        case Some(elements) => elements map expr reduce ((x,y) => TypedTerm(f(x,y), scalar))
        case _ => app(f, args)
      }
    }

    def defn(f: Term, vars: List[Term], body: Term) =  def_(f)(`()`(vars))(body)
    def defn(entry: (Identifier, (List[Term], Term))): Term = entry match { case (f, (vars, body)) => defn(T(f), vars, body) }

    def decl(vars: List[Term]) = vars map { va =>
      val typ =shape(typeOf_!(va))(new Scope)  // scope is irrelevant
      val args = targs(typ)
      if (args.isEmpty)
        def_(va)
      else {
        val vars = qvars(args, Strip.lower)
        val (f_val, f_supp) = ($TV(s"${va.root.literal}_val"),$TV(s"${va.root.literal}_supp"))
        `;`(
          def_(TypedTerm(f_val, tret(typ)), `()`(vars)),
          def_(TypedTerm(f_supp, Prelude.B), `()`(vars)),
          def_(TypedTerm(va, scalar), `()`(vars), ret(when(f_supp(vars), f_val(vars))))
        )
      }
    }

    def pred(typ: Term) = {
      val args = TypingSugar.qvars(targs(shape(typ)), Strip.lower)
      val body = TypedTerm(&&(TypeTranslation.checks(scope, typ, args)), Prelude.B)

      apply(TI("↦")(args)(body)>>, sized=false)
    }

    def pred(typ: Term, name: String): Term = rename(pred(typ), name)

    def rename(defTerm: Term, name: String) = defTerm match {
      case T(def_.root, f :: xs) => def_(preserve(f, TI(name)) :: xs)
      case _ => throw new TranslationError("not a def() term") at defTerm
    }

    def emit(t: Term)(implicit ctx: Context) = {
      val defns = ctx.innerFuncs map defn
      if (defns.isEmpty) ret(t)
      else `;`(defns toList)(ret(t))
    }

    def toplevel(t: Term, sized: Boolean)(implicit ctx: Context) = {
      if (!(t.isLeaf && ctx.innerFuncs.size == 1 && ctx.innerFuncs.contains(t.root)))
        throw new TranslationError("top-level expression must be a function") at t
      val n = TypedTerm(TV("n"), Prelude.N)
      val sz = if (sized) Some(n) else None
      ctx.innerFuncs.head match { case (f, (vars, body)) => defn(T(f), sz ++: vars, body) }
    }
  }

  object CodeGen {
    val scalar = TS("scalar")
    val when = TI("when")
    val only = TI("only")
    val slash = TI("slash")
    val def_ = TI("def")
    val `()` = TI("()")
    val `;` = TI(";")
    val ret = TI("ret")

    def codegen(t: Term)(implicit scope: Scope) = new CodeGen apply t
  }



  class SketchOutput {

    import CodeGen._
    import TypedTerm.typeOf_!

    val mnemonics = new Mnemonics {
      override def isIdentifierPart(c: Character) = c < 0x100 && super.isIdentifierPart(c)
    }

    def mne(id: Identifier) = mnemonics.get(id)
    def mne(t: Term) = mnemonics.get(t.leaf)

    def apply(code: Term): String = code match {
      case T(def_.root, List(f, T(`()`.root, params), body)) =>
        try s"${typ(typeOf_!(f))} ${mne(f)}(${params map param mkString ", "}) {\n${indent(apply(body))}\n}"
        finally mnemonics release (params map (_.leaf))
      case T(def_.root, List(f, T(`()`.root, params))) =>
        try s"${typ(typeOf_!(f))} ${mne(f)}(${params map param mkString ", "});"
        finally mnemonics release (params map (_.leaf))
      case T(`;`.root, stmts) => stmts map apply mkString "\n"
      case T(@:.root, f :: args) => s"apply(${mne(f)}, ${args map apply mkString ", "})"
      case T(ret.root, List(r)) => s"return ${apply(r)};"
      case T(v, Nil) => s"${mne(v)}"
      case T(InfixOp(op), List(a, b)) => s"(${apply(a)} $op ${apply(b)})"
      case T(Reduction(f), args) => args match {
          case List(T(fn, Nil)) => s"$f(n, ${mne(fn)})"
          case List(T(`f`, List(T(fn, Nil))), b) => s"${f}_acc(${apply(b)}, n, ${mne(fn)})"  // this is an
          case List(a, T(`f`, List(T(fn, Nil)))) => s"${f}_acc(${apply(a)}, n, ${mne(fn)})"  // optimization
          case List(a, b) => s"${f}2(${apply(a)}, ${apply(b)})"
          case _ => throw new TranslationError(s"invalid usage of 'min'") at code
        }
      case T(f, args) => s"${mne(f)}(${args map apply mkString ", "})"
    }

    def harness(decl: Iterable[Term]) = {
      val (prologue, proto) = decl partition { case T(def_.root, List(v)) => false case _ => true }
      //val main = def_(TypedTerm($TV("main"), $TV("void")), `()`(proto flatMap (_.subtrees) toList))
      (prologue map apply mkString "\n\n") + "\n\n#include \"harness.sk\"\n"
    }

    val INFIX = Map(I("+") -> "+", I("-") -> "-", I("=") -> "==", I("<") -> "<", I(">") -> ">", I("&") -> "&&")

    object InfixOp { def unapply(id: Identifier) = INFIX get id }

    val REDUCT = HashSet(Prelude.min.root)

    object Reduction { def unapply(id: Identifier) = if (REDUCT contains id) Some(id) else None }

    def typ(typ: Term) =
      if (typ == scalar) "|scalar|"
      else if (typ == Prelude.B) "bit"
      else if (typ.isLeaf) "int"
      else "fun"

    def param(t: Term) = s"${typ(typeOf_!(t))} ${mne(t)}"

    def indent(block: String) = block split "\n" map ("    " + _) mkString "\n"
  }
}
