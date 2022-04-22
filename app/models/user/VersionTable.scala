package models.user

import java.sql.Timestamp
import models.utils.MyPostgresDriver.simple._
//import play.api.Play
import play.api.Play.current

case class Version(versionId: String, versionStartTime: Timestamp, description: Option[String])

class VersionTable(tag: Tag) extends Table[Version](tag, Some("sidewalk"), "version") {
  def versionId = column[String]("version_id", O.PrimaryKey)
  def versionStartTime = column[Timestamp]("version_start_time", O.NotNull)
  def description = column[Option[String]]("description")

  def * = (versionId, versionStartTime, description) <> ((Version.apply _).tupled, Version.unapply)
}

/**
 * Data access object for the version table.
 */
object VersionTable {
  val db = play.api.db.slick.DB
  val versions = TableQuery[VersionTable]

  /**
   * Returns current version ID.
   */
  def currentVersionId(): String = db.withSession { implicit session =>
    versions.sortBy(_.versionStartTime.desc).first.versionId
  }

  /**
   * Returns timestamp of most recent update.
   */
  def currentVersionTimestamp(): String = db.withSession { implicit session =>
    versions.sortBy(_.versionStartTime.desc).first.versionStartTime.toString
  }
}
