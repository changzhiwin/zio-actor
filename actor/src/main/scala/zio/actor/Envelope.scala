package zio.actor

private[actor] final case class Envelope(command: Command, recipient: String) extends Serializable

private[actor] sealed trait Command

private[actor] object Command {
  case class Ask(msg: Any)  extends Command
  case class Tell(msg: Any) extends Command
  case object Stop          extends Command
}