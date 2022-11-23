package zio.actor

import zio.actor.Actor.AsyncMessage
import zio.actor.Command.{Ask, Stop, Tell}
import zio.{Supervisor => _, _}

object Actor {

  private[actor] type AsyncMessage[F[_], A] = (F[A], Promise[Throwable, A])

  private[actor] trait AbstractStateful[R, S, -F[+_]] {

    private[actor] def makeActor(
      supervisor: Supervisor[R],
      context: Context,
      optOutActorSystem: () => Task[Unit],
      mailboxSize: Int = 10000,
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
      mailboxSize: Int = 10000,
    )(init: S): RIO[R, Actor[F]] = {

      def process[A](
        msg: AsyncMessage[F, A],
        state: Ref[S],
      ): RIO[R, Unit] = {
        for {
          s <- state.get

          (fa, promise) = msg
          // 缓存了这个ZIO，后面用到了两个地方。
          receiver      = receive(s, fa, context)
          // tupled是一个函数参数转换：从 (s, a) => {} 到 sa => {}，两个参数变成一个参数
          completer     = ((s: S, a: A) => state.set(s) *> promise.succeed(a)).tupled
          _            <- receiver.foldZIO(
                            e => {
                              // ZIO.log(e.getMessage) *>
                              supervisor
                                .supervise(receiver, e)
                                .foldZIO(ee => promise.fail(ee), completer)
                            },
                            completer,
                          )
        } yield ()
      }

      for {
        state <- Ref.make(init)
        queue <- Queue.bounded[AsyncMessage[F, _]](mailboxSize)
        _     <- queue.take.flatMap { msg =>
                   process(msg, state)
                 }.forever.fork
      } yield new Actor[F](queue)(optOutActorSystem)
    }

    // 很奇怪，process放在外面定义，会导致编译错误；放到里面定义就可以
    /*
    private def process0[A](
      msg: AsyncMessage[F, A],
      state: Ref[S],
      supervisor: Supervisor[R],
      context: Context
    ): RIO[R, Unit] = {
      for {
        s <- state.get

        (fa, promise) = msg
        _ <- receive(s, fa, context).fold(
              e => ZIO.log(s"${e}") *> promise.fail(e) *> ZIO.unit,
              sa => state.set(sa._1) *> promise.succeed(sa._2) *> ZIO.unit
            )
      } yield ()
    }
     */
  }

}

// 注意private声明，class Actor本身是不对外暴漏的
private[actor] final class Actor[-F[+_]](
  queue: Queue[AsyncMessage[F, _]],
)(optOutActorSystem: () => Task[Unit]) {

  // 请求 & 响应
  def ?[A](fa: F[A]): Task[A] = for {
    promise <- Promise.make[Throwable, A]
    _       <- queue.offer(fa -> promise)
    value   <- promise.await
  } yield value

  // 请求 & 不管
  def ![A](fa: F[A]): Task[Unit] = for {
    promise <- Promise.make[Throwable, A]
    _       <- queue.offer(fa -> promise)
  } yield ()

  val stop: Task[Chunk[_]] = for {
    tall <- queue.takeAll
    _    <- queue.shutdown
    _    <- optOutActorSystem()
  } yield tall

  def unsafeOp(command: Command): Task[Any] = command match {
    case Ask(msg)  => this ? msg.asInstanceOf[F[_]]
    case Tell(msg) => this ! msg.asInstanceOf[F[_]]
    case Stop      => this.stop
  }
}
