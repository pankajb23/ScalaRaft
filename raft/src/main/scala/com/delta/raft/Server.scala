package com.delta.raft

import akka.actor.{Actor, Cancellable, Props}
import com.delta.rest.LeaderKnown.{LeaderFound, NoLeaderFound}
import com.delta.rest.LogEntry.CommandEntry
import com.delta.rest.MemberStates.{Candidate, Follower}
import com.delta.rest.{
  AppendEntry,
  AppendEntryResponse,
  FindLeader,
  HeartBeat,
  Initialize,
  LeaderElected,
  LivenessCheck,
  Member,
  MemberStates,
  NewDataEntry,
  NewEntry,
  PersistLogs,
  PersistableStates,
  ReElection,
  ReplicaGroup,
  RequestVote,
  RequestWritten,
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

  private var currentStates = PersistableStates(hostId)
  private var volatileStates = VolatileStates()
  private var volatileStatesOnLeader = VolatileStatesOnLeader(Map.empty, Map.empty)

  private var lastKnownLeader: String = _
  var replicaGroup: ReplicaGroup = _
  // new members can also join the group.
  private var candidateStateForLastContigousTerm: Int = 1
  private var currentMemberState: MemberStates = MemberStates.Candidate
  private val liveNessCheck =
    context.system.scheduler.scheduleAtFixedRate(0.seconds, 5 seconds, self, LivenessCheck)
  private var lastMessageReceived: Long = 0

  override def receive: Receive = uninitialized()

  private def uninitialized(): Receive = {
    case msg: ReplicaGroup =>
      replicaGroup = msg
      logger.info(s"Initialized server with id ${hostId} and replicaGroup ${msg}")
      context.become(candidate())
      // at first they will
      self ! Initialize
  }

  private def candidate(messages: List[Any] = Nil): Receive =
    requestVote().orElse(candidateBehaviour()).orElse(leaderQuery())

  private def candidateBehaviour(messages: List[Any] = Nil): Receive = {
    case Initialize =>
      // vote for self for election
      // increase term count
      logger.info(
        s"Initialized server as candidate"
      )
      self ! ReElection

    case ReElection =>
      currentStates = currentStates.incrementTerm
      currentMemberState = MemberStates.Candidate
      logger.info(s"Starting re-election with self term ${currentStates.currentTerm}")
      startElection()
    // StartElection() and wait for any RPC response from any other server.

    case LeaderElected =>
      logger.info("Elected as leader")
      candidateStateForLastContigousTerm = 1
      currentMemberState = MemberStates.Leader
      context.become(leader().orElse(leaderQuery()))
      self ! Initialize

    case entry: AppendEntry =>
      // save message but transition.
      // there could be delayed messages from the previous term which could be AppendEntry .
      // we have to careful of this message.
      val sender = context.sender()
      val isSuccessful = appendEntryToLog(entry)

      sender ! AppendEntryResponse(currentStates.currentTerm, success = isSuccessful)
      if (isSuccessful) {
        logger.info(s"Becoming follower and log entry is $entry")
        lastKnownLeader = entry.leaderId
        currentMemberState = MemberStates.Follower
        context.become(follower().orElse(leaderQuery()))
      } else {
        logger.info(s"Rejecting append entry request from ${entry.leaderId} with term ${entry.term} from ${sender.path.toStringWithoutAddress}")
        // Re-election might be required or probably triggered.
      }

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
    case entry: AppendEntry =>
      val sender = context.sender()
      val isSuccessful = appendEntryToLog(entry)
      sender ! AppendEntryResponse(currentStates.currentTerm, success = isSuccessful)
    case ReElection =>
      logger.info("Received re-election request, but this is not correct message for follower")

  }

  private var hbs: Cancellable = _
  private def leader(): Receive = {
    case Initialize =>
      logger.info(s"Initialized leader with current term ${currentStates.currentTerm}")
      // send periodic heartbeats to all other servers
      hbs = context.system.scheduler.scheduleAtFixedRate(0.seconds, 5 seconds, self, SendHB)

    case NewDataEntry(entry: String) =>
      // append the new entry to the log
      // send the entry to all other servers
      // update the commit index
      logger.info(s"Received new entry from client $entry")
      currentStates = currentStates.addLogs(List(entry))
      sender() ! RequestWritten(currentStates.currentTerm, index = currentStates.lastLogIndex())
      updateAppendEntries()

    case TransitionToCandidate =>
      hbs.cancel()
      logger.info("Transitioning to candidate")
      currentMemberState = MemberStates.Candidate
      context.become(candidate().orElse(leaderQuery()))
      self ! Initialize

    case SendHB =>
      updateAppendEntries()
  }

  private def appendEntryToLog(entry: AppendEntry): Boolean = {
    lastMessageReceived = System.currentTimeMillis()
    if (currentStates.currentTerm > entry.term) {
      logger.warn(s"""Rejecting append entry request from ${entry.leaderId} with term ${entry.term} whereas current term is ${currentStates.currentTerm} from ${sender().path.toStringWithoutAddress}""")
      false
    } else {
      // check if the log entry is matching with the leader
      // if not, then delete the log entries from the index and append the new entries
      // if yes, then append the new entries
      // update the commit index
      // send the response back to the leader
      logger.info(s"Received append entry request from ${entry.leaderId} with term ${entry.term}")
      if (currentStates.doesContainAnEntryAtIndex(entry.prevLogIndex, entry.prevLogTerm)) {
        logger.info(s"""Appending entries from ${entry.leaderId} with term ${entry.term} from ${sender().path.toStringWithoutAddress}""")
        currentStates = currentStates.withLogs(entry.entries, entry.leaderCommit)
        true
      } else {
        logger.warn(s"""Rejecting append entry request from ${entry.leaderId} with term ${entry.term} because of log index from ${sender().path.toStringWithoutAddress} and log size ${currentStates.log}""")
        false
      }
    }
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
                  if (value.success) {
                    volatileStatesOnLeader = volatileStatesOnLeader
                      .updateNextIndex(member.id, latestLogIndex, currentStates.currentTerm)
                      .updateMatchIndex(member.id, latestLogIndex)
                    Future.successful(value)
                  } else {
                    volatileStatesOnLeader = volatileStatesOnLeader
                      .decrementNextIndex(member.id, currentStates)
                    Future.successful(value)
                  }

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
        // atleast half of the servers have commited this result.
        if (entries.count(_.success) >= (replicaGroup.maxSize / 2)) {
          val t =
            volatileStatesOnLeader.matchIndex.filter(x =>
              x._2 > currentStates.lastCommitedLogIndex && volatileStatesOnLeader
                .nextIndexTerm(x._1) == currentStates.currentTerm
            )
          if (t.size >= (replicaGroup.maxSize / 2)) {
            currentStates = currentStates.updateLastCommitedLog(t.values.min)
          }
        }
      }
  }

  private def startElection(): Unit = {
    Future
      .sequence(replicaGroup.availableMembers.map { member =>
        restClient
          .memberEndpoint(member)
          .requestVote(RequestVote(currentStates, hostId))
          .transformWith {
            case Success(value) => Future.successful(value)
            case Failure(e) =>
              logger.warn(s"Failed to get vote from ${member.id}", e)
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
        logger.info(s"""Received votes from all servers $allVotes grantedVotes:$grantedVotes quorumVotes:$quorumVotes""")
        // if majority votes are received, then no other can become leader in this term
        // if not then someone else could be the leader by now
        // wait for HB from the leader or start a new election
        // whoever comes first.
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

  def leaderQuery(): Receive = {
    case FindLeader =>
      if (currentMemberState == MemberStates.Leader) {
        sender() ! LeaderFound(hostId)
      } else if (currentMemberState == Follower) {
        sender() ! LeaderFound(lastKnownLeader)
      } else {
        sender() ! NoLeaderFound
      }

    case PersistLogs =>
      currentStates.persistLogs

    case LivenessCheck =>
      if (
        lastMessageReceived + 10.seconds.toMillis < System
          .currentTimeMillis() && currentMemberState == MemberStates.Follower
      ) {
        // if server is in candidate state, reelection would have been in progress. So no need to start re-election.
        logger.info("No message received for 10 seconds, starting re-election")
        context.become(candidate())
        self ! ReElection
      }
  }
  override def unhandled(message: Any): Unit =
    logger.warn(s"Unhandled message $message current state ${currentMemberState}")
}
