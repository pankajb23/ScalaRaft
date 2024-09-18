package com.delta

import play.api.libs.json.{JsValue, Json, OFormat}

package object rest {
  case class Member(id: String)

  object Member {
    implicit val f: OFormat[Member] = Json.format[Member]
  }

  case class ResponseVote(memberId: String, term: Long, voteGranted: Boolean)
  object ResponseVote {
    implicit val o: OFormat[ResponseVote] = Json.format
    def fromJson(js: JsValue): ResponseVote = js.as[ResponseVote](o)
  }

  case class RequestVote(term: Long, candidateId: String, lastLogIndex: Long, lastLogTerm: Long)
  object RequestVote {
    implicit val o: OFormat[RequestVote] = Json.format[RequestVote]
  }

  sealed trait LogEntry extends enumeratum.EnumEntry

  object LogEntry extends enumeratum.Enum[LogEntry] with enumeratum.PlayJsonEnum[LogEntry] {
    override def values: IndexedSeq[LogEntry] = findValues

    case class CommandEntry(index: Long, term: Long, command: String) extends LogEntry

    case class HBEntry(term: Long) extends LogEntry
  }

  case class PersistableStates(
    currentTerm: Long = 0L,
    votedFor: Option[String] = None,
    log: List[LogEntry] = Nil,
    lastCommitedLogInex: Long = 0L
  ) {
    def incrementTerm: PersistableStates = copy(currentTerm = currentTerm + 1)

    def withLogs(entries: List[LogEntry], prevLogIndex: Long): PersistableStates = {
      copy(
        log = log.dropRight(log.size - prevLogIndex.toInt) ++ entries,
        lastCommitedLogInex = prevLogIndex + entries.size
      )
    }
  }

  object PersistableStates {
    implicit val f: OFormat[PersistableStates] = Json.format[PersistableStates]
  }

  case class ReplicaGroup(members: List[Member], groupId: String, maxSize: Int = 5)
  object ReplicaGroup {
    implicit val o: OFormat[ReplicaGroup] = Json.format[ReplicaGroup]
  }

  sealed trait Verbs
  case object Initialize extends Verbs
  case object LeaderElected extends Verbs
  case object SendHB extends Verbs
  case class HeartBeat(term: Long, commitedIndex: Long) extends Verbs
  object HeartBeat {
    implicit val o: OFormat[HeartBeat] = Json.format[HeartBeat]
  }
  case class AppendEntry(
    term: Long,
    leaderId: String,
    prevLogIndex: Long,
    prevLogTerm: Long,
    entries: List[LogEntry]
  ) extends Verbs

  object AppendEntry {
    implicit val o: OFormat[AppendEntry] = Json.format[AppendEntry]
  }

  case class AppendEntryResponse(term: Long, success: Boolean)
  object AppendEntryResponse {
    implicit val o: OFormat[AppendEntryResponse] = Json.format[AppendEntryResponse]
  }
}
