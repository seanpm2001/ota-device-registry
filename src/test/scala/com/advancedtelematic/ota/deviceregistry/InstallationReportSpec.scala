package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.model.StatusCodes._
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.deviceInstallationReportDecoder
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceInstallationReport
import com.advancedtelematic.ota.deviceregistry.daemon.DeviceInstallationReportListener
import com.advancedtelematic.ota.deviceregistry.data.Codecs.installationStatDecoder
import com.advancedtelematic.ota.deviceregistry.data.DataType.{InstallationStat, InstallationStatsLevel}
import com.advancedtelematic.ota.deviceregistry.data.InstallationReportGenerators
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

class InstallationReportSpec extends ResourcePropSpec with ScalaFutures with Eventually with InstallationReportGenerators {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(50, Millis))

  val listener = new DeviceInstallationReportListener()

  property("should save device reports and retrieve failed stats per devices") {
    val correlationId = genCorrelationId.sample.get
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3")
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.sample.get)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Device) ~> route ~> check {
        status shouldBe OK
        val expected =
          Seq(InstallationStat("0", 1, true), InstallationStat("1", 1, false), InstallationStat("2", 2, false), InstallationStat("3", 3, false))
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save device reports and retrieve failed stats per ECUs") {
    val correlationId = genCorrelationId.sample.get
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3")
    val deviceReports = resultCodes.map(genDeviceInstallationReport(correlationId, _)).map(_.sample.get)

    deviceReports.foreach(listener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Ecu) ~> route ~> check {
        status shouldBe OK
        val expected =
          Seq(InstallationStat("0", 1, true), InstallationStat("1", 1, false), InstallationStat("2", 2, false), InstallationStat("3", 3, false))
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  property("should save the whole message as a blob and get back the history for a device") {
    val deviceId       = createDeviceOk(genDeviceT.sample.get)
    val correlationIds = Gen.listOfN(50, genCorrelationId).sample.get
    val deviceReports  = correlationIds.map(cid => genDeviceInstallationReport(cid, "0", deviceId)).map(_.sample.get)

    deviceReports.foreach(listener.apply)

    eventually {
      getReportBlob(deviceId) ~> route ~> check {
        status shouldBe OK
        responseAs[PaginationResult[DeviceInstallationReport]].values should contain allElementsOf deviceReports
      }
    }
  }

}