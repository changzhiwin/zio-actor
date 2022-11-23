package zio.actor

// 屏蔽ZIO内置的
import zio.{Supervisor => _, _}

private[actor] trait Supervisor[-R] {
  def supervise[R0 <: R, A](zio: RIO[R0, A], error: Throwable): RIO[R0, A]
}

object Supervisor {

  final def none: Supervisor[Any] = retry(Schedule.once)

  final def retry[R, S](policy: Schedule[R, Throwable, S]): Supervisor[R] =
    retryOrElse(policy, (e: Throwable, _: S) => ZIO.log(e.getMessage) *> ZIO.unit)

  final def retryOrElse[R, S](
    policy: Schedule[R, Throwable, S],
    orElse: (Throwable, S) => URIO[R, Unit],
  ): Supervisor[R] =
    new Supervisor[R] {
      override def supervise[R0 <: R, A](zio: RIO[R0, A], error: Throwable): RIO[R0, A] =
        zio.retryOrElse(policy, (e: Throwable, s: S) => orElse(e, s) *> ZIO.fail(error))

      // 特别说明：retryOrElse的返回类型是有第二个参数orElse确定的，看函数签名，如下：
      // retryOrElse[R1 <: R, A1 >: A, S, E1](policy: => Schedule[R1, E, S], orElse: (E, S) => ZIO[R1, E1, A1]): ZIO[R1, E1, A1]
    }
}
