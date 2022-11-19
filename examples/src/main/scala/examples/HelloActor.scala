package examples

import zio.{ Supervisor => _, _ }
import zio.actor.{ActorSystem, Actor, Context, Supervisor}

object HelloActor extends ZIOAppDefault {

  sealed trait Operator[+A]
  case class Add(n: Int) extends Operator[Unit]
  case object Reset      extends Operator[Unit]
  case object Result     extends Operator[Int]

  val counter = new Actor.Stateful[Any, Int, Operator] {
    override def receive[A](state: Int, msg: Operator[A], context: Context): UIO[(Int, A)] = {
      msg match {
        case Add(n) => ZIO.succeed( (state + n, ()) )
        case Reset  => ZIO.succeed( (0, ()) )
        case Result => ZIO.succeed( (state, state) )
      }
    }
  }

  def program = for {
    mainSystem  <- ActorSystem("main")
    actor       <- mainSystem.make("counter", Supervisor.none, 0, counter)
    uri         <- actor.uri
    _           <- ZIO.log(s"actor uri: ${uri}")
    _           <- actor ! Add(5)
    _           <- actor ! Add(3)
    now         <- actor ? Result
    _           <- ZIO.log(s"now result = ${now}.")
    subSystem   <- ActorSystem("sub")
    subActor    <- subSystem.select[Operator]("zio://main@0.0.0.0:7071/counter")
    result      <- subActor ? Result
    _           <- ZIO.log(s"remote result = ${result}")
    _           <- ZIO.sleep(5.second)
    _           <- subSystem.shutdown.debug("sub")
    _           <- actor.stop.debug("stop")
    _           <- mainSystem.shutdown.debug("main")
  } yield ()

  def run = program
}