package controller

import com.delta.raft.StateManager
import com.delta.raft.Utils.ac
import com.delta.rest._
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class RequestController @Inject() (val controllerComponents: ControllerComponents)
    extends BaseController
    with LazyLogging {
  implicit val dispatcher = ac.dispatcher
  logger.info("java klass path " + System.getProperty("java.class.path"))
  val restClient = RestClient("http://localhost:9000")(ac)
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
      stateManager.routeRequest(memberId, request.body).transformWith {
        case Success(value) =>
          println("value " + value)
          logger.info("value " + value)
          Future(Ok(Json.toJson(value.asInstanceOf[ResponseVote])))
        case Failure(exception) => Future.failed(exception)
      }
  }

  def setup(): Action[AnyContent] = Action {
    logger.info("In setup")
    stateManager = StateManager.props(3, "group1", restClient)
    stateManager.membersMap
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
