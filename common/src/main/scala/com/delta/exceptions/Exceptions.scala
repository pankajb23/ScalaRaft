package com.delta.exceptions

object Exceptions {
  abstract class DeltaExceptions(ex: Option[Throwable], message: Option[String])
      extends RuntimeException(message.orNull, ex.orNull)

  case class IllegalStateDeltaException(ex: Option[Throwable], message: Option[String])
      extends DeltaExceptions(ex, message)
}
