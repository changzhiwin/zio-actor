package zio.actor

import zio._
import java.io.ObjectOutputStream
import java.io.ObjectInputStream

sealed trait ActorRef[-F[+_]] extends Serializable {

  def ?[A](fa: F[A]): Task[A]

  def ![A](fa: F[A]): Task[Unit]

  val path: UIO[String]

  val stop: Task[Chunk[_]]
}

private[actor] sealed abstract class ActorRefSerial[-F[+_]](private var actorPath: String) extends ActorRef[F] {

  def writeObject(out: ObjectOutputStream): Unit =
    out.writeObject(actorPath)

  def readObject(in: ObjectInputStream):Unit = {
    val rawActorPath = in.readObject()
    actorPath = rawActorPath.asInstanceOf[String]
  }

  // 需要解析地址
  def readResolve(): Object = {
    val remoteRef = for {
      resolved           <- ???(actorPath)
      (_, addr, port, _) = resolved
      ip                 <- InetAddress.byName()
      address            <- InetSocketAddress.inetAddress(ip, port)
    } yield new ActorRefRemote[F](actorPath, address)

    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(remoteRef).getOrThrowFiberFailure()
    }
  }

  override val path: UIO[String] = ZIO.succeed(actorPath)
}

private[actor] final class ActorRefLocal[-F[+_]](
  actorName: String,
  actor: Actor[F]
) extends ActorRefSerial[F](actorName) {

  override def ?[A](fa: F[A]): Task[A] = actor ? fa

  override def ![A](fa: F[A]): Task[Unit] = actor ! fa

  override val stop: Task[Chunk[_]] = actor.stop
}

private[actor] final class ActorRefRemote[-F[+_]](
  actorName: String,
  address: InetSocketAddress
) extends ActorRefSerial[F](actorName) {

  override def ?[A](fa: F[A]): Task[A] = sendEnvelope[A](Command.Ask(fa))

  override def ![A](fa: F[A]): Task[Unit] = sendEnvelope[Unit](Command.Tell(fa))

  override val stop: Task[Chunk[_]] = sendEnvelope[Chunk[_]](Command.Stop) 
  
  private def sendEnvelope[A](command: Command): Task[A] =
    ZIO.scoped {
      for {
        client   <- AsynchronousSocketChannel.open
        response <- for {
                      _         <- client.connect(address)
                      actorPath <- path
                      _         <- writeToRemote(client, Envelope(command, actorPath))
                      response  <- readFromRemote(client)
                    } yield response.asInstanceOf[Either[Throwable, A]]
        result   <- ZIO.fromEither(response)
      } yield result
    }
}