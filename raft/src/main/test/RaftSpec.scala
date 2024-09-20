import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.TestServer

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class RaftSpec extends AnyFunSpec with Matchers with GuiceOneServerPerSuite {
  it("initialize") {
    startRaftCluster(5)
    val testRestClient = TestRestClient()
    val response = testRestClient.initialize()
    Await.result(response, 10 seconds) mustBe "initialized"
  }

  def startRaftCluster(nodeCount: Int): Unit = {
    val basePort = 19001 // Choose a base port number
    val ports = (basePort until basePort + nodeCount).toList

    val servers = ports.zipWithIndex.map {
      case (port, index) =>
        val config = Map(
          "play.server.http.port" -> 9000,
          "raft.node.id" -> index,
          "raft.peers" -> ports.filter(_ != port).mkString(",")
        )

        val app: Application = new GuiceApplicationBuilder()
          .configure(config)
          .build()

        TestServer(port, app)
    }

    // Start all servers
    servers.foreach(_.start())

    // Create clients for each server

  }
}
