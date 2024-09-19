package com.delta.raft

import akka.actor.{Actor, Props}
import akka.pattern.ask
import com.delta.rest.{Initialize, Member, ReplicaGroup, RestClient}
import controller.RequestController.actorSystem

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object StateManager {
  def props(maxReplica: Int, groupId: String, restClient: RestClient): StateManager =
    new StateManager(maxReplica, groupId: String, restClient)
}

case class StateManager(maxReplica: Int, groupId: String, restClient: RestClient) {
  val membersMap = initialize()
  def initialize() = {
    val members = for {
      i <- 0 to maxReplica
    } yield UUID.randomUUID().toString
    val replicaGroup = ReplicaGroup(members.map(x => Member(x)).toList, groupId)
    val ret = {
      for {
        member <- members
      } yield member -> {
        val actor = actorSystem.actorOf(
          Server.props(replicaGroup, member, restClient),
          member.replaceAll("-", "_")
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
        actorSystem.stop(actor)
        membersMap - memberId
      case None => println("No actor found")
    }
  }
}
