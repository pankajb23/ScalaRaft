import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import play.api.test.Injecting

import scala.concurrent.duration.DurationInt

class TestSpec extends PlaySpec with GuiceOneServerPerSuite with Injecting {
  override def fakeApplication(): Application =
    new GuiceApplicationBuilder().configure("play.http.router" -> "router.Routes").build()

  lazy val wsClient = inject[WSClient]

  "Application " should {
    "starts successfully" in {
      val response = await(
        wsClient.url(s"http://localhost:$port/healthCheck").withRequestTimeout(10.seconds).get()
      )
      response.status mustBe 200
    }
  }
}
