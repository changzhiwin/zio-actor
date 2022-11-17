package zio.actor

import zio._

object Actor {

  private[actor] type AsyncMessage[F[_], A] = (F[A], Promise[Throwable, A])

  private[actor] trait AbstractStateful[R, S, -F[+_]] {

    def makeActor(
      supervisor: Supervisor[R],
      context: Context,
      optOutActorSystem: () => Task[Unit],
      mailboxSize: Int = 10000
    )(init: S): RIO[R, Actor[F]]

  }

  trait Stateful[R, S, -F[+_]] extends AbstractStateful[R, S, F] {

    // 应用逻辑层需要实现的，响应各种消息的逻辑
    // state是状态转换，传递的对象
    def receive[A](state: S, msg: F[A], context: Context): RIO[R, (S, A)]

    final override def makeActor(
      supervisor: Supervisor[R],
      context: Context,
      optOutActorSystem: () => Task[Unit],
      mailboxSize: Int = 10000
    )(init: S): RIO[R, Actor[F]] = {

      for {
        state <- Ref.make(init)
        queue <- Queue.bounded[AsyncMessage[F, _]](mailboxSize)
        _     <- queue.take.flatMap { msg =>
                   process(msg, state, supervisor, context)
                 }.forever.fork
      } yield new Actor[F](queue)(optOutActorSystem)
    }

    private def process[A](
        msg: AsyncMessage[F, A], 
        state: Ref[S], 
        supervisor: Supervisor[R],
        context: Context): UIO[R, Unit] = for {
      //TODO
      s <- state.get
      (fa, promise) = msg
      _ <- receive(s, fa, context).fold(
             e => {},
             sa => state.update(sa._1) *> promise.success(sa._2)
           )
    } yield ()

  }

}

private[actor] final class Actor[-F[+_]](
  queue: Queue[AsyncMessage]
)(optOutActorSystem: () => Task[Unit]) {

  // 请求 & 响应
  def ?[A](fa: F[A]): Task[A] = for {
    promise <- Promise.make[Throwable, A]
    _       <- queue.offer(fa -> promise)
    value   <- promise.await
  } yield value

  // 请求 & 不管
  def !(fa: F[A]): Task[Unit] = for {
    promise <- Promise.make[Throwable, A]
    _       <- queue.offer(fa -> promise)
  } yield ()

  val stop: Task[Chunk[_]] = for {
    tall <- queue.takeAll
    _    <- queue.shutdown
    _    <- optOutActorSystem()
  } yield tall
}