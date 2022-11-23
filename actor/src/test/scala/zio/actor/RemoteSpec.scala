package zio.actor

import zio.actor.Actor.Stateful
import zio.actor.SpecUtils._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.{Console, IO, ZIO, durationInt}

import java.io.File
import java.net.ConnectException

object SpecUtils {
  sealed trait Message[+A]
  case class Str(value: String) extends Message[String]

  sealed trait MyErrorDomain extends Throwable
  case object DomainError    extends MyErrorDomain

  val handlerMessageTrait = new Stateful[Any, Int, Message] {
    override def receive[A](
      state: Int,
      msg: Message[A],
      context: Context,
    ): IO[MyErrorDomain, (Int, A)] =
      msg match {
        case Str(value) =>
          ZIO.succeed((state + 1, value + " received plus " + (state + 1)))
      }
  }

  // ActorRef case
  sealed trait PingPongProto[+A]
  case class Ping(sender: ActorRef[PingPongProto])        extends PingPongProto[Unit]
  case object Pong                                        extends PingPongProto[Unit]
  case class GameInit(recipient: ActorRef[PingPongProto]) extends PingPongProto[Unit]

  val protoHandler: Stateful[Any, Unit, PingPongProto] = new Stateful[Any, Unit, PingPongProto] {

    override def receive[A](
      state: Unit,
      msg: PingPongProto[A],
      context: Context,
    ): IO[Throwable, (Unit, A)] = msg match {
      case Ping(sender) =>
        for {
          uri <- sender.uri
          _   <- Console.printLine(s"Ping from: ${uri}")
          _   <- sender ! Pong
        } yield ((), ())

      case Pong =>
        for {
          _ <- Console.printLine(s"Received pong")
          _ <- ZIO.succeed(1)
        } yield ((), ())

      case GameInit(to) =>
        for {
          _    <- Console.printLine("Game starts...")
          self <- context.self[PingPongProto]
          _    <- to ! Ping(self)
        } yield ((), ())
    }
  }

  // Error case
  sealed trait ErrorProto[+A]
  case object UnsafeMessage extends ErrorProto[String]

  val errorHandler = new Stateful[Any, Unit, ErrorProto] {
    override def receive[A](
      state: Unit,
      msg: ErrorProto[A],
      context: Context,
    ): IO[Throwable, (Unit, A)] =
      msg match {
        case UnsafeMessage => ZIO.fail(new Exception("Error on remote side"))
      }
  }
}

object RemoteSpec extends ZIOSpecDefault {
  def spec =
    suite("RemoteSpec")(
      suite("Remote communication suite")(
        test("Remote test send message") {
          for {
            actorSystemOne <- ActorSystem("remote1")
            _              <- actorSystemOne.make("actor1", Supervisor.none, 0, handlerMessageTrait)
            actorSystemTwo <- ActorSystem("remote2")
            actorRef       <- actorSystemTwo.select[Message]("zio://remote1@0.0.0.0:8001/actor1")
            _              <- actorRef ? Str("any")
            result         <- actorRef ? Str("response is")
          } yield assertTrue(result == "response is received plus 2")
        },
        test("ActorRef serialization case") {
          for {
            actorSystem3 <- ActorSystem("remote3")
            _            <- actorSystem3.make("actor3", Supervisor.none, (), protoHandler)
            actorSystem4 <- ActorSystem("remote4")
            actor4       <- actorSystem4.make("actor4", Supervisor.none, (), protoHandler)
            remoteActor3 <- actorSystem4.select[PingPongProto]("zio://remote3@0.0.0.0:8003/actor3")
            _            <- remoteActor3 ? GameInit(actor4)
            _            <- TestClock.adjust(5.seconds)
            output       <- TestConsole.output
          } yield assertTrue(
            output.size == 3,
            output(0) == "Game starts...\n",
            output(1) == "Ping from: zio://remote3@0.0.0.0:8003/actor3\n",
            output(2) == "Received pong\n",
          )
        },
      ),
      suite("Error handling suite")(
        test("Remote system does not exist") {
          val program = for {
            actorSystem <- ActorSystem("remote5")
            actor       <- actorSystem.select[PingPongProto]("zio://remote55@0.0.0.0:8888/actor55")
            _           <- actor ? GameInit(actor)
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[ConnectException](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("Connection refused"))),
          )
        },
        test("Remote actor does not exist") {
          val program = for {
            actorSystem6 <- ActorSystem("remote6")
            actorSystem7 <- ActorSystem("remote7")
            actor        <- actorSystem6.select[PingPongProto]("zio://remote7@0.0.0.0:8007/actor7")
            _            <- actor ? GameInit(actor)
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[Throwable](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("No such remote actor"))),
          )
        },
        test("On remote side error message processing error") {
          val program = for {
            actorSystem1 <- ActorSystem("remote8")
            _            <- actorSystem1.make("actor8", Supervisor.none, (), errorHandler)
            actorSystem2 <- ActorSystem("remote9")
            remoteRef    <- actorSystem2.select[ErrorProto]("zio://remote8@0.0.0.0:8008/actor8")
            _            <- remoteRef ? UnsafeMessage
          } yield ()

          assertZIO(program.exit)(
            fails(isSubtype[Throwable](anything)) &&
              fails(hasField[Throwable, String]("message", _.getMessage, equalTo("Error on remote side"))),
          )
        },
      ),
    )
}
