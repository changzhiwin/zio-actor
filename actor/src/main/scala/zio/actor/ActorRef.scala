package zio.actor

import java.io.{ IOException, ObjectStreamException, ObjectOutputStream, ObjectInputStream }

import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.{ InetAddress, InetSocketAddress }
import zio._

sealed trait ActorRef[-F[+_]] extends Serializable {

  def ?[A](fa: F[A]): Task[A]

  def ![A](fa: F[A]): Task[Unit]

  val uri: UIO[String]

  val stop: Task[Chunk[_]]
}

private[actor] sealed abstract class ActorRefSerial[-F[+_]](private var fullName: String) extends ActorRef[F] {

  import Utils._

  @throws[IOException]
  def writeObject1(out: ObjectOutputStream): Unit =
    out.writeObject(fullName)

  @throws[IOException]
  def readObject1(in: ObjectInputStream):Unit = {
    val rawValue = in.readObject()
    fullName = rawValue.asInstanceOf[String]
  }

  @throws[ObjectStreamException]
  def readResolve1(): Object = {
    val remoteRefZIO = for {
      resolved      <- resolveActorURI(fullName)
      (_, remote, _) = resolved
      host          <- InetAddress.byName(remote.host)
      address       <- InetSocketAddress.inetAddress(host, remote.port)
    } yield new ActorRefRemote[F](fullName, address)

    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(remoteRefZIO).getOrThrowFiberFailure()
    }
  }

  override val uri: UIO[String] = ZIO.succeed(fullName)
}

private[actor] final class ActorRefLocal[-F[+_]](
  actorName: String,
  actor: Actor[F]
) extends ActorRefSerial[F](actorName) {

  override def ?[A](fa: F[A]): Task[A] = actor ? fa

  override def ![A](fa: F[A]): Task[Unit] = actor ! fa

  override val stop: Task[Chunk[_]] = actor.stop

  @throws[IOException]
  private def writeObject(out: ObjectOutputStream): Unit =
    super.writeObject1(out)

  @throws[IOException]
  private def readObject(in: ObjectInputStream): Unit =
    super.readObject1(in)

  @throws[ObjectStreamException]
  private def readResolve(): Object =
    super.readResolve1()
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
                    } yield response.asInstanceOf[Either[Throwable, A]]   // 因为发送端都转化成Either对象
        result   <- ZIO.fromEither(response)
      } yield result
    }

  @throws[IOException]
  private def writeObject(out: ObjectOutputStream): Unit =
    super.writeObject1(out)

  @throws[IOException]
  private def readObject(in: ObjectInputStream): Unit =
    super.readObject1(in)

  @throws[ObjectStreamException]
  private def readResolve(): Object =
    super.readResolve1()
}