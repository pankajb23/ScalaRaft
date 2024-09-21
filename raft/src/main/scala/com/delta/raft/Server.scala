package com.delta.raft

import akka.actor.{Actor, Cancellable, Props}
import com.delta.rest.{
  AppendEntry,
  AppendEntryResponse,
  HeartBeat,
  Initialize,
  LeaderElected,
  Member,
  MemberStates,
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
import com.delta.utils.{ActorPathLogging, BackOffComputation}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

object Server {
  def props(hostId: String, restClient: RestClient): Props = Props(
    new Server(hostId, restClient)
  )
}

class Server(hostId: String, restClient: RestClient) extends Actor with ActorPathLogging {
  import context.dispatcher

  override def preStart(): Unit =
    logger.info(s"Starting server with id ${self.path.toStringWithoutAddress}")

  private var currentStates = PersistableStates()
  private var volatileStates = VolatileStates()
  private var volatileStatesOnLeader = VolatileStatesOnLeader(Map.empty, Map.empty)

  var replicaGroup: ReplicaGroup = _
  // new members can also join the group.
  private var candidateStateForLastContigousTerm: Int = 1
  private var currentMemberState: MemberStates = MemberStates.Candidate

  override def receive: Receive = uninitialized()

  private def uninitialized(): Receive = {
    case msg: ReplicaGroup =>
      replicaGroup = msg
      logger.info(s"Initialized server with id ${hostId} and replicaGroup ${msg}")
      context.become(candidate())
      self ! Initialize
  }

  private def candidate(messages: List[Any] = Nil): Receive =
    requestVote().orElse(candidateBehaviour())

  private def candidateBehaviour(messages: List[Any] = Nil): Receive = {
    case Initialize =>
      // vote for self for election
      // increase term count
      logger.info(
        s"Initialized server as candidate, starting election with term ${currentStates.currentTerm}"
      )
      self ! ReElection

    case ReElection =>
      currentStates = currentStates.incrementTerm
      logger.info(s"Starting re-election with self term ${currentStates.currentTerm}")
      startElection()
    // StartElection() and wait for any RPC response from any other server.

    case LeaderElected =>
      logger.info("Elected as leader")
      candidateStateForLastContigousTerm = 1
      currentMemberState = MemberStates.Leader
      context.become(leader())
      self ! Initialize

    case entry: AppendEntry =>
      // save message but transition.
      logger.info(s"Becoming follower and log entry is ${entry}")
      currentMemberState = MemberStates.Follower
      context.become(follower())
      self.forward(entry)
  }

  var candidateServing: Option[String] = None
  var mappedTermLeaderTuple: Option[(Long, String)] = None

  // only in case for candidate and follower not for leader.
  private def requestVote(): Receive = {
    case x: RequestVote =>
      if (
        x.term >= currentStates.currentTerm && (mappedTermLeaderTuple.isEmpty || (mappedTermLeaderTuple.isDefined && x.term > mappedTermLeaderTuple.get._1))
      ) {
        // updating self term.
        currentStates = currentStates.withTerm(x.term)
        mappedTermLeaderTuple = Option((x.term, x.candidateId))
        logger.info(s"Received vote request from ${x.candidateId} in term ${x.term}")
        if (x.lastLogIndex >= currentStates.lastCommitedLogIndex) {
          logger.info(s"Voting for ${x.candidateId} in term ${x.term}")
          sender() ! ResponseVote(hostId, x.term, voteGranted = true)
        } else {
          logger.info(s"Rejecting vote for ${x.candidateId} in term ${x.term} because of log index")
          sender() ! ResponseVote(hostId, currentStates.currentTerm, voteGranted = false)
        }
      } else {
        logger.info(s"Rejecting vote for ${x.candidateId} in term ${x.term} because of term is less than current term ${currentStates.currentTerm} and mappedLeaderTuple ${mappedTermLeaderTuple}")
        sender() ! ResponseVote(hostId, currentStates.currentTerm, voteGranted = false)
      }
  }

  private def follower(): Receive = appendEntries()

  private def appendEntries(): Receive = {
    case _ @AppendEntry(leaderTerm, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      val sender = context.sender()
      if (currentStates.currentTerm > leaderTerm) {
        logger.warn(s"""Rejecting append entry request from $leaderId with term $leaderTerm whereas current term is ${currentStates.currentTerm} from ${sender.path.toStringWithoutAddress}""")
        sender ! AppendEntryResponse(currentStates.currentTerm, success = false)
      } else {
        // check if the log entry is matching with the leader
        // if not, then delete the log entries from the index and append the new entries
        // if yes, then append the new entries
        // update the commit index
        // send the response back to the leader
        logger.info(s"Received append entry request from ${leaderId} with term ${leaderTerm}")
        if (currentStates.doesContainAnEntryAtIndex(prevLogIndex, prevLogTerm)) {
          logger.info(s"""Appending entries from $leaderId with term $leaderTerm from ${sender.path.toStringWithoutAddress}""")
          currentStates = currentStates.withLogs(entries, leaderCommit)
          sender ! AppendEntryResponse(currentStates.currentTerm, success = true)
        } else {
          logger.warn(s"""Rejecting append entry request from $leaderId with term $leaderTerm because of log index from ${sender.path.toStringWithoutAddress}""")
          sender ! AppendEntryResponse(currentStates.currentTerm, success = false)
        }
      }
    case ReElection =>
      logger.info("Received re-election request, but this is not correct message for follower")

  }

  private var hbs: Cancellable = _
  private def leader(): Receive = {
    case Initialize =>
      logger.info(s"Initialized leader with current term ${currentStates.currentTerm}")
      // send periodic heartbeats to all other servers
      self ! SendHB
      hbs = context.system.scheduler.scheduleAtFixedRate(5.seconds, 5 seconds, self, SendHB)

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
      currentMemberState = MemberStates.Candidate
      context.become(candidate())
      self ! Initialize

    case SendHB =>
      updateAppendEntries()
  }

  private def updateAppendEntries(): Unit = {
    val latestLogIndex = currentStates.lastLogIndex()
    Future
      .sequence(
        replicaGroup.otherMembers
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
      .sequence(replicaGroup.otherMembers.map { member =>
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
        val grantedVotes = allVotes.count(_.voteGranted)
        val quorumVotes = replicaGroup.maxSize / 2
        val canElectLeader = grantedVotes > quorumVotes
        logger.info(s"Received votes from all servers ${allVotes} grantedVotes:${grantedVotes} quorumVotes:$quorumVotes")
        if (canElectLeader) {
          logger.info("Majority votes received, elected as leader")
          self ! LeaderElected
        } else {
          val waitTime =
            BackOffComputation
              .computeBackoff(candidateStateForLastContigousTerm)
              .toMillis
              .milliseconds
          logger.info(s"Majority votes not received, starting new election after some timeout waitTime $waitTime after term ${currentStates.currentTerm}")
          context.system.scheduler.scheduleOnce(
            waitTime,
            self,
            ReElection
          )
        }
      }
  }

  override def unhandled(message: Any): Unit =
    logger.warn(s"Unhandled message $message current state ${currentMemberState}")
}
