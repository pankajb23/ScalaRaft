package com.delta.rest

import akka.actor.ActorSystem
import com.delta.configuration.Configs
import play.api.libs.json.JsValue
import sttp.client3.{HttpClientFutureBackend, UriContext}
import sttp.model.Uri

import javax.inject.Inject
import scala.concurrent.Future
import scala.language.implicitConversions

case class RestClient @Inject() (configs: Configs)(implicit ac: ActorSystem) {
  val backend = HttpClientFutureBackend()

  def memberEndpoint(member: Member) = new MemberEndpoint(this, member)
}

class MemberEndpoint(restClient: RestClient, member: Member)(implicit ac: ActorSystem) {
  implicit def uri(path: String): Uri = uri"${restClient.configs.hostUrl}/members/${member.id}"

  def ping(content: JsValue): Future[Unit] =
    Future.successful(())

  def pong(member: Member, content: JsValue): Unit = {}
}
