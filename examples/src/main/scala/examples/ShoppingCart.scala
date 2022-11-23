package examples

import zio.actor.{Actor, ActorSystem, Context, Supervisor}
import zio.{Supervisor => _, _}

import java.time.Instant

object ShoppingCart extends ZIOAppDefault {

  // 业务模型
  final case class State(items: Map[String, Int], checkoutDate: Option[Instant]) {

    def isCheckedOut: Boolean =
      checkoutDate.isDefined

    def hasItem(itemId: String): Boolean =
      items.contains(itemId)

    def isEmpty: Boolean =
      items.isEmpty

    def updateItem(itemId: String, quantity: Int): State =
      quantity match {
        case 0 => copy(items = items - itemId)
        case _ => copy(items = items + (itemId -> quantity))
      }

    def removeItem(itemId: String): State =
      copy(items = items - itemId)

    def checkout(now: Instant): State =
      copy(checkoutDate = Some(now))

    def toSummary: Summary =
      Summary(items, isCheckedOut)
  }
  object State {
    val empty = State(items = Map.empty, checkoutDate = None)
  }

  // 响应的命令
  sealed trait Command[+A]
  final case class AddItem(itemId: String, quantity: Int)            extends Command[Confirmation]
  final case class RemoveItem(itemId: String)                        extends Command[Confirmation]
  final case class AdjustItemQuantity(itemId: String, quantity: Int) extends Command[Confirmation]
  final case object Checkout                                         extends Command[Confirmation]
  final case object Get                                              extends Command[Summary]

  // 命令的返回类型
  final case class Summary(items: Map[String, Int], checkedOut: Boolean)
  sealed trait Confirmation
  final case class Accepted(summary: Summary) extends Confirmation
  final case class Rejected(reason: String)   extends Confirmation

  val shopping = new Actor.Stateful[Any, State, Command] {
    override def receive[A](state: State, msg: Command[A], context: Context): UIO[(State, A)] = {
      msg match {
        case AddItem(itemId, quantity)            => {
          val st      = state.updateItem(itemId, quantity)
          val summary = st.toSummary
          ZIO.succeed((st, Accepted(summary)))
        }
        case RemoveItem(itemId)                   => {
          val st      = state.removeItem(itemId)
          val summary = st.toSummary
          ZIO.succeed((st, Accepted(summary)))
        }
        case AdjustItemQuantity(itemId, quantity) => {
          val state1  = state.removeItem(itemId)
          val state2  = state1.updateItem(itemId, quantity)
          val summary = state2.toSummary
          ZIO.succeed((state2, Accepted(summary)))
        }
        case Checkout                             => {
          val st      = state.checkout(Instant.now())
          val summary = st.toSummary
          ZIO.succeed((st, Accepted(summary)))
        }
        case Get                                  => {
          ZIO.succeed((state, state.toSummary))
        }
      }
    }
  }

  def run = for {
    mainSystem <- ActorSystem("main")
    actor      <- mainSystem.make("cart", Supervisor.none, State.empty, shopping)
    _          <- actor.uri.debug("uri")
    apple2     <- actor ? AddItem("apple", 2)
    _          <- ZIO.debug(apple2)
    banana     <- actor ? AddItem("banana", 8)
    _          <- ZIO.debug(banana)
    adjust     <- actor ? AdjustItemQuantity("apple", 5)
    _          <- ZIO.debug(adjust)
    checkout   <- actor ? Checkout
    _          <- ZIO.debug(checkout)
    summary    <- actor ? Get
    _          <- ZIO.debug(summary)
  } yield ()
}
