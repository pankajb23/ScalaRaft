package modules

import akka.actor.ActorSystem
import com.delta.rest.RestClient
import com.google.inject.{AbstractModule, Inject, Provider, Singleton}
import play.api.libs.concurrent.ActorSystemProvider
import play.api.{Configuration, Environment}

class Modules(env: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit =
    bind(classOf[RestClient]).toProvider(classOf[RestClientProvider])
}

@Singleton
class RestClientProvider @Inject() (configuration: Configuration, ac: ActorSystemProvider)
    extends Provider[RestClient] {
  override def get(): RestClient =
    RestClient(configuration.get[String]("io.prophecy.url"))(ac.get)
}
