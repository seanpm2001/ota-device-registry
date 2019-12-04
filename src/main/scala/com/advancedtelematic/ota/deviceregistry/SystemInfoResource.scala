/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.time.Instant

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.ota.deviceregistry.common.Errors.{Codes, MissingSystemInfo}
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository
import com.advancedtelematic.ota.deviceregistry.db.SystemInfoRepository.NetworkInfo
import io.circe.{Decoder, Encoder, Json}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import cats.syntax.show._
import cats.syntax.option._
import com.advancedtelematic.libats.http.Errors.RawError
import com.advancedtelematic.libats.messaging_datatype.Messages.{AktualizrConfigChanged, DeviceSystemInfoChanged}
import toml.Toml
import http.`application/toml`

import scala.concurrent.ExecutionContext

case class AktualizrConfig(uptane: Uptane, pacman: Pacman)
case class Uptane(polling_sec: Int, force_install_completion: Boolean)
case class Pacman(`type`: String)

class SystemInfoResource(
    messageBus: MessageBusPublisher,
    authNamespace: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[DeviceId]
)(implicit db: Database, actorSystem: ActorSystem, ec: ExecutionContext) {
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val systemInfoUpdatePublisher = new SystemInfoUpdatePublisher(messageBus)

  implicit val NetworkInfoEncoder: Encoder[NetworkInfo] = Encoder.instance { x =>
    import io.circe.syntax._
    Json.obj(
      "local_ipv4" -> x.localIpV4.asJson,
      "mac"        -> x.macAddress.asJson,
      "hostname"   -> x.hostname.asJson
    )
  }

  implicit val NetworkInfoDecoder: Decoder[DeviceId => NetworkInfo] = Decoder.instance { c =>
    for {
      ip       <- c.get[String]("local_ipv4")
      mac      <- c.get[String]("mac")
      hostname <- c.get[String]("hostname")
    } yield (uuid: DeviceId) => NetworkInfo(uuid, ip, hostname, mac)
  }

  implicit val aktualizrConfigUnmarshaller: FromEntityUnmarshaller[AktualizrConfig] = Unmarshaller.stringUnmarshaller.map { s =>
    import toml.Codecs._

    Toml.parseAs[AktualizrConfig](s) match {
      case Left((_,msg)) => throw RawError(Codes.MalformedInput, StatusCodes.BadRequest, msg)
      case Right(aktualizrConfig) => aktualizrConfig
    }
  }.forContentTypes(`application/toml`)

  def fetchSystemInfo(uuid: DeviceId): Route = {
    val comp = db.run(SystemInfoRepository.findByUuid(uuid)).recover {
      case MissingSystemInfo => Json.obj()
    }
    complete(comp)
  }

  def createSystemInfo(ns: Namespace, uuid: DeviceId, data: Json): Route = {
    val f = db
      .run(SystemInfoRepository.create(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          systemInfoUpdatePublisher.publishSafe(ns, uuid, data.some)
      }
    complete(Created -> f)
  }

  def updateSystemInfo(ns: Namespace, uuid: DeviceId, data: Json): Route = {
    val f = db
      .run(SystemInfoRepository.update(uuid, data))
      .andThen {
        case scala.util.Success(_) =>
          systemInfoUpdatePublisher.publishSafe(ns, uuid, data.some)
      }
    complete(OK -> f)
  }

  def api: Route =
    (pathPrefix("devices") & authNamespace) { ns =>
      val scope = Scopes.devices(ns)
      deviceNamespaceAuthorizer { uuid =>
        pathPrefix("system_info") {
          pathEnd {
            scope.get {
              fetchSystemInfo(uuid)
            } ~
            scope.post {
              entity(as[Json]) { body =>
                createSystemInfo(ns.namespace, uuid, body)
              }
            } ~
            scope.put {
              entity(as[Json]) { body =>
                updateSystemInfo(ns.namespace, uuid, body)
              }
            }
          } ~
          path("network") {
            get {
              val networkInfo = db.run(SystemInfoRepository.getNetworkInfo(uuid))
              completeOrRecoverWith(networkInfo) {
                case MissingSystemInfo =>
                  complete(OK -> NetworkInfo(uuid, "", "", ""))
                case t =>
                  failWith(t)
              }
            } ~
            (put & entity(as[DeviceId => NetworkInfo])) { payload =>
              val result = db
                .run(SystemInfoRepository.setNetworkInfo(payload(uuid)))
                .andThen {
                  case scala.util.Success(Done) =>
                    messageBus.publish(DeviceSystemInfoChanged(ns.namespace, uuid, None))
                }
              complete(NoContent -> result)
            }
          } ~
          path("config") {
            pathEnd {
              post {
                entity(as[AktualizrConfig]) { config =>
                  val result = messageBus.publish(AktualizrConfigChanged(ns.namespace, uuid, config.uptane.polling_sec,
                                                                         config.uptane.force_install_completion,
                                                                         config.pacman.`type`, Instant.now))
                  complete(result.map(_ => NoContent))
                }
              }
            }
          }
        }
      }
    }

  def mydeviceRoutes: Route = authNamespace { authedNs => // don't use this as a namespace
    pathPrefix("mydevice" / DeviceId.Path) { uuid =>
      (put & path("system_info") & authedNs.oauthScope(s"ota-core.${uuid.show}.write")) {
        entity(as[Json]) { body =>
          updateSystemInfo(authedNs.namespace, uuid, body)
        }
      }
    }
  }

  val route: Route = api ~ mydeviceRoutes
}
