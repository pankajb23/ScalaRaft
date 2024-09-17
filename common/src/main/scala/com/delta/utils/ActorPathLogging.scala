package com.delta.utils

import akka.actor.Actor
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

trait ActorPathLogging { it: Actor =>
  val logger = Logger(LoggerFactory.getLogger(self.path.toStringWithoutAddress))
}
