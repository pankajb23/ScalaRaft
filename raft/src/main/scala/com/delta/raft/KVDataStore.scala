package com.delta.raft

import com.delta.rest.LogEntry.CommandEntry
import com.delta.rest.{DataStore, LogEntry, PersistableStates}
import io.delta.LSMTree
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

class KVDataStore(hostId: String) extends DataStore {
  private val lsmTree = new LSMTree(hostId)
  def put(key: String, value: Option[String]): Unit = lsmTree.put(key, value)
  def get(key: String): Option[String] = lsmTree.get(key)

  def persist: Unit = lsmTree.flushToDisk()
}
