package zio.actor

import zio.actor.Actor._
import zio.actor.ActorConfig._
import zio.actor.Utils._
import zio.nio.channels.AsynchronousServerSocketChannel
import zio.nio.{Buffer, InetAddress, InetSocketAddress}
import zio.{Supervisor => _, _}

object ActorSystem {

  def apply(sysName: String): Task[ActorSystem] =
    for {
      actorMap     <- Ref.make(Map.empty[String, Actor[Any]])
      remoteConfig <- ActorConfig.getRemoteConfig(sysName)
      actorSystem  <- ZIO.attempt(new ActorSystem(sysName, remoteConfig, actorMap))
      _            <- remoteConfig.fold[Task[Unit]](ZIO.logWarning("No listen port, only work locally."))(c =>
                        actorSystem.receiveLoop(c.host, c.port),
                      )
    } yield actorSystem
}

final class ActorSystem private[actor] (
  val actorSystemName: String,
  remoteConfig: Option[RemoteConfig],
  actorMap: Ref[Map[String, _ <: Actor[Any]]], // 限制value是Actor[Any]子类或本身类型
) { self =>

  def make[R, S, F[+_]](
    actorName: String,
    sup: Supervisor[R],
    initialState: S,
    stateful: AbstractStateful[R, S, F],
    parent: Option[String] = None,
  ): ZIO[R, Throwable, ActorRef[F]] = {

    for {
      map       <- actorMap.get
      actorPath <- buildAbsolutePath(parent.getOrElse(""), actorName)
      _         <- ZIO.fail(new Exception(s"Actor ${actorPath} already exists.")).when(map.contains(actorPath))

      uri          = buildActorURI(actorSystemName, actorPath, remoteConfig)
      childrenRef <- Ref.make(Set.empty[ActorRef[Any]])
      actor       <- stateful.makeActor(
                       sup,
                       new Context(uri, self, childrenRef),
                       () => self.dropFromActorMap(uri, childrenRef),
                     )(initialState)
      _           <- actorMap.update(_.concat(Map(actorPath -> actor.asInstanceOf[Actor[Any]])))
    } yield new ActorRefLocal[F](uri, actor)
  }

  // 清理死后残留
  private def dropFromActorMap(uri: String, childrenRef: Ref[Set[ActorRef[Any]]]): Task[Unit] = for {
    solved           <- resolveActorURI(uri)
    (_, _, actorPath) = solved
    _                <- actorMap.update(_ - actorPath)
    children         <- childrenRef.get
    _                <- ZIO.foreachDiscard(children)(_.stop)
    _                <- childrenRef.set(Set.empty[ActorRef[Any]])
  } yield ()

  def select[F[+_]](uri: String): Task[ActorRef[F]] = {
    for {
      solved                      <- resolveActorURI(uri)
      (sysName, remote, actorPath) = solved

      actorRef <- ZIO.ifZIO(ZIO.succeed(sysName == actorSystemName))(
                    onTrue = {
                      for {
                        map   <- actorMap.get
                        actor <- map
                                   .get(actorPath)
                                   .fold[Task[Actor[F]]](
                                     ZIO.fail(new Exception(s"No such actor ${actorPath}")),
                                   )(a => ZIO.attempt(a.asInstanceOf[Actor[F]]))
                      } yield new ActorRefLocal[F](uri, actor)
                    },
                    onFalse = {
                      for {
                        host    <- InetAddress.byName(remote.host)
                        address <- InetSocketAddress.inetAddress(host, remote.port)
                      } yield new ActorRefRemote[F](uri, address)
                    },
                  )
    } yield actorRef
  }

  def shutdown: Task[List[_]] = {
    for {
      map  <- actorMap.get
      undo <- ZIO.foreach(map.values.toList)(_.asInstanceOf[Actor[Any]].stop)
    } yield undo.flatten
  }

  private def receiveLoop(host: String, port: Int): Task[Unit] = ZIO.scoped {
    for {
      addr    <- InetAddress.byName(host)
      address <- InetSocketAddress.inetAddress(addr, port)
      p       <- Promise.make[Nothing, Unit]
      _       <- self.listenFiber(address, p).fork
      _       <- p.await
      _       <- ZIO.log(s"System(${actorSystemName}), listen at ${host}:${port}")
    } yield ()
  }

  private def listenFiber(address: InetSocketAddress, p: Promise[Nothing, Unit]): Task[Unit] = ZIO.scoped {
    for {
      channel <- AsynchronousServerSocketChannel.open
      _       <- channel.bind(Some(address))
      _       <- p.succeed(())
      _       <- workFiber(channel).forever
    } yield ()
  }

  private def workFiber(channel: AsynchronousServerSocketChannel): Task[Unit] = ZIO.scoped {
    for {
      connection <- channel.accept
      envelope   <- readFromRemote(connection).map(_.asInstanceOf[Envelope])
      map        <- actorMap.get
      actorOpt   <- resolveActorURI(envelope.receiverURI).map(tp => map.get(tp._3))
      _          <- actorOpt.fold(
                      writeToRemote(connection, Left(new Exception("No such remote actor"))),
                    )(actor =>
                      actor.unsafeOp(envelope.command).either.flatMap { eh =>
                        writeToRemote(connection, eh)
                      },
                    )
    } yield ()
  }
}
