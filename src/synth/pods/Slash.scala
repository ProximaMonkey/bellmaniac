package synth.pods

import semantics.TypeTranslation.Declaration
import syntax.AstSugar._
import semantics.TypeTranslation.TypingSugar._
import semantics.Scope
import semantics.Prelude
import semantics.TypedTerm
import semantics.TypedLambdaCalculus
import syntax.Identifier
import synth.pods.Pod.TacticalError


/**
 *        <
 * J = J₀ ⨃ J₁
 *
 * Means that J can be partitioned into J₀, J₁ s.t. every element of J₀ is less than every element of J₁.
 */
class PartitionPod(val J: Term, val < : Term, val J0: Term, val J1: Term)(implicit scope: Scope) extends Pod {
  import Prelude.{partition, allToAll}

  val U = T(scope.sorts.getMasterOf(J.leaf))

  override val decl = new Declaration where (
    partition(U)(J, J0, J1),
    allToAll(U)(J0, <, J1)
  )
}

object PartitionPod {
  def apply(J: Term, < : Term, J0: Term, J1: Term)(implicit scope: Scope) = new PartitionPod(J, <, J0, J1)
}

class SlicePod(val f: Term, val subdomains: List[Term]) extends Pod {
  import Prelude.?

  val slices = subdomains map (_ -> ?)
  override val program =
    f =:= /::(slices map (f :: _))

  override val obligations = {
    val fabs = $TyTV("f", TypedTerm.typeOf_!(f))
    fabs =:= /::(slices map (fabs :: _))
  }

}

object SlicePod {

  def apply(f: Term, subdomains: List[Term]) = new SlicePod(f, subdomains)

  def splitSkip(term: Term, sep: Identifier): List[Term] =
    if (term =~ (":", 2)) splitSkip(term.subtrees(1), sep)
    else if (term.root == sep) term.subtrees flatMap (splitSkip(_, sep))
    else List(term)

  def slices(term: Term) = splitSkip(term, I("/"))
}


class PadPod(f: Term, padding: List[Term]) extends Pod {

  import TypedTerm.typeOf_!

  override val program =
    f =:= (/::(f :: padding) :: typeOf_!(f))

  override val obligations = {
    val fabs = $TyTV("f", typeOf_!(f))
    fabs =:= (/::(fabs :: padding) :: typeOf_!(f))
  }
}

object PadPod {
  def apply(f: Term, padding: List[Term]) = new PadPod(f, padding)
  def apply(f: Term, padding: Term, subdomains: List[Term]) = {
    import Prelude.?
    val slices = subdomains map (_ -> ?)
    new PadPod(f, slices map (padding :: _))
  }
}


object SlashDistribPod {
  import TypedTerm.replaceDescendant
  
  def apply(f: Term, box: Term)(implicit scope: Scope) = {
    if (f.nodes exists (_ eq box)) {
      f =:= /::(box.split(I("/")) map (x => replaceDescendant(f, (box, x))))
    }
    else throw new TacticalError(s"'${box toPretty}' should be a sub-term of '${f toPretty}'")
  }
}

object StratifyPod {
  import Prelude.{ω,ℐ,?}
  import TypedTerm.{replaceDescendant,replaceDescendants}
  import SlicePod.splitSkip

  /*
   * TODO obligations
   * q, g monotonic
   * q nondecreasing
   */
  
  def apply(box: Term, quadrant: Term, sideburns: List[Term]=List())(implicit scope: Scope) = {
    val quadrants = splitSkip(box, I("/"))
    if (quadrants exists (_ eq quadrant)) {
      val θ = $TV("θ")
      val q = /::(quadrant +: (sideburns map (__ => ℐ :: (__ -> ?))))
      val g = replaceDescendant(box, (quadrant, ℐ))
      val strata = List(replaceDescendants(box, (quadrants filter (_ ne quadrant) map ((_, ℐ))) :+(quadrant, ω(q))),
        ω(g))
      ω(box) =:= θ ↦ ((θ /: strata)((x, y) => y :@ x))
    }
    else throw new Pod.TacticalError(s"'${quadrant toPretty}' is not a component of '${box toPretty}'")
  }
}



object StratifyLetPod {
  import Prelude.{ω,ℐ,?}
  import TypedLambdaCalculus.{pullOut,enclosure,simplify}
  import TypedTerm.replaceDescendant
  
  /*
   * TODO obligation
   * forall θ (capsule θ = capsule (f θ))
   */
  
  def apply(f: Term, component: Term)(implicit scope: Scope) = {
    val _θ = $TV("θ̲")
    val (capsule, ctx) = (pullOut(f, component) get, enclosure(f, component) get)
    val let = { val ψ = $TV("ψ") ; ψ ↦ ω(replaceDescendant(f, (component, ψ :@ ctx.tail))) }
    ω(f) =:= (_θ ↦ ((TI("let") :- (let :@ simplify(capsule :@ _θ)) :@ _θ)))
  }
}


object LetPod {
  import TypedLambdaCalculus.{pullOut,enclosure}
  import TypedTerm.replaceDescendant
  
  def apply(f: Term, component: Term)(implicit scope: Scope) = {
    val (capsule, ctx) = (pullOut(f, component) get, enclosure(f, component) get)
    val let = { val y = $TV("y") ; y ↦ (replaceDescendant(f, (component, y :@ ctx))) }
    f =:= (let :@ capsule)
  }
}

/*
object StratifyReducePod {
  import Prelude.{ω,ℐ,?}
  import TypedLambdaCalculus.{pullOut,enclosure}
  import TypedTerm.replaceDescendant

  import Prelude.{min,cons,nil}
  import ConsPod.{`⟨ ⟩`, `⟨ ⟩?`}
  
  def findContainingReduction(box: Term, components: List[Term]) =
    box.nodes find (n => n =~ ("@", 2) && n.subtrees(0).root != "cons" && (`⟨ ⟩?`(n.subtrees(1)) match {
      case Some(l) => components forall (x => l exists (_ eq x))
      case None => false
    }))
  
  /*
   * obligation; 
   * forall θ (q θ = q (box θ))
   * g >= q >= I
   * g monotonic
   */
    
  def apply(box: Term, components: List[Term])(implicit scope: Scope) = {
    val f = findContainingReduction(box, components) getOrElse { throw new TacticalError(s"cannot locate reduction containing '${components map (_ toPretty)}'") }
    val (capsule, ctx) = (pullOut(box, f) get, enclosure(box, f) get)
    /**/ assume(!ctx.isEmpty) /**/
    val quadrant = (box split I("/") find (_.hasDescendant(f)) get)
    /**/ assume(f =~ ("@", 2)) /**/
    val (reduce, all_components) = (f.subtrees(0), `⟨ ⟩?`(f.subtrees(1)) get)
    val other_components = all_components filter (x => !(components exists(_ eq x)))
    val θ = $TV("θ")
    val wwtb = ctx.head :@ ctx.tail
    val q = replaceDescendant(box, (f, reduce :@ (`⟨ ⟩`(components))))
    val g = replaceDescendant(box, (f, reduce :@ (`⟨ ⟩`(other_components :+ wwtb))))
    val strata = List(q, ω(g))
    ω(box) =:= θ ↦ ((θ /: strata)((x,y) => y :@ x))
  }
}
*/