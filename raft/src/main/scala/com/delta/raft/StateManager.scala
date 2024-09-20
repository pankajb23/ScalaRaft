package com.delta.raft

import akka.actor.{Actor, Props}
import akka.pattern.ask
import com.delta.rest.{Initialize, Member, ReplicaGroup, RestClient}
import com.delta.raft.Utils.ac
import com.typesafe.scalalogging.LazyLogging

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object StateManager {
  def props(maxReplica: Int, groupId: String, restClient: RestClient): StateManager =
    new StateManager(maxReplica, groupId: String, restClient)
}

case class StateManager(maxReplica: Int, groupId: String, restClient: RestClient)
    extends LazyLogging {

  lazy val membersMap = initialize()
  private def initialize() = {

    val members = for {
      i <- 1 to maxReplica
    } yield UUID.randomUUID().toString
    logger.info(s"Setting server with replica count ${maxReplica} and group id ${groupId} with members ${members}")
    val replicaGroup = ReplicaGroup(members.map(x => Member(x)).toList, groupId)
    val ret = {
      for {
        member <- members
      } yield member -> {
        val m = member.replaceAll("-", "_")
        logger.info(s"member ${m}")
        val actor = ac.actorOf(
          Server.props(replicaGroup, member, restClient),
          s"child_${m}"
        )
        actor ! Initialize
        actor
      }
    }.toMap
    ret
  }

  def routeRequest(memberId: String, msg: Any): Future[Any] = {
    implicit val timer = akka.util.Timeout(5 seconds)
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
