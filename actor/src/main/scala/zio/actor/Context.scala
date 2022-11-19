package zio.actor

import zio.{ Supervisor => _, _ }
import Utils._
import Actor.Stateful

final class Context private[actor] (
  uri: String,
  actorSystem: ActorSystem,
  childrenRef: Ref[Set[ActorRef[Any]]]
) {

  def self[F[+_]]: Task[ActorRef[F]] = actorSystem.select(uri)

  def make[R, S, F1[+_]](
    actorName: String,
    sup: Supervisor[R],
    initialState: S,
    stateful: Stateful[R, S, F1]
  ): ZIO[R, Throwable, ActorRef[F1]] = for {
    solved   <- resolveActorURI(uri)             // (systemName, remote, actorPath)
    actorRef <- actorSystem.make(actorName, sup, initialState, stateful, Some(solved._3))
    _        <- childrenRef.update(_ + actorRef.asInstanceOf[ActorRef[Any]])
  } yield actorRef

  def select[F1[+_]](actorURI: String): Task[ActorRef[F1]] = actorSystem.select(actorURI)

  private[actor] def actorSystemName = actorSystem.actorSystemName
}