/*
 * Copyright 2013 Sentric. See LICENSE for details.
 */

package models

import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HRegionInfo, HServerLoad}
import scala.collection.mutable.ListBuffer
import play.api.Logger
import org.codehaus.jackson.annotate.JsonIgnoreProperties
import scala.collection._
import play.api.libs.json.{JsObject, Writes}
import play.api.libs.json.Json._
import models.hbase.RegionServer
import globals.hBaseContext


@JsonIgnoreProperties(Array("parsedRegionName", "regionServer", "regionLoad", "info"))
case class Region(val regionServer: RegionServer, val regionLoad: HServerLoad.RegionLoad) {

  val regionName        = Bytes.toStringBinary(regionLoad.getName)

  val parsedRegionName  = RegionName(regionName)

  val serverName        = regionServer.serverName
  val serverHostName    = regionServer.hostName
  val serverPort        = regionServer.port
  val serverInfoPort    = regionServer.infoPort

  val storefiles        = regionLoad.getStorefiles
  val stores            = regionLoad.getStores
  val storefileSizeMB   = regionLoad.getStorefileSizeMB
  val memstoreSizeMB    = regionLoad.getMemStoreSizeMB

  val parsedElements    = HRegionInfo.parseRegionName(regionLoad.getName)

  val tableName         = parsedRegionName.tableName
  val startKey          = parsedRegionName.startKey
  val regionIdTimestamp = parsedRegionName.regionIdTimestamp

  // Kind ot regionName, without the startKey, to avoid strange routing issues.
  // This might be safely used within URIs
  val regionURI         = tableName + ",," + regionIdTimestamp + "." +
                            parsedRegionName.encodedName

  lazy val serverInfoUrl = "http://" + serverHostName + ":" + serverInfoPort

  lazy val info: RegionInfo = {
    val hRegionInfo =
      hBaseContext.hBase
        .withAdmin { _.getConnection.getRegionLocation(Bytes.toBytes(tableName), parsedElements(1), false)}
        .getRegionInfo
    RegionInfo(hRegionInfo)
  }
}

object Region {
  private var cache: Map[String, ListBuffer[Region]] = null // TODO replace http://www.playframework.com/documentation/2.0.1/ScalaCache

  def all(): Seq[Region] = {
    if(cache == null) {
      Logger.error("Region Cache not yet ready!")
      return List()
    }

    cache.values.flatten.toSeq
  }

  def findByName(regionName: String): Option[Region] =
    all().find((region) => RegionName(region.regionName) == RegionName(regionName))

  def forTable(tableName: String): Seq[Region] = {
    if(cache == null) {
      Logger.error("Region Cache not yet ready!")
      return List()
    }

    cache.get(tableName).get.toSeq
  }

  def updateCache() = {
    var newCache = Map[String, ListBuffer[Region]]()
    hBaseContext.hBase.eachRegionServer { regionServer =>
      regionServer.regionsLoad.foreach { regionLoad =>
        val region: Region = Region(regionServer, regionLoad)
        var regions: ListBuffer[Region] = newCache.get(region.tableName).getOrElse(ListBuffer[Region]())
        regions += region
        newCache += region.tableName -> regions
      }
    }
    cache = newCache
  }
}

case class RegionInfo(wrapped:HRegionInfo) {
  def endKey() = Bytes.toStringBinary(wrapped.getEndKey)
  def startKey() = Bytes.toStringBinary(wrapped.getStartKey)
  def version() = wrapped.getVersion
  def regionId() = wrapped.getRegionId
  def regionName() = wrapped.getRegionNameAsString
}


case class RegionName(tableName: String, startKey: String, regionIdTimestamp: Long, encodedName: String) {
  override def equals(that: Any): Boolean =
    that match {
      case r: RegionName => r.encodedName == this.encodedName &&
                              r.tableName == this.tableName &&
                              r.regionIdTimestamp == this.regionIdTimestamp
      case _ => false
    }
}

object RegionName {
  /**
   * Region name in HBase is composed of "<tableName>,<startKey>,<regionIdTimestamp>.<encodedName>."
   */
  def apply(regionName: String) : RegionName = {

    // Parse out comma separated components
    val commaParts = regionName.split(",")

    // Last comma separated components contains <regionIdTimestamp>.<encodedName>."
    // We only need the first two dot splitted components sicne the last one is empty
    // (note the dot at the end...)
    val regionIdTimestampAndEncodedName = commaParts.last.split("\\.").view(0, 2)

    RegionName(
      tableName = commaParts.head,
      // startKey can contain commas itself, so we join all components except the first and last with a ","
      startKey = commaParts.view(1, commaParts.length - 1).reduceLeft(_ + "," + _),
      regionIdTimestamp = regionIdTimestampAndEncodedName.head.toLong,
      encodedName = regionIdTimestampAndEncodedName.last
    )
  }

  implicit val regionNameWrites: Writes[RegionName] = new Writes[RegionName] {
     def writes(rn: RegionName) =
      toJson(JsObject(Seq(
        "tableName" -> toJson(rn.tableName),
        "startKey" -> toJson(rn.startKey),
        "regionIdTimestamp" -> toJson(rn.regionIdTimestamp),
        "encodedName" -> toJson(rn.encodedName)
      )))
  }
}
