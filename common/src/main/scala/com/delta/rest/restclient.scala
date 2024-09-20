package com.delta.rest

import akka.actor.ActorSystem
import com.delta.configuration.Configs
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import sttp.client3.{HttpClientFutureBackend, UriContext, basicRequest}
import sttp.model.Uri
import sttp.client3.playJson._

import javax.inject.Inject
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import scala.language.{implicitConversions, postfixOps}
import retry._

case class RestClient @Inject() (hostUrl: String)(implicit ac: ActorSystem) extends LazyLogging {

  import ac.dispatcher

  private val backend = HttpClientFutureBackend()
  private val baseRequest = basicRequest.header("Content-Type", "application/json")
  private val policy = retry.JitterBackoff(max = 5, 100 millisecond)

  def memberEndpoint(member: Member) = new MemberEndpoint(this, member)

  def post[Req, Resp](uri: Uri, req: Req)(implicit w: Writes[Req], r: Reads[Resp]): Future[Resp] = {
    policy { () =>
      logger.info(s"POST $uri ${req}")
      baseRequest.post(uri).body(req).response(asJson[Resp]).send(backend).map {
        _.body match {
          case Left(value) =>
            throw new RuntimeException(value)
          case Right(value) => value
        }
      }
    }(Success.always, ac.dispatcher)
  }
}

class MemberEndpoint(restClient: RestClient, member: Member)(implicit ac: ActorSystem) {
  implicit def ur(path: String): Uri = uri"${restClient.hostUrl}/members/${member.id}/$path"

  def appendEntry(content: AppendEntry): Future[AppendEntryResponse] =
    restClient.post[AppendEntry, AppendEntryResponse](ur("appendEntry"), content)

  def requestVote(requestVote: RequestVote)(implicit w: Writes[RequestVote]): Future[ResponseVote] =
    restClient.post[RequestVote, ResponseVote](ur("requestVote"), requestVote)
}
