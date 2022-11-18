package zio.actor

import zio.config.ConfigDescriptor
import zio.config.ConfigDescriptor._
import zio.config.typesafe.TypesafeConfig
import zio._

private[actor] object ActorConfig {

  final case class RemoteConfig(host: String, port: Int)

  val remoteConfig: ConfigDescriptor[Option[RemoteConfig]] =
    nested("remoting") {
      (string("host") zip int("port")).to[RemoteConfig]
    }.optional

  private def prefixSystemConfig[T](systemName: String, configT: ConfigDescriptor[T]) =
    nested(systemName) {
      nested("zio") {
        nested("actor")(configT)
      }
    }

  def getConfig[T : Tag](
    systemName: String,
    configDescriptor: ConfigDescriptor[T]
  ): Task[T] =
    ZIO.service[T].provideLayer(
      TypesafeConfig.fromResourcePath(prefixSystemConfig(systemName, configDescriptor))
    )

  def getRemoteConfig(systemName: String): Task[Option[RemoteConfig]] =
    getConfig(systemName, remoteConfig)
}