package zio.actor

// 屏蔽ZIO内置的
import zio.{ Supervisor => _, _ }

private[actor] trait Supervisor[-R] {
  def supervise[R0 <: R, A](zio: RIO[R0, A], error: Throwable): ZIO[R0, Unit, A]
}

object Supervisor {

  final def none: Supervisor[Any] = retry(Schedule.once)

  final def retry[R, A](policy: Shedule[R, Throwable, A]): Supervisor[R] =
    retryOrElse(policy, (_: Throwable, _: A) => ZIO.unit)

  final def retryOrElse[R, A](
    policy: Schedule[R, Throwable, A],
    orElse: (Throwable, A) => URIO[R, Unit]
  ): Supervisor[R] =
    new Supervisor[R] {
      override def supervise[R0 <: R, A](zio: RIO[R0, A], error: Throwable): ZIO[R0, Unit, A] =
        zio
          .retryOrElse(policy, (e: Throwable, a: A) => orElse(e, a) *> ZIO.fail(error))
          .mapError(_ => ())
    }
}