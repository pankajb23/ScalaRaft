package com.delta.configuration

import play.api.Configuration
import javax.inject.Inject


case class Configs @Inject()(configs: Configuration) {
  lazy val hostUrl: String = configs.get[String]("io.delta.host.url")
}
