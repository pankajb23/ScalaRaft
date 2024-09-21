package com.delta

import com.delta.rest.AppendEntry
import com.delta.rest.LogEntry.CommandEntry
import com.typesafe.scalalogging.LazyLogging
import julienrf.json.derived
import play.api.libs.json.{JsValue, Json, OFormat, __}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.collection.immutable.TreeMap
import scala.reflect.io.Path

package object rest {
  case class Member(id: String)

  object Member {
    implicit val f: OFormat[Member] = Json.format[Member]
  }

  /**
   *
   * @param memberId other candidates' memberId
   * @param term term of the candidate
   * @param voteGranted true if the candidate received vote
   */
  case class ResponseVote(memberId: String, term: Long, voteGranted: Boolean)
  object ResponseVote {
    implicit val o: OFormat[ResponseVote] = Json.format
    def fromJson(js: JsValue): ResponseVote = js.as[ResponseVote](o)
  }

  /**
   *
   * @param term candidates' term
   * @param candidateId  candidate requesting vote
   * @param lastLogIndex index of the candidate's last log entry
   * @param lastLogTerm term of the candidate's last log entry
   */
  case class RequestVote(term: Long, candidateId: String, lastLogIndex: Long, lastLogTerm: Long)
  object RequestVote {
    implicit val o: OFormat[RequestVote] = Json.format[RequestVote]
    def apply(currentState: PersistableStates, candidateId: String): RequestVote =
      RequestVote(
        currentState.currentTerm,
        candidateId,
        currentState.lastLogIndex(),
        currentState.currentTerm
      )
  }

//  sealed trait DataLog extends enumeratum.EnumEntry
//  object DataLog extends enumeratum.Enum[DataLog] with enumeratum.PlayJsonEnum[DataLog] {
//    override def values: IndexedSeq[DataLog] = findValues
//
//
//
//
//  }

  sealed trait LogEntry extends enumeratum.EnumEntry

  object LogEntry extends enumeratum.Enum[LogEntry] {
    override def values: IndexedSeq[LogEntry] = findValues

    case class CommandEntry(index: Long, term: Long, command: String) extends LogEntry
    object CommandEntry {
      implicit val f: OFormat[CommandEntry] = derived.oformat[CommandEntry]()
    }

    case class HBEntry(term: Long) extends LogEntry

    val defaultTypeFormat = (__ \ "type").format[String]
    implicit val f: OFormat[LogEntry] = derived.flat.oformat[LogEntry](defaultTypeFormat)
  }

  case class PersistableStates(
    hostId: String,
    currentTerm: Long = 0L,
    votedFor: Option[String] = None,
    log: Map[Long, LogEntry] = TreeMap.empty,
    lastCommitedLogIndex: Long = 0L,
    persistableLogs: List[LogEntry] = List.empty
  ) extends LazyLogging {
    def incrementTerm: PersistableStates = copy(currentTerm = currentTerm + 1)

    def doesContainAnEntryAtIndex(index: Long, term: Long): Boolean = {
      val t = log.get(index)
      logger.info(s"log entry at index ${t} and term ${term} and ${index} and ${log}")
      // only appending command entry to the log
      log.isEmpty || log.get(index).exists(entry => entry.asInstanceOf[CommandEntry].term == term)
    }

    def addLogs(entries: List[String]): PersistableStates = {
      val lastIndex = lastLogIndex()
      val newEntries = entries.zipWithIndex.map {
        case (entry, index) =>
          lastIndex + index + 1 -> CommandEntry(lastIndex + index + 1, currentTerm, entry)
      }
      copy(
        log = log ++ newEntries
      )
    }

    def withLogs(entries: List[LogEntry], leaderCommit: Long): PersistableStates = {
      val (commitTableLogs, seenLogs) = log.partition(_._1 <= leaderCommit)
      val newLogs = seenLogs ++ entries.collect { case x: CommandEntry => x.index -> x }
      copy(
        log = newLogs,
        lastCommitedLogIndex = leaderCommit,
        persistableLogs = persistableLogs ++ commitTableLogs.values.toList
      )
    }

    def withTerm(term: Long): PersistableStates =
      copy(currentTerm = term)

    def lastLogIndex(): Long =
      log.lastOption.map(_._1).getOrElse(0L)

    def getLogEntriesFromIndex(from: Long): List[LogEntry] =
      log.filter(_._1 >= from).values.toList

    def previousLogEntryTo(l: Long): Option[CommandEntry] = {
      val index = l - 1
      log.get(index).map(_.asInstanceOf[CommandEntry])
    }

    def persistLogs(): Unit =
      Files.write(
        Paths.get(s"/Users/pankajb23/workspace/$hostId.json"),
        Json.prettyPrint(Json.toJson(persistableLogs)).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )

    def logAtIndex(index: Long): Option[LogEntry] = log.get(index)

    def updateLastCommitedLog(min: Long): PersistableStates = {
      val (commitTableLogs, seenLogs) = log.partition(_._1 < min)
      copy(
        lastCommitedLogIndex = min,
        persistableLogs = persistableLogs ++ commitTableLogs.values.toList
      )
    }
  }

  object PersistableStates {
    implicit val f: OFormat[PersistableStates] = Json.format[PersistableStates]
  }

  case class VolatileStates(commitIndex: Long = 0L, lastApplied: Long = 0) {
    def withCommitIndex(cIndex: Long): VolatileStates = copy(commitIndex = cIndex)

    def withLastApplied(lApplied: Long): VolatileStates = copy(lastApplied = lApplied)
  }

  object VolatileStates {
    implicit val f: OFormat[VolatileStates] = Json.format[VolatileStates]
  }

  case class VolatileStatesOnLeader(
    nextIndex: Map[String, Long] = Map.empty,
    nextIndexTerm: Map[String, Long] = Map.empty,
    matchIndex: Map[String, Long] = Map.empty
  ) {
    def withNextIndex(memberId: String, index: Long, term: Long): VolatileStatesOnLeader =
      copy(
        nextIndex = nextIndex + (memberId -> index),
        nextIndexTerm = nextIndexTerm + (memberId -> term)
      )

    def withMatchIndex(memberId: String, index: Long): VolatileStatesOnLeader =
      copy(matchIndex = matchIndex + (memberId -> index))

    def updateMatchIndex(id: String, l: Long): VolatileStatesOnLeader =
      copy(matchIndex = matchIndex + (id -> l))

    def decrementNextIndex(id: String, currentState: PersistableStates): VolatileStatesOnLeader = {
      val previousLogEntry = currentState.previousLogEntryTo(nextIndex(id))
      copy(
        nextIndex = nextIndex + (id -> previousLogEntry.map(_.index).getOrElse(0L)),
        nextIndexTerm = nextIndexTerm + (id -> previousLogEntry.map(_.term).getOrElse(0L))
      )
    }

    def updateNextIndex(id: String, l: Long, term: Long): VolatileStatesOnLeader =
      copy(nextIndex = nextIndex + (id -> l), nextIndexTerm = nextIndexTerm + (id -> term))
  }

  case class ReplicaGroup(
    members: List[Member],
    hostId: String,
    groupId: String,
    maxSize: Int = 3
  ) {
    def availableMembers: List[Member] = members
    def otherMembers: List[Member] = members.filterNot(_.id == hostId)
  }
  object ReplicaGroup {
    implicit val o: OFormat[ReplicaGroup] = Json.format[ReplicaGroup]

  }

  sealed trait Verbs
  case object Initialize extends Verbs
  case object ReElection extends Verbs
  case object LeaderElected extends Verbs
  case object TransitionToCandidate extends Verbs
  case object SendHB extends Verbs
  case class HeartBeat(term: Long, commitedIndex: Long) extends Verbs
  object HeartBeat {
    implicit val o: OFormat[HeartBeat] = Json.format[HeartBeat]
  }
  case class AppendEntry(
    term: Long,
    leaderId: String,
    prevLogIndex: Long = 0L,
    prevLogTerm: Long = 0L,
    entries: List[LogEntry],
    leaderCommit: Long = 0L
  ) extends Verbs

  object AppendEntry {
    implicit val o: OFormat[AppendEntry] = Json.format[AppendEntry]
  }

  case class AppendEntryResponse(term: Long, success: Boolean)
  object AppendEntryResponse {
    implicit val o: OFormat[AppendEntryResponse] = Json.format[AppendEntryResponse]
  }

  case class NewEntry(entry: String) extends Verbs
  object NewEntry {
    implicit val o: OFormat[NewEntry] = Json.format[NewEntry]
  }

  sealed trait MemberStates extends enumeratum.EnumEntry
  object MemberStates
      extends enumeratum.Enum[MemberStates]
      with enumeratum.PlayJsonEnum[MemberStates] {
    override def values: IndexedSeq[MemberStates] = findValues
    case object Leader extends MemberStates
    case object Follower extends MemberStates
    case object Candidate extends MemberStates
  }

  case class NewDataEntry(entry: String) extends Verbs
  object NewDataEntry {
    implicit val o: OFormat[NewDataEntry] = Json.format[NewDataEntry]
  }
  case object FindLeader extends Verbs

  case class RequestWritten(term: Long, index: Long) extends Verbs
  object RequestWritten {
    implicit val o: OFormat[RequestWritten] = Json.format[RequestWritten]
  }
  sealed trait LeaderKnown extends Verbs with enumeratum.EnumEntry
  object LeaderKnown
      extends enumeratum.Enum[LeaderKnown]
      with enumeratum.PlayJsonEnum[LeaderKnown] {
    case class LeaderFound(hostId: String) extends LeaderKnown
    case object NoLeaderFound extends LeaderKnown

    override val values: IndexedSeq[LeaderKnown] = findValues
  }
  case object PersistLogs extends Verbs
}

object Delta extends App {
  val t = AppendEntry(1, "1", 0, 0, List(CommandEntry(1, 1, "hello")), 0)
  val json = Json.toJson(t)
  println(json)
}
