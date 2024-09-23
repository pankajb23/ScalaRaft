package com.delta.raft

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import com.delta.rest.{FindLeader, LeaderKnown, Member, PersistLogs, ReplicaGroup, RequestWritten, ResponseVote, RestClient}
import com.delta.rest.LeaderKnown.LeaderFound
import com.typesafe.scalalogging.LazyLogging

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

object StateManager {
  def create(maxReplica: Int, groupId: String, restClient: RestClient)(implicit
    ac: ActorSystem
  ): StateManager =
    new StateManager(maxReplica, groupId: String, restClient)(ac)
}

class StateManager(maxReplica: Int, groupId: String, restClient: RestClient)(implicit
  ac: ActorSystem
) extends LazyLogging {
  implicit val timer: Timeout = akka.util.Timeout(5 seconds)
  import ac.dispatcher

  lazy val membersMap: Map[String, ActorRef] = {
    val members = (1 to maxReplica).toList.map(x => UUID.randomUUID().toString.replaceAll("-", "_"))
    members.map { m =>
      val actor = ac.actorOf(
        Server.props(m, restClient),
        m
      )
      logger.info(s"Initializing actor with id $m")
      actor ! ReplicaGroup(members.map(x => Member(x)), m, groupId, members.size)
      m -> actor
    }.toMap
  }

  // only leaders can accept new entry
  def writeRequest(msg: Any): Future[RequestWritten] = {
    val memberToAsk = membersMap.head._2
    logger.info(s"Writing request to member ${memberToAsk.path.toStringWithoutAddress}")
    memberToAsk
      .ask(FindLeader)
      .mapTo[LeaderKnown]
      .flatMap {
        case LeaderFound(hostId) =>
          membersMap.get(hostId) match {
            case Some(actor) => actor.ask(msg).mapTo[RequestWritten]
            case None        => Future.failed(new Exception(s"No actor found with id ${hostId}"))
          }

        case _ => Future.failed(new Exception("No leader found"))
      }
      .transformWith {
        case Success(value) => Future(value)
        case Failure(exception) =>
          logger.error(
            s"Error in writeRequest for member ${memberToAsk.path.toStringWithoutAddress}",
            exception
          )
          Future.failed(exception)
      }
  }

  def killLeader() = {
    val memberToAsk = membersMap.head._2
    memberToAsk
      .ask(FindLeader)
      .mapTo[LeaderKnown]
      .foreach {
        case LeaderFound(hostId) =>
          membersMap.get(hostId) match {
            case Some(actor) => actor ! PoisonPill
            case None        => Future.failed(new Exception(s"No actor found with id ${hostId}"))
          }

        case _ => Future.failed(new Exception("No leader found"))
      }
  }

  def routeRequest(memberId: String, msg: Any): Future[Any] = {
    membersMap.get(memberId) match {
      case Some(actor) => actor.ask(msg)
      case None =>
        Future.failed(new Exception(s"No actor found for member ${memberId} for message ${msg}"))
    }
  }

  def killMember(memberId: String) = {
    membersMap.get(memberId) match {
      case Some(actor) =>
        ac.stop(actor)
        membersMap - memberId
      case None => println("No actor found")
    }
  }

  def persistLogs(): Unit = {
    membersMap.foreach {
      case (_, actor) =>
        actor ! PersistLogs
    }
  }
}
