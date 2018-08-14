/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.advancedtelematic.libats.auth.{AuthedNamespaceScope, Scopes}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka._
import com.advancedtelematic.ota.deviceregistry.data.Device.DeviceId
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.GroupInfoRepository
import io.circe.{Decoder, Encoder}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class GroupsResource(
    namespaceExtractor: Directive1[AuthedNamespaceScope],
    deviceNamespaceAuthorizer: Directive1[Uuid]
)(implicit ec: ExecutionContext, db: Database)
    extends Directives {

  import UuidDirectives._
  import com.advancedtelematic.libats.http.RefinedMarshallingSupport._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val GroupIdPath = {
    def groupAllowed(groupId: GroupId): Future[Namespace] = db.run(GroupInfoRepository.groupInfoNamespace(groupId))

    pathPrefix(GroupId.Path).flatMap { groupId =>
      allowExtractor(namespaceExtractor, provide(groupId), groupAllowed)
    }
  }

  implicit val groupTypeParamUnmarshaller: Unmarshaller[String, GroupType] =
    Unmarshaller.strict[String, GroupType](GroupType.withName)

  val groupMembership = new GroupMembership()

  def getDevicesInGroup(groupId: GroupId, includeDeviceId: Boolean): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      val deviceIds = groupMembership.listDevices(groupId, offset, limit)

      onSuccess(deviceIds) { res =>
        if(includeDeviceId)
          complete {
            res.map { case (uuid, deviceId) => GroupDevice(uuid, deviceId) }
          }
        else
          complete(res.map(_._1))
      }

    }

  def listGroups(ns: Namespace): Route =
    parameters(('offset.as[Long].?, 'limit.as[Long].?)) { (offset, limit) =>
      complete(db.run(GroupInfoRepository.list(ns, offset, limit)))
    }

  def getGroup(groupId: GroupId): Route =
    complete(db.run(GroupInfoRepository.findByIdAction(groupId)))

  def createGroup(groupName: Name,
                  namespace: Namespace,
                  groupType: GroupType,
                  expression: Option[GroupExpression]): Route =
    complete(StatusCodes.Created -> groupMembership.create(groupName, namespace, groupType, expression))

  def renameGroup(groupId: GroupId, newGroupName: Name): Route =
    complete(db.run(GroupInfoRepository.renameGroup(groupId, newGroupName)))

  def countDevices(groupId: GroupId): Route =
    complete(groupMembership.countDevices(groupId))

  def addDeviceToGroup(groupId: GroupId, deviceUuid: Uuid): Route =
    complete(groupMembership.addGroupMember(groupId, deviceUuid))

  def removeDeviceFromGroup(groupId: GroupId, deviceId: Uuid): Route =
    complete(groupMembership.removeGroupMember(groupId, deviceId))

  val route: Route =
    (pathPrefix("device_groups") & namespaceExtractor) { ns =>
      val scope = Scopes.devices(ns)
      (scope.post & entity(as[CreateGroup]) & pathEnd) { req =>
        createGroup(req.name, ns.namespace, req.groupType, req.expression)
      } ~
      (scope.get & pathEnd) {
        listGroups(ns.namespace)
      } ~
      GroupIdPath { groupId =>
        (scope.get & pathEndOrSingleSlash) {
          getGroup(groupId)
        } ~
        pathPrefix("devices") {
          scope.get {
            optionalHeaderValueByType(classOf[Accept]) { accept =>

              val requestsDeviceId = accept.exists(_.mediaRanges.exists { mr =>
                mr.matches(MediaTypes.`application/json`) && mr.params.get("vin").contains("1")
              })

              getDevicesInGroup(groupId, requestsDeviceId)
            }
          } ~
          deviceNamespaceAuthorizer { deviceUuid =>
            scope.post {
              addDeviceToGroup(groupId, deviceUuid)
            } ~
            scope.delete {
              removeDeviceFromGroup(groupId, deviceUuid)
            }
          }
        } ~
        (scope.put & path("rename") & parameter('groupName.as[Name])) { groupName =>
          renameGroup(groupId, groupName)
        } ~
        (scope.get & path("count") & pathEnd) {
          countDevices(groupId)
        }
      }
    }
}

case class GroupDevice(uuid: Uuid, id: DeviceId)

object GroupDevice {
  import io.circe.generic.semiauto._
  import com.advancedtelematic.libats.codecs.CirceAnyVal._

  implicit val groupDeviceEncoder: Encoder[GroupDevice] = deriveEncoder
  implicit val groupDeviceDecoder: Decoder[GroupDevice] = deriveDecoder
}

case class CreateGroup(name: Group.Name, groupType: GroupType, expression: Option[GroupExpression])

object CreateGroup {
  import GroupType._
  import com.advancedtelematic.circe.CirceInstances._
  import com.advancedtelematic.libats.codecs.CirceRefined._
  import io.circe.generic.semiauto._

  implicit val createGroupEncoder: Encoder[CreateGroup] = deriveEncoder
  implicit val createGroupDecoder: Decoder[CreateGroup] = deriveDecoder
}