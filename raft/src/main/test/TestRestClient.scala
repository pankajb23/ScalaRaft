import akka.actor.ActorSystem
import com.delta.rest.RestClient
import sttp.client3.UriContext
import sttp.model.Uri

import scala.concurrent.Future

object TestRestClient {
  implicit val ac: ActorSystem = ActorSystem("test")
  def apply(): TestRestClient = new TestRestClient()
}
class TestRestClient()(implicit ac: ActorSystem) {
  implicit def uri(path: String): Uri = uri"http:localhost:9000/$path"

  def initialize(): Future[String] = {
    val restClient = RestClient("http://localhost:9000")
    restClient.post[String, String](uri"initialize", "")
  }
}
