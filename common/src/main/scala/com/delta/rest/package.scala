package com.delta

import play.api.libs.json.{Json, OFormat}

package object rest {
  case class Member(id: String)

  object Member {
    lazy val f: OFormat[Member] = Json.format[Member]
  }
}
