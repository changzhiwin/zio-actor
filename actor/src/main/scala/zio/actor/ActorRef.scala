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

  protected def writeObject0(out: ObjectOutputStream): Unit =
    out.writeObject(actorPath)

  protected def readObject0(in: ObjectInputStream):Unit = {
    val rawActorPath = in.readObject()
    actorPath = rawActorPath.asInstanceOf[String]
  }

  // 需要解析地址
  protected def readResolve0(): Object = {
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

}

private[actor] final class ActorRefRemote[-F[+_]](
  actorName: String,
  address: InetSocketAddress
) extends ActorRefSerial[F](actorName) {
  
}