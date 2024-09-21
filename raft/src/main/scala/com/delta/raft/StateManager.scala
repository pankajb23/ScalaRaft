package com.delta.raft

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.delta.rest.{Member, ReplicaGroup, RestClient}
import com.delta.raft.Utils.ac
import com.typesafe.scalalogging.LazyLogging

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object StateManager {
  def create(maxReplica: Int, groupId: String, restClient: RestClient): StateManager =
    new StateManager(maxReplica, groupId: String, restClient)
}

case class StateManager(maxReplica: Int, groupId: String, restClient: RestClient)
    extends LazyLogging {
  implicit val timer: Timeout = akka.util.Timeout(5 seconds)

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

  def routeRequest(memberId: String, msg: Any): Future[Any] = {
    membersMap.get(memberId) match {
      case Some(actor) => actor.ask(msg)
      case None        => Future.successful("No actor found")
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
}
