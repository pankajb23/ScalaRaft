package controller

import com.delta.raft.StateManager
import com.delta.raft.Utils.ac
import com.delta.rest._
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class RequestController @Inject() (
  val controllerComponents: ControllerComponents,
  restClient: RestClient
) extends BaseController
    with LazyLogging {
  implicit val dispatcher: ExecutionContextExecutor = ac.dispatcher
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

  def setup(): Action[AnyContent] = Action {
    logger.info("In setup")
    stateManager = StateManager.create(3, "group1", restClient)(ac)
    stateManager.membersMap
    Ok("setup done")
  }

  def addNewEntry(): Action[NewDataEntry] = Action(parse.json[NewDataEntry]).async {
    implicit request =>
      stateManager.writeRequest(request.body).transformWith {
        case Success(value)     => Future(Ok(Json.toJson(value)))
        case Failure(exception) => Future.failed(exception)
      }
  }

  def persistLogs(): Action[AnyContent] = Action {
    stateManager.persistLogs()
    Ok("persistLogs")
  }

  def healthCheck(): Action[AnyContent] = Action {
    Ok("healthCheck")
  }

  def getLeader: Action[AnyContent] = Action {
    Ok("getLeader")
  }

  def killLeader: Action[AnyContent] = Action {
    stateManager.killLeader()
    Ok("killLeader")
  }
 
  def killMember: Action[AnyContent] = Action {
    Ok("killMember")
  }
}
