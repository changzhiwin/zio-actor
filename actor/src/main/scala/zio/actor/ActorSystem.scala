package zio.actor

import zio._

object ActorSystem {

  def apply(sysName: String, configFile: Option[File] = None): Task[ActorSystem] =
    for {
      actorRefMap  <- Ref.make(Map.empty[String, Any]) // TODO: Must Any?
      config       <- ???
      remoteConfig <- ???
      actorSystem  <- ZIO.attemp(new ActorSystem(sysName))
      _            <- ZIO.succeed(remoteConfig)       // TODO: listen port
    } yield actorSystem
}

final class Context private[actor] (
  path: String,
  actorSystem: ActorSystem,
  childrenRef: Ref[Set[ActorRef[Any]]]                 // TOOD, Any again
) {

  def self[F[+_]]: Task[ActorRef[F]] = actorSystem.select(path)

  def make[R, S, F1[+_]](
    actorName: String,
    sup: Supervisor[R],
    init: S,
    stateful: Stateful[R, S, F]
  ): ZIO[R, Throwable, ActorRef]

  def select[F1[+_]](path: String): Task[ActorRef[F1]] =

  private[actor] def actorSystemName = actorSystem.actorSystemName
}

final class ActorSystem private[actor] (
  actorSystemName: String,
) {

  def make[R, S, F[+_]](
    actorName: String,
    sup: Supervisor[R],
    init: S,
    stateful: AbstractStateful[R, S, F]
  ): RIO[R, ActorRef[F]]

  def select[F[+_]](path: String): Task[ActorRef[F]]

  def shutdown: Task[List[_]]

  private def receiveLoop(address: , port: ): Task[Unit]
}