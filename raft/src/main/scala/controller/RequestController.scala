package controller

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import com.delta.raft.StateManager
import com.delta.rest.{
  AppendEntry,
  AppendEntryResponse,
  Initialize,
  RequestVote,
  ResponseVote,
  RestClient
}
import com.google.inject.Inject
import controller.RequestController.actorSystem
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object RequestController {
  val actorSystem = ActorSystem("raft")
}
class RequestController @Inject() (val controllerComponents: ControllerComponents)
    extends BaseController {
  import RequestController.actorSystem.dispatcher
  val restClient = RestClient("http://localhost:9000")(actorSystem)
  var stateManager: StateManager = _

  def appendEntry(memberId: String): Action[AppendEntry] = Action(parse.json[AppendEntry]).async {
    implicit request =>
      stateManager.routeRequest(memberId, request.body).mapTo[AppendEntryResponse].transformWith {
        case Success(value)     => Future(Ok(Json.toJson(value)))
        case Failure(exception) => Future.failed(exception)
      }
  }

  def requestVote(memberId: String): Action[RequestVote] = Action(parse.json[RequestVote]).async {
    implicit request =>
      stateManager.routeRequest(memberId, request.body).mapTo[ResponseVote].transformWith {
        case Success(value)     => Future(Ok(Json.toJson(value)))
        case Failure(exception) => Future.failed(exception)
      }
  }

  def setup(maxReplicaSize: Int): Action[AnyContent] = Action {
    stateManager = StateManager.props(5, "group1", restClient)
    stateManager.initialize()
    Ok("setup done")
  }

  def getLeader: Action[AnyContent] = Action {
    Ok("getLeader")
  }

  def killLeader: Action[AnyContent] = Action {
    Ok("killLeader")
  }

  def killMember: Action[AnyContent] = Action {
    Ok("killMember")
  }
}
