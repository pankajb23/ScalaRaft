package io.delta

import java.io.{FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.io.Directory
import scala.util.Using

/**
 * LSM tree with key,value pairs.
 *
 * values can be compressed as string whereas schema can be stored in a separate file.
 * upon reading the schema, the values can be decompressed and used.
 * this can be potentially be done at client side so to avoid any overhead on the server.
 *
 *
 * SSTables can be further compressed using snappy or any other compression algorithm.
 */
class LSMTree(MaxMemtablesSize: Int) {
  // tree map is a sorted map with concurrent support.

  private val memTables = new mutable.TreeMap[String, Option[String]]()
  private val indexes = new mutable.TreeMap[String, (Int, Long)]()
  private var currentFileId: Int = 0
  private def get(key: String): Option[String] = {
    memTables
      .get(key)
      .orElse {
        indexes.get(key).map {
          case (fileId, index) =>
            Using(new ObjectInputStream(new FileInputStream(s"/tmp/sstable_${fileId}.dat"))) { in =>
              in.skip(index)
              in.readObject().asInstanceOf[(String, String)]._2
            }.toOption
        }
      }
      .flatten
  }

  def put(key: String, value: String): Unit = {
    memTables.put(key, Option(value))
    if (memTables.size > 10) {
      flushToDisk()
    }
  }

  def flushToDisk(): Unit = {
    if (memTables.isEmpty) return
    val filename = s"sstable_${currentFileId}.dat"
    Using(new ObjectOutputStream(new FileOutputStream(s"/tmp/${filename}"))) { out =>
      memTables.zipWithIndex.map {
        case ((key, value), index) =>
          out.writeObject((key, value))
          indexes.put(key, (currentFileId, index))
      }
    }
  }
}
