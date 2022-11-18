package zio.actor

import java.io.ObjectOutputStream
import java.io.ObjectInputStream

import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.{ InetAddress, InetSocketAddress }
import zio._

sealed trait ActorRef[-F[+_]] extends Serializable {

  def ?[A](fa: F[A]): Task[A]

  def ![A](fa: F[A]): Task[Unit]

  val uri: UIO[String]

  val stop: Task[Chunk[_]]
}

private[actor] sealed abstract class ActorRefSerial[-F[+_]](private var uri: String) extends ActorRef[F] {

  import Utils._

  def writeObject(out: ObjectOutputStream): Unit =
    out.writeObject(uri)

  def readObject(in: ObjectInputStream):Unit = {
    val rawValue = in.readObject()
    uri = rawValue.asInstanceOf[String]
  }

  def readResolve(): Object = {
    val remoteRefZIO = for {
      resolved      <- resolveActorURI(uri)
      (_, remote, _) = resolved
      host          <- InetAddress.byName(remote.host)
      address       <- InetSocketAddress.inetAddress(host, remote.port)
    } yield new ActorRefRemote[F](actorPath, address)

    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(remoteRefZIO).getOrThrowFiberFailure()
    }
  }

  override val uri: UIO[String] = ZIO.succeed(uri)
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

  import Utils._

  override def ?[A](fa: F[A]): Task[A] = sendEnvelope[A](Command.Ask(fa))

  override def ![A](fa: F[A]): Task[Unit] = sendEnvelope[Unit](Command.Tell(fa))

  override val stop: Task[Chunk[_]] = sendEnvelope[Chunk[_]](Command.Stop) 
  
  private def sendEnvelope[A](command: Command): Task[A] =
    ZIO.scoped {
      for {
        client   <- AsynchronousSocketChannel.open
        response <- for {
                      _           <- client.connect(address)
                      receiverURI <- uri
                      _           <- writeToRemote(client, Envelope(command, receiverURI))
                      response    <- readFromRemote(client)
                    } yield response.asInstanceOf[Either[Throwable, A]]   // 因为写的时候都转化成Either对象
        result   <- ZIO.fromEither(response)
      } yield result
    }
}