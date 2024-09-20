package com.delta.utils

import scala.concurrent.duration.{Duration, DurationDouble, DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Random

object BackOffComputation {
  def computeBackoff(
    attempt: Int,
    baseDelay: FiniteDuration = 100.millis,
    maxDelay: FiniteDuration = 30.seconds,
    factor: Double = 2.0
  ): Duration = {
    val backoffWithoutJitter = (baseDelay * math.pow(factor, attempt - 1)).min(maxDelay)
    val cappedBackoff =
      backoffWithoutJitter.min(25.seconds) // Cap at 25 seconds to allow for jitter
    val jitter = (Random.nextDouble() * 5).seconds // Random jitter between 0 and 5 seconds
    (cappedBackoff + jitter).min(30.seconds) // Ensure total doesn't exceed 30 seconds
  }

}
