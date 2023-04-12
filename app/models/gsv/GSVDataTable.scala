package models.gsv

import models.label.LabelTable
import models.utils.MyPostgresDriver.simple._
import play.api.Play.current

import scala.slick.lifted.ForeignKeyQuery

case class GSVData(gsvPanoramaId: String, imageWidth: Option[Int], imageHeight: Option[Int],
                   tileWidth: Option[Int], tileHeight: Option[Int], originHeading: Option[Float],
                   originPitch: Option[Float], imageDate: String, copyright: String)

class GSVDataTable(tag: Tag) extends Table[GSVData](tag, Some("sidewalk"), "gsv_data") {
  def gsvPanoramaId = column[String]("gsv_panorama_id", O.PrimaryKey)
  def imageWidth = column[Option[Int]]("image_width")
  def imageHeight = column[Option[Int]]("image_height")
  def tileWidth = column[Option[Int]]("tile_width")
  def tileHeight = column[Option[Int]]("tile_height")
  def originHeading = column[Option[Float]]("origin_heading")
  def originPitch = column[Option[Float]]("origin_pitch")
  def imageDate = column[String]("image_date", O.NotNull)
  def copyright = column[String]("copyright", O.NotNull)

  def * = (gsvPanoramaId, imageWidth, imageHeight, tileWidth, tileHeight, originHeading, originPitch, imageDate, copyright) <>
    ((GSVData.apply _).tupled, GSVData.unapply)
}

object GSVDataTable {
  val db = play.api.db.slick.DB
  val gsvDataRecords = TableQuery[GSVDataTable]

  /**
   * List all panos with labels with some metadata. For the metadata that we get from the label table (panorama_lat,
   * panorama_lng, photographer_heading, and photographer_pitch), we are getting different values from Google's API over
   * time. We are not totally sure why, but for now we are grabbing the most recent metadata we've gotten from Google.
   *
   * @return
   */
  def getAllPanos(): List[(String, Option[Int], Option[Int], Option[Float], Option[Float], Option[Float], Option[Float])] = db.withSession { implicit session =>
    val mostRecentLabels = LabelTable.labels
      .filter(_.gsvPanoramaId =!= "tutorial")
      .groupBy(_.gsvPanoramaId).map(_._2.map(_.labelId).max)
      .leftJoin(LabelTable.labels).on(_ === _.labelId)
      .map(_._2)

    // Left join with the most recent labels that we found above, grabbing the metadata.
    gsvDataRecords
      .filter(_.gsvPanoramaId =!= "tutorial")
      .innerJoin(mostRecentLabels).on(_.gsvPanoramaId === _.gsvPanoramaId)
      .map { case (g, l) => (
        g.gsvPanoramaId, g.imageWidth, g.imageHeight, l.panoramaLat.?, l.panoramaLng.?, l.photographerHeading.?, l.photographerPitch.?
      )
      }.list
  }

  /**
    * This method checks if the given panorama id already exists in the table
    * @param panoramaId Google Street View panorama Id
    * @return
    */
  def panoramaExists(panoramaId: String): Boolean = db.withTransaction { implicit session =>
    gsvDataRecords.filter(_.gsvPanoramaId === panoramaId).list.nonEmpty
  }

  def save(data: GSVData): String = db.withTransaction { implicit session =>
    gsvDataRecords += data
    data.gsvPanoramaId
  }

}
