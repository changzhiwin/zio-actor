package zio.actor

import java.util.concurrent.atomic.AtomicBoolean

import zio.{ Chunk, IO, Ref, Schedule, Task, UIO, ZIO }
import zio.stream.ZStream
import zio.test._
import zio.test.Assertion._

import Actor.Stateful

object CounterUtils {
  sealed trait Message[+A]
  case object Reset                   extends Message[Unit]
  case object Increase                extends Message[Unit]
  case object Get                     extends Message[Int]
  case class IncreaseUpTo(upper: Int) extends Message[ZStream[Any, Nothing, Int]]
}

object TickUtils {
  sealed trait Message[+A]
  case object Tick extends Message[Unit]
}

object StopUtils {
  sealed trait Message[+A]
  case object Letter extends Message[Unit]
}

object ActorSpec extends ZIOSpecDefault {

  def spec = suite("Test the basic actor behavior")(
    test("Sequential message processing") {
      import CounterUtils._

      val handler = new Stateful[Any, Int, Message] {
        override def receive[A](
          state: Int,
          msg: Message[A],
          contet: Context
        ): UIO[(Int, A)] = msg match {
          case Reset               => ZIO.succeed( 0 -> () )
          case Increase            => ZIO.succeed( (state + 1) -> () )
          case Get                 => ZIO.succeed( state -> state )
          case IncreaseUpTo(upper) => ZIO.succeed( upper -> ZStream.fromIterable(state until upper) )
        }
      }

      for {
        system <- ActorSystem("test1")
        actor  <- system.make("actor1", Supervisor.none, 0, handler)
        _      <- actor ! Increase
        _      <- actor ! Increase
        c1     <- actor ? Get
        _      <- actor ! Reset
        c2     <- actor ? Get
        c3     <- actor ? IncreaseUpTo(15)
        vals   <- c3.runCollect
        c4     <- actor ? Get
      } yield assertTrue(c1 == 2, c2 == 0, vals == Chunk(0 until 15: _*), c4 == 15)
    },
    test("Error recovery by retrying") {
      import TickUtils._

      val maxRetries = 10

      def makeHandler(ref: Ref[Int]): Stateful[Any, Unit, Message] =
        new Stateful[Any, Unit, Message] {

          override def receive[A](
            state: Unit,
            msg: Message[A],
            context: Context
          ): Task[(Unit, A)] = msg match {
            case Tick => 
              ref
                .updateAndGet(_ + 1)
                .flatMap { v =>
                  if (v < maxRetries) ZIO.fail(new Exception(s"fail ${v}th time"))
                  else ZIO.succeed(state -> state)
                }
          }
        }

      for {
        ref     <- Ref.make(0)
        handler  = makeHandler(ref)
        schedule = Schedule.recurs(maxRetries)
        policy   = Supervisor.retry(schedule)
        system  <- ActorSystem("test2")
        actor   <- system.make("actor2", policy, (), handler)
        _       <- actor ? Tick
        count   <- ref.get
      } yield assertTrue(count == maxRetries)
    },
    test("Error recovery by fallback action") {
      import TickUtils._
      val handler = new Stateful[Any, Unit, Message] {
        override def receive[A](
          state: Unit,
          msg: Message[A],
          context: Context
        ): IO[Throwable, (Unit, A)] = msg match {
          case Tick => ZIO.fail(new Exception("I'm fail."))
        }
      }

      val called   = new AtomicBoolean(false)
      val schedule = Schedule.recurs(10)
      val policy   = Supervisor.retryOrElse[Any, Long](
        schedule,
        (e, _) => ZIO.log(e.getMessage) *> ZIO.succeed(called.set(true))
      )

      val program = for {
        system <- ActorSystem("test3")
        actor  <- system.make("actor3", policy, (), handler)
        _      <- actor ? Tick
      } yield ()

      assertZIO(program.exit)(fails(anything)) && assertZIO(ZIO.succeed(called.get))(isTrue)
    },
    test("Stopping actors") {
      import StopUtils._

      val handler = new Stateful[Any, Unit, Message] {
        override def receive[A](
          state: Unit,
          msg: Message[A],
          context: Context
        ): IO[Throwable, (Unit, A)] = msg match {
          case Letter => ZIO.succeed(() -> ())
        }
      }

      for {
        system <- ActorSystem("test4")
        actor  <- system.make("actor4", Supervisor.none, (), handler)
        _      <- actor ! Letter
        _      <- actor ? Letter
        dump   <- actor.stop
      } yield assertTrue(dump.size == 0)
    },
    test("Select local actor") {
      import TickUtils._
      val handler = new Stateful[Any, Unit, Message] {
        override def receive[A](
          state: Unit,
          msg: Message[A],
          context: Context
        ): IO[Throwable, (Unit, A)] = msg match {
          case Tick => ZIO.succeed(() -> ())
        }
      }

      for {
        system <- ActorSystem("test5")
        _      <- system.make("actor5", Supervisor.none, (), handler)
        actor  <- system.select[Message]("zio://test5@0.0.0.0:7005/actor5")
        _      <- actor ! Tick
        uri    <- actor.uri
      } yield assertTrue(uri == "zio://test5@0.0.0.0:7005/actor5")
    },
    test("Local actor does not exist") {
      import TickUtils._
      val handler = new Stateful[Any, Unit, Message] {
        override def receive[A](
          state: Unit,
          msg: Message[A],
          context: Context
        ): IO[Throwable, (Unit, A)] = msg match {
          case Tick => ZIO.succeed(() -> ())
        }
      }

      val program = for {
        system <- ActorSystem("test6")
        _      <- system.make("actor6", Supervisor.none, (), handler)
        actor  <- system.select[Message]("zio://test6@0.0.0.0:7006/actor5")
        _      <- actor ! Tick
      } yield ()

      assertZIO(program.exit)(
        fails(isSubtype[Throwable](anything)) &&
        fails(
          hasField[Throwable, String](
            "message",
            _.getMessage,
            equalTo("No such actor /actor5")
          )
        )
      )
    }
  )
}
