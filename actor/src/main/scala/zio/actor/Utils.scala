package zio.actor

import zio._
import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.{Buffer, InetAddress, InetSocketAddress}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.ByteBuffer

private[actor] object Utils {

  import ActorConfig._

  private val RegexName =
    "[\\w+|\\d+|(\\-_.*$+:@&=,!~';.)|\\/]+".r

  private val RegexAcotrURI =
    "^(?:zio:\\/\\/)(\\w+)[@](\\d+\\.\\d+\\.\\d+\\.\\d+)[:](\\d+)[/]([\\w+|\\d+|\\-_.*$+:@&=,!~';.|\\/]+)$".r

  def resolveActorURI(uri: String): Task[(String, RemoteConfig, String)] =
    RegexAcotrURI.findFirstMatchIn(uri) match {
      case Some(value) if value.groupCount == 4 => {
        val actorSystemName = value.group(1)
        val host            = value.group(2)
        val port            = value.group(3).toInt
        val actorName       = "/" + value.group(4)
        ZIO.succeed((actorSystemName, RemoteConfig(host, port), actorName))
      }
      case _                                    =>
        ZIO.fail(
          new java.lang.Exception(
            "Invalid Actor URI, must [zio://YOUR_ACTOR_SYSTEM_NAME@HOST:PORT/RELATIVE_ACTOR_PATH]",
          ),
        )
    }

  def buildAbsolutePath(parentActorName: String, actorName: String): Task[String] =
    actorName match {
      case ""            => ZIO.fail(new Exception("Actor actor must not be empty"))
      case null          => ZIO.fail(new Exception("Actor actor must not be null"))
      case RegexName(_*) => ZIO.succeed(parentActorName + "/" + actorName)
      case _             => ZIO.fail(new Exception(s"Invalid actor name provided ${actorName}."))
    }

  def buildActorURI(actorSystemName: String, actorPath: String, remoteConfig: Option[RemoteConfig]): String =
    s"zio://${actorSystemName}@${remoteConfig.fold("0.0.0.0:0000")(c => c.host + ":" + c.port)}${actorPath}"

  def objFromByteArray(bytes: Array[Byte]): Task[Any] =
    ZIO.scoped {
      ZIO
        .fromAutoCloseable(
          ZIO.attempt(
            new ObjectInputStream(new ByteArrayInputStream(bytes)),
          ),
        )
        .flatMap { s =>
          ZIO.attempt(s.readObject())
        }
    }

  def readFromRemote(socket: AsynchronousSocketChannel): Task[Any] =
    for {
      size      <- socket.readChunk(4)
      buffer    <- Buffer.byte(size)
      intBuffer <- buffer.asIntBuffer
      toRead    <- intBuffer.get(0)
      content   <- socket.readChunk(toRead)
      bytes      = content.toArray
      obj       <- objFromByteArray(bytes)
    } yield obj

  def objToByteArray(obj: Any): Task[Array[Byte]] =
    for {
      stream <- ZIO.succeed(new ByteArrayOutputStream())
      bytes  <- ZIO.scoped {
                  ZIO
                    .fromAutoCloseable(
                      ZIO.attempt(
                        new ObjectOutputStream(stream),
                      ),
                    )
                    .flatMap { s =>
                      ZIO.attempt(s.writeObject(obj)) *> ZIO.succeed(stream.toByteArray)
                    }
                }
      _      <- ZIO.attempt(stream.close())
    } yield bytes

  def writeToRemote(socket: AsynchronousSocketChannel, obj: Any): Task[Unit] =
    for {
      bytes <- objToByteArray(obj)
      _     <- socket.writeChunk(Chunk.fromArray(ByteBuffer.allocate(4).putInt(bytes.length).array()))
      _     <- socket.writeChunk(Chunk.fromArray(bytes))
    } yield ()
}
