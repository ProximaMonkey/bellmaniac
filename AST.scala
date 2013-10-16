sealed trait Term 

// Recursive algorithm definition
case class Algorithm(v: Var, args: List[Var], pre: Pred, expr: Expr) {
  assert (v.arity == args.size)
  override def toString = Python.print(this)
}

// Boolean predicate
sealed trait Pred extends Term {
  def and(that: Pred): Pred = that match {
    case True => this
    case _ => And(this, that)
  }
  def or(that: Pred): Pred = that match {
    case False => this
    case _ => Or(this, that)
  }
  def unary_! = Not(this)  
  override def toString = Python.print(this)
  def s(subs: List[(Var, Expr)]) = 
    Transform.substitute(this, subs.toMap)
}
object True extends Pred {
  override def and(that: Pred) = that
}
object False extends Pred {
  override def or(that: Pred) = that
}
sealed trait Comparison extends Pred { 
  def l: Expr
  def r: Expr 
}
case class Eq(l: Expr, r: Expr) extends Comparison
case class GT(l: Expr, r: Expr) extends Comparison
case class LT(l: Expr, r: Expr) extends Comparison
case class GE(l: Expr, r: Expr) extends Comparison
case class LE(l: Expr, r: Expr) extends Comparison
sealed trait BinaryPred extends Pred { 
  def l: Pred
  def r: Pred 
}
case class And(l: Pred, r: Pred) extends BinaryPred
case class Or(l: Pred, r: Pred) extends BinaryPred
case class Not(p: Pred) extends Pred

// Expression language
sealed trait Expr extends Term {
  def +(that: Expr) = Plus(this, that)
  def -(that: Expr) = Minus(this, that)
  def *(that: Expr) = Times(this, that)
  def /(that: Expr) = Div(this, that)
  def mod(that: Expr) = Mod(this, that)
  def ===(that: Expr) = Eq(this, that)
  def <(that: Expr) = LT(this, that)
  def <=(that: Expr) = LE(this, that)
  def >(that: Expr) = GT(this, that)
  def >=(that: Expr) = GE(this, that)
  override def toString = Python.print(this)
  def arity = 0
  def where(v: Var) = {
    val that = this
    new { def in(r: Range) = Compr(that, v, r) }
  }
  def s(subs: List[(Var, Expr)]) = Transform.substitute(this, subs.toMap)
}

// Fall through conditional statement
case class Cond(cases: List[(Pred, Expr)], default: Expr = Havoc) extends Expr {
  def ELIF(pe: (Pred, Expr)) = Cond(cases :+ pe, default)
  def ELSE(e: Expr) = Cond(cases, e)
}
object IF {
  def apply(pe: (Pred, Expr)) = Cond(pe :: Nil)
}

// Algebra on integers 
case class Const(i: Int) extends Expr 
object Havoc extends Expr
sealed trait BinaryExpr extends Expr { 
  assert (l.arity == 0 && r.arity == 0)
  def l: Expr
  def r: Expr 
}
case class Plus(l: Expr, r: Expr) extends BinaryExpr
case class Minus(l: Expr, r: Expr) extends BinaryExpr
case class Times(l: Expr, r: Expr) extends BinaryExpr
case class Div(l: Expr, r: Expr) extends BinaryExpr
case class Mod(l: Expr, r: Expr) extends BinaryExpr
object Zero extends Expr
case class Reduce(range: Seq, init: Expr = Zero) extends Expr 

// Functions
sealed trait Funct extends Expr {
  def apply(exprs: Expr*) = App(this, exprs.toList)
  // subsequent compositions rewrite "v" in the operator
  def compose(that: Funct): Funct
}
case class Var(name: String, override val arity: Int = 0) extends Funct {
  def fresh = Var.fresh(name, arity)
  // Translate argument list
  def translate(args: List[Var], op: (Var, Expr)*) = {
    assert (arity > 0)
    val map = op.toMap
    val dargs = args.map(_.fresh)
    val exprs = (args zip dargs).map {
      case (arg, darg) => 
        if (map.contains(arg)) 
          map(arg).s(args zip dargs)
        else
          darg
    }
    OpVar(this, dargs, exprs)
  }
  def rename(s: String) = Var(s, arity)
  override def compose(that: Funct) = {
    assert (this.arity == that.arity, "composition disallowed")
    that
  }
}
object Var {
  private var counter = 0
  def fresh(prefix: String = "_v", arity: Int = 0) = {
    counter = counter + 1
    Var(prefix + counter, arity)
  }
}
case class OpVar(v: Var, args: List[Var], exprs: List[Expr]) extends Funct {
  assert(v.arity > 0, "must be a function")
  assert(v.arity == exprs.size, "wrong number of arguments in translation")
  override def arity = args.size
  override def compose(that: Funct) = that match {
    case w: Var => OpVar(w, args, exprs)
    case OpVar(w, wargs, wexprs) => OpVar(w, args, wexprs.map(_.s(wargs zip exprs)))
  }
}
case class App(v: Funct, args: List[Expr]) extends Expr {
  assert(v.arity == args.size, "wrong number of arguments in " + this)
  def flatten = v match {
    case OpVar(v, vargs, vexprs) =>
      App(v, vexprs.map(_.s(vargs zip args)))
    case _ => this
  }
}

// Sequence of (one-dimensional) values to be reduced
sealed trait Seq extends Term {
  def ++(that: Seq) = Join(this, that)
}
// Denotes empty seq if l >= h
case class Range(l: Expr, h: Expr) 
case class Compr(expr: Expr, v: Var, r: Range) extends Seq 
case class Join(l: Seq, r: Seq) extends Seq 
case class Singleton(e: Expr) extends Seq 

// Collect all variables
object Vars {
  def apply(t: Term): Set[Var] = t match {
    case True | False => Set() 
    case t: Comparison => apply(t.l) ++ apply(t.r)
    case t: BinaryPred => apply(t.l) ++ apply(t.r)
    case Not(p) => apply(p)
    case e: BinaryExpr => apply(e.l) ++ apply(e.r)
    case Zero | Havoc => Set()
    case _: Const => Set() 
    case Reduce(range, init) => apply(range) ++ apply(init)
    case v: Var => Set(v)
    case OpVar(v, args, exprs) => exprs.toSet[Expr].flatMap(apply(_)) ++ Set(v) -- args.toSet
    case App(v, args) => args.toSet[Expr].flatMap(apply(_)) ++ apply(v)
    case Compr(expr, v, Range(l, h)) => apply(expr) ++ Set(v) ++ apply(l) ++ apply(h)
    case Join(l, r) => apply(l) ++ apply(r)
    case Singleton(e) => apply(e)
    case Cond(cases, default) => 
      cases.flatMap { case (p, e) => apply(p) ++ apply(e) }.toSet ++
      apply(default)
  }
}

object Python {
  val prelude = 
"""
class memoize(dict):
  def __init__(self, func):
    self.func = func
  def __call__(self, *args):
    return self[args]
  def __missing__(self, key):
    result = self[key] = self.func(*key)
    return result
plus = min
zero = 10000000000000000000000000000
"""
  // translate into python list/generator
  def print(s: Seq): String = s match {
    case Singleton(e) => "[" + print(e) + "]"
    case Join(l, r) => print(l) + " + " + print(r)
    case Compr(e, v, Range(l, h)) => "[" + print(e) + " for " + print(v) + 
      " in range(" + print(l) + ", " + print(h) + ")]"
  }
  def print(e: Expr): String = e match {
    case Var(n, _) => n
    case Const(i) => i.toString
    case Plus(l, r) => "(" + print(l) + " + " + print(r) + ")"
    case Minus(l, r) => "(" + print(l) + " - " + print(r) + ")"
    case Times(l, r) => print(l) + "*" + print(r)
    case Div(l, r) => print(l) + "/" + print(r)
    case Mod(l, r) => "(" + print(l) + " mod " + print(r) + ")"
    case App(v, args) => print(v) + "(" + args.map(print(_)).mkString(", ") + ")"
    case Reduce(r, init) => "reduce(plus, " + print(r) + ", " + print(init) + ")"
    case Zero => "zero"
    case Havoc => "None"
    case OpVar(v, args, exprs) => "(lambda " + args.map(print(_)).mkString(", ") + 
      ": " + print(v) + "(" + exprs.map(print(_)).mkString(", ") + ")"
    case Cond(cases, default) => "  if " + cases.map { case (pred, expr) =>
      print(pred) + ":\n    return " + print(expr)
    }.mkString("\n  elif ") + "\n  else:\n    return " + print(default)          
  }
  def print(p: Pred): String = p match {
    case True => "True"
    case False => "False"
    case And(l, r) => "(" + print(l) + " and " + print(r) + ")"
    case Or(l, r) => "(" + print(l) + " or " + print(r) + ")"
    case Not(l) => "(not " + print(l) + ")"
    case Eq(l, r) => "(" + print(l) + " == " + print(r) + ")"
    case LT(l, r) => "(" + print(l) + " < " + print(r) + ")"
    case GT(l, r) => "(" + print(l) + " > " + print(r) + ")"
    case LE(l, r) => "(" + print(l) + " <= " + print(r) + ")"
    case GE(l, r) => "(" + print(l) + " >= " + print(r) + ")"
  }
  def print(a: Algorithm): String = {
    "@memoize\ndef " + print(a.v) +
    "(" + a.args.map(print(_)).mkString(", ") + "):\n" +     
    "  assert " + print(a.pre) + 
    "\n" + { a.expr match {
      case e: Cond => print(e)
      case e => "  return " + print(e)
    }}
  }
}

object SMT {
  val z3 = new Z3
  import z3._ 
  command("(declare-sort Seq)")
  command("(declare-fun zero () Int)")
  command("(declare-fun reduce (Seq Int) Int)")
  command("(declare-fun join (Seq Seq) Seq)")
  command("(declare-fun compr (Int Int) Seq)")
  command("(declare-fun singleton (Int) Seq)")
 
  import collection.mutable.ListBuffer
  def print(e: Expr)(implicit side: ListBuffer[String]): String = e match {
    case Var(n, k) if k == 0 => n
    // apply to variable witnesses
    case v @ Var(n, k) if k > 0 => print(App(v, (1 to k).map(i => Var(n + "_v_" + i)).toList))
    case o @ OpVar(Var(n, k), _, _) => print(App(o, (1 to k).map(i => Var(n + "_v_" + i)).toList))
    case Const(i) => i.toString
    case Plus(l, r) => "(+ " + print(l) + " " + print(r) + ")"
    case Minus(l, r) => "(- " + print(l) + " " + print(r) + ")"
    case Times(l, r) => "(* " + print(l) + " " + print(r) + ")"
    case Div(l, r) => 
      // TODO: can't easily express n is a power of two
      side += "(assert " + print((l mod r) === Const(0)) + ")"
      "(div " + print(l) + " " + print(r) + ")"
    case Mod(l, r) => "(mod " + print(l) + " " + print(r) + ")"
    case Zero => "zero"
    case Havoc =>
      val v = Var.fresh()
      side += "(declare-const " + v.name + " Int)"
      print(v)
    case Reduce(r, init) => "(reduce " + print(r) + " " + print(init) + ")"
    case a: App => a.flatten match {
      case App(Var(n, k), args) => "(" + n + " " + args.map(print(_)).mkString(" ") + ")"
      case _ => ???
    }
    case Cond(cases, d) => cases match {
      case (p, e) :: rest=> "(ite " + print(p) + " " + print(e) + "\n " + print(Cond(rest, d)) + ")"
      case Nil => print(d)
    }
  }
  def print(s: Seq)(implicit side: ListBuffer[String]): String = s match {
    case Singleton(e) => "(singleton " + print(e) + ")"
    case Join(l, r) => "(join " + print(l) + " " + print(r) + ")"
    case Compr(e, v, Range(l, h)) => "(compr " + 
      print(e.s(List(v->(v+l)))) + " " + print(h-l) + ")"
  }
  def print(p: Pred)(implicit side: ListBuffer[String]): String = p match {
    case True => "true"
    case False => "false"
    case And(l, r) => "(and " + print(l) + " " + print(r) + ")"
    case Or(l, r) => "(or " + print(l) + " " + print(r) + ")"
    case Not(l) => "(not " + print(l) + ")"
    case Eq(l, r) => "(= " + print(l) + " " + print(r) + ")"
    case LT(l, r) => "(< " + print(l) + " " + print(r) + ")"
    case GT(l, r) => "(> " + print(l) + " " + print(r) + ")"
    case LE(l, r) => "(<= " + print(l) + " " + print(r) + ")"
    case GE(l, r) => "(>= " + print(l) + " " + print(r) + ")"
  }

  // Check for satisfaction
  def check(p: Pred) = {
    val side = new ListBuffer[String]
    for (v <- Vars(p)) {
      side += "(declare-fun " + v.name + 
        " (" + (1 to v.arity).map(_ => "Int").mkString(" ")+ ") Int)";
      // equality witnesses
      for (i <- 1 to v.arity)
        side += "(declare-fun " + v.name + "_v_" + i + " () Int)";
    }

    val formula = print(p)(side)

    push()
    for (s <- side)
      command(s)
    assert(formula)
    val out = z3.check()

    if (out) {
      println("cex: " + p)
      for (v <- Vars(p) if v.arity == 0)
        println(eval(v.name))
    }

    pop()

    out
  }

  def close() {
    z3.close()
  }
}


object Transform {
  trait Transformer {
    def apply(path: Pred, p: Pred): Option[Pred] = None
    def apply(path: Pred, e: Expr): Option[Expr] = None
    def apply(path: Pred, s: Seq): Option[Seq] = None
  }
  def visit(p: Pred)(implicit path: Pred, f: Transformer): Pred = 
    f(path, p) match {
      case Some(out) => out
      case None => p match {
        case True => True
        case False => False
        case And(l, r) => And(visit(l), visit(r))
        case Or(l, r) => Or(visit(l), visit(r))
        case Not(l) => Not(visit(l))
        case Eq(l, r) => Eq(visit(l), visit(r))
        case LT(l, r) => LT(visit(l), visit(r))
        case GT(l, r) => GT(visit(l), visit(r))
        case LE(l, r) => LE(visit(l), visit(r))
        case GE(l, r) => GE(visit(l), visit(r))
      }
    }
  def visit(e: Expr)(implicit path: Pred, f: Transformer): Expr = 
    f(path, e) match {
      case Some(out) => out
      case None => e match {
        case _: Var | _: Const | Zero | Havoc => e
        case Plus(l, r) => Plus(visit(l), visit(r))
        case Minus(l, r) => Minus(visit(l), visit(r))
        case Times(l, r) => Times(visit(l), visit(r))
        case Div(l, r) => Div(visit(l), visit(r))
        case Mod(l, r) => Mod(visit(l), visit(r))
        case App(v, args) => 
          App(visit(v).asInstanceOf[Funct], args.map(visit(_)))
        case Reduce(range, init) => Reduce(visit(range), visit(init))
        case OpVar(v, args, exprs) => 
          OpVar(visit(v).asInstanceOf[Var], 
            args.map(visit(_).asInstanceOf[Var]), 
            exprs.map(visit(_)))
        case Cond(cases, default) => 
          var els: Pred = path
          Cond(cases.map {
            case (p, e) => 
              val out = (visit(p)(els, f), visit(e)(els and p, f))
              els = els and !p
              out
          }, visit(default)(els, f))
      }
    }
  def visit(s: Seq)(implicit path: Pred, f: Transformer): Seq = 
    f(path, s) match {
      case Some(out) => out
      case None => s match {
        case Compr(expr, v, Range(l, h)) => 
          Compr(visit(expr)(path and l <= v and v < h, f), 
            visit(v).asInstanceOf[Var], 
            Range(visit(l), visit(h)))
        case Join(l, r) => Join(visit(l), visit(r))
        case Singleton(e) => Singleton(visit(e))
      }
    }

  def transformer(f: PartialFunction[Term, Term]) = new Transformer {
    override def apply(path: Pred, p: Pred) = f.lift(p).asInstanceOf[Option[Pred]]
    override def apply(path: Pred, e: Expr) = f.lift(e).asInstanceOf[Option[Expr]]
    override def apply(path: Pred, s: Seq) = f.lift(s).asInstanceOf[Option[Seq]]
  }

  def exprTransformer(f: PartialFunction[(Pred, Expr), Expr]) = new Transformer {
    override def apply(path: Pred, e: Expr) =
      if (f.isDefinedAt((path, e)))
        Some(f((path, e)))
      else
        None
  }

  def transform(e: Expr)(f: PartialFunction[Term, Term]): Expr = 
    visit(e)(True, transformer(f))


  def transform(p: Pred)(f: PartialFunction[Term, Term]): Pred = 
    visit(p)(True, transformer(f))

  def substitute(e: Expr, f: Map[Var, Expr]): Expr = 
    transform(e) {
      case v: Var if f.contains(v) => f(v)
    }

  def substitute(p: Pred, f: Map[Var, Expr]): Pred =
    transform(p) {
      case v: Var if f.contains(v) => f(v)
    }
}

sealed trait Step
case class RefStep(op: Funct) extends Step
case object PartStep extends Step

/**
 * Refinement steps
 */
class Refinement {
  // keep all versions of algorithms
  import collection.mutable.{ HashMap, MultiMap, Set }
  import Transform._
  val steps = new HashMap[Algorithm, Set[(Algorithm, Step)]]
    with MultiMap[Algorithm, (Algorithm, Step)]

  def lift(from: Var, to: Var): Option[Funct] = {
    for ((k, vs) <- steps
         if k.v == from;
         (l, s) <- vs)
        s match {
          case RefStep(op) =>
            if (l.v == to)
              return Some(op)
            else lift(l.v, to) match {
              case Some(op2) => 
                return Some(op compose op2)
              case _ =>
            }
          case _ =>
        }
    return None
  }

  private def msg(s: => String) = {
    print(Console.RED)
    println(s)
    print(Console.RESET)
  }

  // Apply an induction step
  def step(f: Algorithm => (Algorithm, Step)) = 
    (x: Algorithm) => {
      val (out, stp) = f(x)
      steps.addBinding(x, (out, stp))
      msg(x.v + " refined to ")
      println(out)    
      out
    }
  
  // Check induction
  def induction(a: Algorithm, metric: Expr, base: Expr) {
    visit(a.expr)(a.pre, exprTransformer {
      case (path, app @ App(v, args)) if v == a.v =>
        if (SMT.check(path and metric.s(a.args zip args) >= metric)) {
          msg("failed induction check on " + a.v + " at " + app) 
        }
        app
    })
    
    if (SMT.check(a.pre and metric <= base))
      msg("failed base case check")
  }

  // Add parameters to a function 
  def introduce(name: String, vs: Var*) = 
    step {
      case Algorithm(v, args, pre, e) => 
        val w = Var(name, v.arity + vs.size)
        val args1 = args.map(_.fresh)
        (Algorithm(w, args ++ vs.toList, pre, e), 
         RefStep(OpVar(w, args1, args1 ++ vs.toList)))        
    }

  // Push functions down refinement chain
  // todo: check termination
  def pushDown(name: String) = 
    step {
      case Algorithm(v, args, pre, e) =>
        val w = v.rename(name)        
        def push(e: Expr): Expr = transform(e) {
          case App(u: Var, uargs) if lift(u, v).isDefined =>
            App(lift(u, v).get compose w, uargs.map(push(_))).flatten
        }
        (Algorithm(w, args, pre, push(e)), RefStep(w))
    }
  def pushDown(name: String, goal: Algorithm) = 
    step {
      case Algorithm(v, args, pre, e) =>
        val w = v.rename(name)        
        def push(e: Expr): Expr = transform(e) {
          case App(u: Var, uargs) if lift(u, goal.v).isDefined =>
            App(lift(u, goal.v).get, uargs.map(push(_))).flatten
        }
        (Algorithm(w, args, pre, push(e)), RefStep(w))
    }
  

  // Create specialized versions of alg by splitting the domain
  def partition(name: String, base: Pred, splits: Pred*): Algorithm => List[Algorithm] = {    
    case a @ Algorithm(v, args, pre, e) =>
      var cases: List[(Pred, Expr)] = Nil;
      var algs: List[Algorithm] = Nil;

      var out = IF (base -> App(v, args))

      def explore(mask: List[Boolean] = Nil) {
        if (mask.size == splits.size) {
          val p = (mask.reverse zip splits.toList) map {
            case (b, split) => if (b) split else !split
          } reduce (_ and _)

          if (SMT.check(p and pre)) {
            val n = v.name + mask.reverse.map(if (_) "0" else "1").mkString
            val cs = Algorithm(Var(n, v.arity), args, pre and p, App(v, args))
            out = out ELIF (p -> App(cs.v, args))
            algs = cs :: algs        
          }
        } else {
          explore(true :: mask)
          explore(false :: mask)
        }
      }
      explore()

      val alg = Algorithm(Var(name, v.arity), args, pre, out) 

      println(alg)
      steps.addBinding(a, (alg, RefStep(alg.v)))    
      for (alg <- algs) {
        println(alg)
        steps.addBinding(a, (alg, PartStep))
      }

      alg :: algs.reverse
  }


  // Rewrite calls to c as d = OpVar(c,...) provided substitution
  // is correct under path condition
  def rewrite(name: String, c: Algorithm, op: (Var, Expr)*) =
    rewrite0(name, c, c.v.translate(c.args, op: _*))

  def rewrite0(name: String, c: Algorithm, d: OpVar) = step {
    case Algorithm(v, args, pre, e) =>
      assert (d.v == c.v)
      val w = v.rename(name)
      (Algorithm(w, args, pre, 
        visit(e)(pre, exprTransformer {
          case (path, App(w, args)) if w == c.v && proveEq(path, c, d) =>
            (App(d, args).flatten)
        })), RefStep(w))
  }

  // Complete OpVar of c based on op
  import collection.mutable.ListBuffer
  def unify(c: Algorithm, op: (Var, Expr)*) {
    val map = op.toMap   
    val cvs = c.args
    // assume we are given all 0-arity variables
    // now infer remaining linear operators (including c itself)
    var operators = Map[Var, LinearOp]()
    val dvs = cvs.map { v =>
      if (map.contains(v)) 
        map(v)
      else {
        assert (v.arity > 0, "0-arity var " + v + " requires user input")
        val w = v.fresh
        w
      }
    }
    implicit val buf = new ListBuffer[Pred]
    unify(c.expr, c.expr.s(cvs zip dvs))  
    for (p <- buf)
      println(p)
  }

  // Relaxed unification
  def unify(c: Term, d: Term)(implicit eqs: ListBuffer[Pred]) {
    (c, d) match {
      case (c: BinaryPred, d: BinaryPred) if c.getClass == d.getClass =>
        unify(c.l, d.l)
        unify(c.r, d.r)
      case (Not(c), Not(d)) => unify(c, d)
      case (c: Comparison, d: Comparison) if c.getClass == d.getClass =>
        unify(c.l - c.r, d.l - d.r)       
      case (c: Cond, d: Cond) if c.cases.size == d.cases.size =>
        for (((cp, ce), (dp, de)) <- c.cases zip d.cases) {
          unify(cp, dp)
          unify(ce, de)
        }
        unify(c.default, d.default)
      case (c: Reduce, d: Reduce) =>
        unify(c.range, d.range)
        unify(c.init, d.init)
      case (c: Join, d: Join) =>
        unify(c.l, d.l)
        unify(c.r, d.r)
      case (Singleton(c), Singleton(d)) => unify(c, d)
      case (c: Compr, d: Compr) =>
        unify(c.r.h - c.r.l, d.r.h - d.r.l)
        unify(c.expr.s((c.v, c.r.l)::Nil), d.expr.s((d.v, d.r.l)::Nil))
      case (c: App, d: App) =>
        eqs += (c.flatten === d.flatten)
      case (c: Expr, d: Expr) if Linear(c).isDefined && Linear(d).isDefined =>
        eqs += (c === d)
      case (c: BinaryExpr, d: BinaryExpr) if c.getClass == d.getClass =>
        unify(c.l, d.l)
        unify(c.r, d.r)
      case _ =>
    }
  }
    
  // Prove by induction that pred(v) => c(v) = c(f(v)) where d = OpVar(c, f)
  def proveEq(pred: Pred, c: Algorithm, d: OpVar): Boolean = {
    assert (d.v == c.v)
    println("proving " + d + " under " + pred)
    // check domains: d is well defined at p and c.pre
    val domain = pred and c.pre and ! c.pre.s(c.args zip App(d, c.args).flatten.args)
    if (SMT.check(domain)) {
      msg("failed domain check")
      return false
    }

    // check that inductive calls satisfy pred
    visit(c.expr)(pred and c.pre, exprTransformer {        
      case (path, a @ App(v, args)) if v == c.v =>
        if (SMT.check(path and ! pred.s(c.args zip args))) {
          msg("*** failed inductive step check")
          return false
        }
        a
    })

    // prove by induction on v (c's metric)
    // inductive step
    // ind is an uninterpreted version of c for the inductive step
    val ind = Var("_ind_" + c.v.name, c.v.arity)
    val c_ind = transform(c.expr) {
      case App(v, args) if v == c.v => 
        // by induction hypothesis
        App(d, args).flatten match {
          // avoid recursion in the formula
          case App(v, args) => assert(v == c.v); App(ind, args)
        }                  
    }
    val d_ind = transform(c.expr.s(c.args zip App(d, c.args).flatten.args)) {
      // avoid recursion in the formula
      case App(v, args) if v == c.v => App(ind, args)        
    }

    // println(c_ind)
    // println(d_ind)
    
    if (SMT.check(pred and c.pre and !(c_ind === d_ind))) {
      msg("*** failed to prove equation equality")
      return false
    }
   
    return true
  }

  // c1 is a refinement and/or restriction of c0
  // TODO check for induction metric
  def refine(name: String, c0: Algorithm, c1: Algorithm) = step {
    case Algorithm(v, args, pre, expr) =>
      val w = v.rename(name)
      (Algorithm(w, args, pre, visit(expr)(pre, exprTransformer {
        case (path, App(w, wargs)) if w == c0.v &&          
          (! SMT.check(path and ! c1.pre.s(c1.args zip wargs))) =>
          App(if (c1.v == v) Var(name, v.arity) else c1.v, wargs)
      })), RefStep(w))
  }

  // Unroll application to c
  def unfold(name: String, c: Algorithm) = step {
    case Algorithm(v, args, pre, expr) =>
      val w = v.rename(name)
      (Algorithm(w, args, pre, transform(expr) {
        case App(w, wargs) if w == c.v =>
          c.expr.s(c.args zip wargs)
      }), RefStep(w))
  }
        
  // Split "k in range(a,b) into k1 in range(a,e) and k2 in range(e,b)
  def splitRange(name: String, k: Var, mid: Expr) = step {
    case Algorithm(v, args, pre, expr) =>
      val w = v.rename(name)
      (Algorithm(w, args, pre, transform(expr) {
        case Compr(e, v, Range(l, h)) if v == k => 
          val v1 = Var(v.name + "1")
          val v2 = Var(v.name + "2")
          Join(Compr(substitute(e, Map(v->v1)), v1, Range(l, mid)), 
              Compr(substitute(e, Map(v->v2)), v2, Range(mid, h)))
      }), RefStep(w))
  }
  // Generalize zero
  def genZero(name: String, zero: Expr) = step { 
    case Algorithm(v, args, pre, expr)  =>
      val w = v.rename(name)
      (Algorithm(w, args, pre, transform(expr) { case Zero => zero }), 
       RefStep(w)) 
    }

  // Generalize variable application
  def genApp(name: String, ov: Var, nv: Var) =
    step {
      case Algorithm(v, args, pre, e) =>
        val w = Var(name, v.arity + 1)
        val args1 = args.map(_.fresh)
        var op: Option[OpVar] = None
        (Algorithm(w, args :+ nv, pre, transform(e) {
          case App(u: Var, uargs) if u == ov && ! op.isDefined =>
            val nvargs = uargs.take(nv.arity)
            val nvargs1 = nvargs.map(v => Var.fresh(arity = v.arity))
            op = Some(OpVar(ov, nvargs1, nvargs1 ++ uargs.drop(nv.arity)))
            App(nv, nvargs)
        }), RefStep(OpVar(w, args1, args1 :+ (op match {
            case Some(op) => // todo: op
              msg("full refstep: " + op)
              nv
            case None => nv
          }))))
    }
}


object Parenthesis {
  import Prelude._
  implicit def int2expr(i: Int) = Const(i)

  val c = Var("c", 2)
  val w = Var("w", 3)
  val x = Var("x", 1)
  
  val C = Algorithm(c, i :: j :: Nil, 
    0 <= i and i < n and i < j and j < n, 
    IF 
      ((i === j - 1) -> x(j))
    ELSE 
      Reduce(c(i, k) + c(k, j) + w(i, k, j) 
        where k in Range(i+1, j)))
    
  def main(args: Array[String]) {
    import Console.println
    import Transform._

    def $ = Var.fresh().name
    
    val refinement = new Refinement()
    import refinement._

    induction(C, j-i, 0)
    val c0 = (introduce($, n, w, x) andThen pushDown("c0"))(C)    
    val List(c1, c000, c001, c011) = partition("c1", n < 4, i < n/2, j < n/2)(c0) 
  
    val c100 = rewrite("c100", c0, n -> n/2)(c000) 
    // use free assumption n mod 2 = 0    
    val c111 = rewrite("c111", c0, i -> (i-n/2), j -> (j-n/2), n -> n/2, 
      x -> x.translate(List(j), j->(j+n/2)), 
      w -> w.translate(List(i, j, k), i->(i+n/2), j->(j+n/2), k->(k+n/2)))(c011)
    // TODO:
    // inferring offsets in i and j requires using the precondition in addition
    // to the symbolic unification
    println("UNIFICATION EQS:")
    unify(c0, i->(i-n/2), j->(j-n/2), n->n/2)
      
    val r = Var("r", 2)
    val b0 = (unfold($, c0) andThen 
      genZero($, r(i, j)) andThen 
      introduce("b0", r))(c001)   
    val b1 = splitRange("b1", k, n/2)(b0)
    // merge with pushDown command
    val b2 = refine("b2", c0, c100)(b1)
    val b3 = refine("b3", c0, c111)(b2)    
    val b4 = refine("b4", c0, c001)(b3)
    
    val s = Var("s", 2)
    val t = Var("t", 2)
    val b5 = (genApp($, c100.v, s) andThen 
      genApp("b5", c111.v, t))(b4)
    
    // todo: re-apply c0 -> b to recursive applications
    val b = pushDown("b")(b5)
    
    val List(b6, b00, b01, b10, b11) = partition("b6", n < 8, i < n/4, j < n/2+n/4)(b)
       
    val b100 = rewrite("b100", b,
        i->(i-n/4),
        j->(j-n/4),
        n->n/2,
        w->w.translate(List(i,j,k), i->(i+n/4),j->(j+n/4),k->(k+n/4)),
        x->x.translate(List(i), i->(i+n/4)),
        s->s.translate(List(i,j), i->(i+n/4), j->(j+n/4)),        
        t->t.translate(List(i,j), i->(i+n/4), j->(j+n/4)),
        r->r.translate(List(i,j), i->(i+n/4), j->(j+n/4)))(b10)

    

    // introduce C beforehand
    // provide axioms to reason about reduce
    //val b000 = rewrite("b000", b,
    //    n->n/2,
    //    j->(j-n/4))(b00)

    GraphViz.display(steps)
    SMT.close()
  }
}

object Gap {
  import Prelude._
  implicit def int2expr(i: Int) = Const(i)
  implicit def expr2singleton(e: Expr) = Singleton(e)

  val w = Var("w", 2)
  val w1 = Var("w1", 2)
  val S = Var("S", 2)
  val g = Var("g", 2)

  val G = Algorithm(g, i :: j :: Nil,
    0 <= i and i <= n and 0 <= j and j <= n,
    IF  
      ((i === 0 and j === 0) -> 0)
    ELIF 
      ((i === 0 and j > 0) -> w(0, j))
    ELIF 
      (((i > 0 and j === 0) -> w1(0, i)))
    ELSE 
      Reduce(
        (g(i, q) + w(q, j) where q in Range(0, j)) ++
        (g(p, j) + w1(p, i) where p in Range(0, i)) ++
        (g(i-1, j-1) + S(i, j))))
}

object Floyd {
  import Prelude._ 
  implicit def int2expr(i: Int) = Const(i)

  val l = Var("l", 2)
  val d = Var("d", 3)

  // TODO: custom operators
  val D = Algorithm(d, i :: j :: k :: Nil,
    0 <= i and i < n and
    0 <= j and j < n and
    0 <= k and k <= n,
    IF 
      ((k === 0) -> l(i, j))
    ELSE 
      (d(i, j, k-1) + d(i, k-1, k-1) * d(k-1, j, k-1)))
}


object Prelude {
  val i = Var("i")
  val j = Var("j") 
  val k = Var("k")
  val l = Var("l")
  val m = Var("m")
  val n = Var("n")
  val o = Var("o")
  val p = Var("p")
  val q = Var("q")
}
