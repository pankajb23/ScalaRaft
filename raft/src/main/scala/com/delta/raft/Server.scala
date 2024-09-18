package com.delta.raft

import akka.actor.{Actor, Cancellable, Props}
import com.delta.rest.{
  AppendEntry,
  AppendEntryResponse,
  HeartBeat,
  Initialize,
  LeaderElected,
  PersistableStates,
  ReplicaGroup,
  RequestVote,
  ResponseVote,
  RestClient,
  SendHB
}
import com.delta.utils.ActorPathLogging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

object Server {
  def props(replicaGroup: ReplicaGroup, hostId: String, restClient: RestClient): Props = Props(
    new Server(replicaGroup, hostId: String, restClient)
  )
}

class Server(replicaGroup: ReplicaGroup, hostId: String, restClient: RestClient)
    extends Actor
    with ActorPathLogging {
  import context.dispatcher

  private var currentStates = PersistableStates(0, None, List.empty)
  // new members can also join the group.
  private var currentMembers = replicaGroup.members

  override def receive: Receive = uninitialized()

  def uninitialized(): Receive = {
    case Initialize =>
      logger.info("Initializing server as candidate")
      context.become(candidate())
      self ! Initialize
  }

  private def candidate(): Receive = {
    case Initialize =>
      // vote for self for election
      // increase term count
      currentStates = currentStates.incrementTerm
      logger.info(
        s"Initialized server as candidate, starting election with term ${currentStates.currentTerm}"
      )
    // StartElection() and wait for any RPC response from any other server.
    case LeaderElected =>
      logger.info("Elected as leader")
      context.become(leader())
      self ! Initialize
  }

  var highestTermServing = 0L
  var candidateServing: Option[String] = None

  // only in case for candidate and follower not for leader.
  private def requestVote(): Receive = {
    case x: RequestVote =>
      if (x.term >= currentStates.currentTerm) {
        highestTermServing = x.term
        currentStates = currentStates.copy(currentTerm = x.term)
        logger.info(s"Received vote request from ${x.candidateId} in term ${x.term}")
        if (x.lastLogIndex >= currentStates.lastCommitedLogInex) {
          logger.info(s"Voting for ${x.candidateId} in term ${x.term}")
          sender() ! ResponseVote(hostId, x.term, voteGranted = true)
        } else {
          logger.info(s"Rejecting vote for ${x.candidateId} in term ${x.term} because of log index")
          sender() ! ResponseVote(hostId, currentStates.currentTerm, voteGranted = false)
        }
      } else {
        logger.info(s"Rejecting vote for ${x.candidateId} in term ${x.term} because of term")
        sender() ! ResponseVote(hostId, currentStates.currentTerm, voteGranted = false)
      }
    case _ @HeartBeat(term, commitedIndex) =>

  }

  private def appendEntries(): Receive = {
    case _ @AppendEntry(leaderTerm, leaderId, prevLogIndex, prevLogTerm, entries) =>
      if (currentStates.currentTerm > leaderTerm) {
        logger.warn(s"Rejecting append entry request from ${leaderId} with term ${leaderTerm} whereas current term is ${currentStates.currentTerm}")
        sender() ! AppendEntryResponse(currentStates.currentTerm, success = false)
      } else {
        // check if the log entry is matching with the leader
        // if not, then delete the log entries from the index and append the new entries
        // if yes, then append the new entries
        // update the commit index
        // send the response back to the leader
        logger.info(s"Received append entry request from ${leaderId} with term ${leaderTerm}")
        if (prevLogIndex >= currentStates.lastCommitedLogInex) {
          logger.info(s"Appending entries from ${leaderId} with term ${leaderTerm}")
          currentStates = currentStates.withLogs(entries, prevLogIndex)
          sender() ! AppendEntryResponse(currentStates.currentTerm, success = true)
        } else {
          logger.warn(s"Rejecting append entry request from ${leaderId} with term ${leaderTerm} because of log index")
          sender() ! AppendEntryResponse(currentStates.currentTerm, success = false)
        }
      }
  }

  private def leader(): Receive = {
    case Initialize =>
      logger.info(s"Initializing leader with current term ${currentStates.currentTerm}")
      // send periodic heartbeats to all other servers
      context.system.scheduler.scheduleOnce(100 milliseconds, self, SendHB)

    case SendHB =>
      Future
        .sequence(currentMembers.map { member =>
          restClient
            .memberEndpoint(member)
            .appendEntry(
              AppendEntry(
                currentStates.currentTerm,
                hostId,
                currentStates.lastCommitedLogInex,
                0,
                List.empty
              )
            )
            .transformWith {
              case Success(value) => Future.successful(value)
              case Failure(e) =>
                logger.error(s"Failed to send heartbeat to ${member.id}", e)
                Future.successful(AppendEntryResponse(currentStates.currentTerm, success = false))
            }
        })
        .map { entries =>
          if (entries.exists(resp => resp.term > currentStates.currentTerm)) {
            logger.info("Received higher term from other server, stepping down as leader")
            context.become(candidate())
          } else {
            logger.info("Heartbeat sent to all servers")
            context.system.scheduler.scheduleOnce(100 milliseconds, self, SendHB)
          }
        }
  }

  private def startElection() = {
    Future
      .sequence(currentMembers.filterNot(x => x.id == hostId).map { member =>
        restClient
          .memberEndpoint(member)
          .requestVote(RequestVote(currentStates.currentTerm, hostId))
          .transformWith {
            case Success(value) => Future.successful(value)
            case Failure(e) =>
              logger.error(s"Failed to get vote from ${member.id}", e)
              Future
                .successful(ResponseVote(member.id, currentStates.currentTerm, voteGranted = false))
          }
      })
      .map { allVotes =>
        // check if majority votes are received
        // if yes, then become leader
        // else, start new election
        val canElectLeader = allVotes.count(_.voteGranted) > (replicaGroup.maxSize / 2)
        if (canElectLeader) {
          logger.info("Majority votes received, elected as leader")
          self ! LeaderElected
        } else {
          logger.info("Majority votes not received, starting new election after some timeout")
          context.system.scheduler.scheduleOnce(1 seconds, self, Initialize)
        }
      }
  }
}
