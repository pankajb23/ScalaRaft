package com.delta.raft

import play.api.libs.json.Json

object States {
  sealed trait LogEntry extends enumeratum.EnumEntry

  object LogEntry extends enumeratum.Enum[LogEntry] with enumeratum.PlayJsonEnum[LogEntry] {
    override def values: IndexedSeq[LogEntry] = findValues

    case class CommandEntry(term: Long, command: String) extends LogEntry

    case class HBEntry(term: Long) extends LogEntry
  }

  case class PersistableStates(
    currentTerm: Long,
    votedFor: Option[String],
    log: List[LogEntry]
  )

  object PersistableStates {
    lazy val f = Json.format[PersistableStates]
  }
}
