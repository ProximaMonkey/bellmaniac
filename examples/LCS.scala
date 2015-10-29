package examples

import semantics.{Trench, TypeTranslation, Scope, Prelude}
import semantics.Prelude._
import syntax.AstSugar._
import semantics.TypeTranslation.TypingSugar._
import semantics.Domains.SubsortAssocT
import synth.pods.Pod
import synth.proof.{Prover, Assistant}



object LCS {

  val I = TS("I")
  val J = TS("J")
  val I0 = TS("I₀")
  val I1 = TS("I₁")
  val J0 = TS("J₀")
  val J1 = TS("J₁")

  val δ = TyTV("δ", I ->: J ->: B)

  import synth.pods.IndexArithPod._1

  case class APod(I: Term, J: Term) extends Pod {

    val (ψ, θ, i, j) = ($TV("ψ"), $TV("θ"), $TV("i"), $TV("j"))

    override val program = Prelude.program(
      (
        ψ ↦ fix((θ↦:i↦:j↦: /::(
          ψ:@(i,j),
          ( (θ:@(i-_1,j-_1)) + _1 ) |! (δ:@(i,j)),
          ( (θ:@(i,j-_1)) ⨁ (θ:@(i-_1,j)) ) |! (~(δ:@(i,j)))
        )) :: ((I x J) -> R) ->: ((I x J) -> R))
      ) :: ? ->: ((I x J) -> R)
    )

  }

  import semantics.TypedTerm.typeOf_!

  implicit val scope = new Scope(R, N, I, J)(I0 :<: I, I1 :<: I, J0 :<: J, J1 :<: J)

  implicit val env = TypeTranslation.subsorts(scope) ++
      TypeTranslation.decl(scope, Map(δ ~> typeOf_!(δ)))



  def main(args: Array[String]): Unit = {
    implicit val scope = env.scope
    new Interpreter().executeFile("/tmp/synopsis.json")
  }


  class Interpreter(implicit scope: Scope) extends Paren.BreakDown.Interpreter {
    import Paren.BreakDown.Interpreter._
    /* This part is LCS-specific */
    override def pods(implicit s: State) = {
      case (L("A"), List(~(i), ~(j))) => APod(i, j)
    }

    override def invokeProver(pod: Pod) { invokeProver(List(), pod.obligations.conjuncts, List(pod)) }
    def invokeProver(assumptions: List[Term], goals: List[Term], pods: List[Pod]=List()) {
      import synth.pods._
      import syntax.Piping._

      for (goal <- goals) extrude(goal)  |-- report.console.Console.display

      val a = new Assistant

      val toR = TotalOrderPod(R)
      val toI = TotalOrderPod(I)
      val toJ = TotalOrderPod(J)
      val idxI = IndexArithPod(I, toI.<)
      val idxJ = IndexArithPod(J, toJ.<)
      val partI = PartitionPod(I, toI.<, I0, I1)
      val partJ = PartitionPod(J, toJ.<, J0, J1)

      val p = new Prover(List(NatPod, TuplePod, toR, toI, toJ, idxI, idxJ, partI, partJ) ++ pods)

      val commits =
        for (goals <- goals map (List(_))) yield {
          //for (goals <- List(goals)) yield {
          val igoals = goals map a.intros
          import semantics.pattern.SimplePattern
          val t = new p.Transaction
          val switch = t.commonSwitch(new p.CommonSubexpressionElimination(igoals, new SimplePattern(min :@ ?)))

          t.commit(assumptions map a.simplify map t.prop, igoals map (switch(_)) map a.simplify map t.goal)
        }

      val results = commits reduce (_ ++ _)

      println("=" * 80)
      Trench.display(results, "◦")

      if (!(results.toList forall (_.root == "valid"))) System.exit(1)
    }
  }
}
