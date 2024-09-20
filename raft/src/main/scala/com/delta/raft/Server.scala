package com.delta.raft

import akka.actor.{Actor, Cancellable, Props}
import com.delta.rest.{
  AppendEntry,
  AppendEntryResponse,
  HeartBeat,
  Initialize,
  LeaderElected,
  NewEntry,
  PersistableStates,
  ReElection,
  ReplicaGroup,
  RequestVote,
  ResponseVote,
  RestClient,
  SendHB,
  TransitionToCandidate,
  VolatileStates,
  VolatileStatesOnLeader
}
import com.delta.utils.ActorPathLogging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

object Server {
  def props(replicaGroup: ReplicaGroup, hostId: String, restClient: RestClient): Props = Props(
    new Server(replicaGroup, hostId: String, restClient)
  )
}

class Server(replicaGroup: ReplicaGroup, hostId: String, restClient: RestClient)
    extends Actor
    with ActorPathLogging {
  import context.dispatcher
  val random = new Random()
  import context.dispatcher

  private var currentStates = PersistableStates()
  private var volatileStates = VolatileStates()
  private var volatileStatesOnLeader = VolatileStatesOnLeader(Map.empty, Map.empty)

  // new members can also join the group.
  private var currentMembers = replicaGroup.members
  private var candidateStateForLastContigousTerm: Int = 1

  // TODO send initialize from outside.
  override def receive: Receive = candidate()

  private def candidate(): Receive =
    candidateBehaviour().orElse(requestVote())

  private def candidateBehaviour(): Receive = {
    case Initialize =>
      // vote for self for election
      // increase term count
      currentStates = currentStates.incrementTerm
      logger.info(
        s"Initialized server as candidate, starting election with term ${currentStates.currentTerm}"
      )
      self ! ReElection

    case ReElection =>
      logger.info(s"Starting re-election with self term ${currentStates.currentTerm}")
      startElection()
    // StartElection() and wait for any RPC response from any other server.

    case LeaderElected =>
      logger.info("Elected as leader")
      candidateStateForLastContigousTerm = 1
      context.become(leader())
      self ! Initialize

    case entry: AppendEntry =>
      // save message but transition.
      context.become(follower())
      self ! entry
  }

  var candidateServing: Option[String] = None
  // only in case for candidate and follower not for leader.
  private def requestVote(): Receive = {
    case x: RequestVote =>
      if (x.term >= currentStates.currentTerm) {
        // updating self term.
        currentStates = currentStates.withTerm(x.term)
        logger.info(s"Received vote request from ${x.candidateId} in term ${x.term}")
        if (x.lastLogIndex >= currentStates.lastCommitedLogIndex) {
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
  }

  private def follower(): Receive = appendEntries()

  private def appendEntries(): Receive = {
    case _ @AppendEntry(leaderTerm, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      if (currentStates.currentTerm > leaderTerm) {
        logger.warn(s"""Rejecting append entry request from $leaderId with term $leaderTerm whereas current term is ${currentStates.currentTerm}""")
        sender() ! AppendEntryResponse(currentStates.currentTerm, success = false)
      } else {
        // check if the log entry is matching with the leader
        // if not, then delete the log entries from the index and append the new entries
        // if yes, then append the new entries
        // update the commit index
        // send the response back to the leader
        logger.info(s"Received append entry request from ${leaderId} with term ${leaderTerm}")
        if (currentStates.doesContainAnEntryAtIndex(prevLogIndex, prevLogTerm)) {
          logger.info(s"""Appending entries from $leaderId with term $leaderTerm""")
          currentStates = currentStates.withLogs(entries, leaderCommit)
          sender() ! AppendEntryResponse(currentStates.currentTerm, success = true)
        } else {
          logger.warn(s"""Rejecting append entry request from $leaderId with term $leaderTerm because of log index""")
          sender() ! AppendEntryResponse(currentStates.currentTerm, success = false)
        }
      }
  }

  private var hbs: Cancellable = _
  private def leader(): Receive = {
    case Initialize =>
      logger.info(s"Initializing leader with current term ${currentStates.currentTerm}")
      // send periodic heartbeats to all other servers
      hbs = context.system.scheduler.scheduleOnce(100 milliseconds, self, SendHB)

    case NewEntry(entry: String) =>
      // append the new entry to the log
      // send the entry to all other servers
      // update the commit index
      logger.info(s"Received new entry from client $entry")
      currentStates = currentStates.addLogs(List(entry))
      updateAppendEntries()

    case TransitionToCandidate =>
      hbs.cancel()
      logger.info("Transitioning to candidate")
      context.become(candidate())
      self ! Initialize

    case SendHB =>
      updateAppendEntries()
  }

  private def updateAppendEntries(): Unit = {
    val latestLogIndex = currentStates.lastLogIndex()
    Future
      .sequence(
        currentMembers
          .map { member =>
            val lastLogIndex = volatileStatesOnLeader.nextIndex.getOrElse(member.id, 0L)
            val lastLogTerm = volatileStatesOnLeader.nextIndexTerm.getOrElse(member.id, 0L)
            restClient
              .memberEndpoint(member)
              .appendEntry(
                AppendEntry(
                  currentStates.currentTerm,
                  hostId,
                  lastLogIndex,
                  lastLogTerm,
                  currentStates.getLogEntriesFromIndex(lastLogIndex),
                  currentStates.lastCommitedLogIndex
                )
              )
              .transformWith {
                case Success(value) =>
                  volatileStatesOnLeader
                    .updateNextIndex(member.id, latestLogIndex, currentStates.currentTerm)
                  volatileStatesOnLeader.updateMatchIndex(member.id, latestLogIndex)
                  Future.successful(value)
                case Failure(e) =>
                  logger.error(s"Failed to send heartbeat to ${member.id}", e)
                  Future.successful(AppendEntryResponse(currentStates.currentTerm, success = false))
              }
          }
      )
      .foreach { entries =>
        if (entries.exists(resp => resp.term > currentStates.currentTerm)) {
          logger.info("Received higher term from other server, stepping down as leader")
          self ! TransitionToCandidate
        }
      }
  }

  private def startElection(): Unit = {
    Future
      .sequence(currentMembers.filterNot(x => x.id == hostId).map { member =>
        restClient
          .memberEndpoint(member)
          .requestVote(RequestVote(currentStates, hostId))
          .transformWith {
            case Success(value) => Future.successful(value)
            case Failure(e) =>
              println(s"Failed to get vote from ${member.id}", e)
              Future
                .successful(ResponseVote(member.id, currentStates.currentTerm, voteGranted = false))
          }
      })
      .foreach { allVotes =>
        // check if majority votes are received
        // if yes, then become leader
        // else, start new election
        val canElectLeader = allVotes.count(_.voteGranted) > (replicaGroup.maxSize / 2)
        if (canElectLeader) {
          logger.info("Majority votes received, elected as leader")
          self ! LeaderElected
        } else {
          val waitTime = (candidateStateForLastContigousTerm + 1) * 2 + random.nextInt(
            candidateStateForLastContigousTerm
          )
          logger.info(s"Majority votes not received, starting new election after some timeout waitTime ${waitTime} after term ${candidateStateForLastContigousTerm}")
          context.system.scheduler.scheduleOnce(
            waitTime seconds,
            self,
            ReElection
          )
        }
      }
  }
}
