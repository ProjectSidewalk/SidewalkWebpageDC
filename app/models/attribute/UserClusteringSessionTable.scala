package models.attribute

/**
  * Created by misaugstad on 4/27/17.
  */

import models.audit.{AuditTaskEnvironmentTable, AuditTaskTable}
import models.daos.slick.DBTableDefinitions.{DBUser, UserTable}
import models.label.{LabelTable, LabelTypeTable, ProblemTemporarinessTable}
import models.utils.MyPostgresDriver.simple._
import play.api.Play.current
import play.api.db.slick
import play.api.libs.json.{JsObject, Json}

import scala.slick.lifted.{ForeignKeyQuery, ProvenShape, Tag}
import scala.slick.jdbc.{GetResult, StaticQuery => Q}
import scala.language.postfixOps

case class LabelToCluster(userIdOrIp: Option[String],
                          labelId: Int,
                          labelType: String,
                          lat: Option[Float],
                          lng: Option[Float],
                          severity: Option[Int],
                          temporary: Boolean) {
  /**
    * This method converts the data into the JSON format
    *
    * @return
    */
  def toJSON: JsObject = {
    Json.obj(
      "user_id_or_ip" -> userIdOrIp,
      "label_id" -> labelId,
      "label_type" -> labelType,
      "lat" -> lat,
      "lng" -> lng,
      "severity" -> severity,
      "temporary" -> temporary
    )
  }
}

case class UserClusteringSession(userClusteringSessionId: Int,
                                 isAnonymous: Boolean, userId: Option[String], ipAddress: Option[String],
                                 timeCreated: java.sql.Timestamp)


class UserClusteringSessionTable(tag: Tag) extends Table[UserClusteringSession](tag, Some("sidewalk"), "user_clustering_session") {
  def userClusteringSessionId: Column[Int] = column[Int]("user_clustering_session_id", O.NotNull, O.PrimaryKey, O.AutoInc)
  def isAnonymous: Column[Boolean] = column[Boolean]("is_anonymous", O.NotNull)
  def userId: Column[Option[String]] = column[Option[String]]("user_id")
  def ipAddress: Column[Option[String]] = column[Option[String]]("ip_address")
  def timeCreated: Column[java.sql.Timestamp] = column[java.sql.Timestamp]("time_created", O.NotNull)

  def * : ProvenShape[UserClusteringSession] = (userClusteringSessionId, isAnonymous, userId, ipAddress, timeCreated) <>
    ((UserClusteringSession.apply _).tupled, UserClusteringSession.unapply)

  def user: ForeignKeyQuery[UserTable, DBUser] =
    foreignKey("user_clustering_session_user_id_fkey", userId, TableQuery[UserTable])(_.userId)
}

/**
  * Data access object for the UserClusteringSessionTable table
  */
object UserClusteringSessionTable {
  val db: slick.Database = play.api.db.slick.DB
  val userClusteringSessions: TableQuery[UserClusteringSessionTable] = TableQuery[UserClusteringSessionTable]

  implicit val labelToClusterConverter = GetResult[LabelToCluster](r => {
    LabelToCluster(r.nextStringOption, r.nextInt, r.nextString, r.nextFloatOption, r.nextFloatOption, r.nextIntOption, r.nextBoolean)
  })

  def getAllUserClusteringSessions: List[UserClusteringSession] = db.withTransaction { implicit session =>
    userClusteringSessions.list
  }

  /**
    * Returns labels that were placed by the specified user, in the format needed for clustering.
    *
    * @param userId
    * @return
    */
  def getRegisteredUserLabelsToCluster(userId: String): List[LabelToCluster] = db.withSession { implicit session =>

    // Gets all non-deleted, non-tutorial labels placed by the specified user.
    val labels = for {
      _task <- AuditTaskTable.auditTasks if _task.userId === userId
      _lab <- LabelTable.labelsWithoutDeletedOrOnboarding if _lab.auditTaskId === _task.auditTaskId
      _latlng <- LabelTable.labelPoints if _lab.labelId === _latlng.labelId
      _type <- LabelTable.labelTypes if _lab.labelTypeId === _type.labelTypeId
    } yield (_task.userId, _lab.labelId, _type.labelType, _latlng.lat, _latlng.lng)

    // Left joins to get severity for any labels that have them.
    val labelsWithSeverity = for {
      (_lab, _severity) <- labels.leftJoin(LabelTable.severities).on(_._2 === _.labelId)
    } yield (_lab._1, _lab._2, _lab._3, _lab._4, _lab._5, _severity.severity.?)

    // Left joins to get temporariness for any labels that have them (those that don't are marked as temporary=false).
    val labelsWithTemporariness = for {
      (_lab, _temp) <- labelsWithSeverity.leftJoin(ProblemTemporarinessTable.problemTemporarinesses).on(_._2 === _.labelId)
    } yield (_lab._1.asColumnOf[Option[String]], _lab._2, _lab._3, _lab._4, _lab._5, _lab._6, _temp.temporaryProblem.?.getOrElse(false))

    labelsWithTemporariness.list.map(LabelToCluster.tupled)
  }

  /**
    * Returns labels that were placed by the specified user, in the form needed for clustering.
    *
    * @param ipAddress
    * @return
    */
  def getAnonymousUserLabelsToCluster(ipAddress: String): List[LabelToCluster] = db.withSession { implicit session =>

    // Gets the audit tasks completed by this anonymous user.
    val userAudits: Set[Int] =
      AuditTaskEnvironmentTable.auditTaskEnvironments
        .filter(_.ipAddress === ipAddress)
        .map(_.auditTaskId)
        .list.toSet

    // Gets all non-deleted, non-tutorial labels placed by the specified user (filters using audit tasks from above).
    val labels = for {
      _task <- AuditTaskTable.auditTasks if _task.auditTaskId inSet userAudits
      _lab <- LabelTable.labelsWithoutDeletedOrOnboarding if _lab.auditTaskId === _task.auditTaskId
      _latlng <- LabelTable.labelPoints if _lab.labelId === _latlng.labelId
      _type <- LabelTable.labelTypes if _lab.labelTypeId === _type.labelTypeId
    } yield (_task.userId, _lab.labelId, _type.labelType, _latlng.lat, _latlng.lng)

    // Left joins to get severity for any labels that have them.
    val labelsWithSeverity = for {
      (_lab, _severity) <- labels.leftJoin(LabelTable.severities).on(_._2 === _.labelId)
    } yield (_lab._1, _lab._2, _lab._3, _lab._4, _lab._5, _severity.severity.?)

    // Left joins to get temporariness for any labels that have them (those that don't are marked as temporary=false).
    val labelsWithTemporariness = for {
      (_lab, _temp) <- labelsWithSeverity.leftJoin(ProblemTemporarinessTable.problemTemporarinesses).on(_._2 === _.labelId)
    } yield (_lab._1.asColumnOf[Option[String]], _lab._2, _lab._3, _lab._4, _lab._5, _lab._6, _temp.temporaryProblem.?.getOrElse(false))

    labelsWithTemporariness.list.map(LabelToCluster.tupled)
  }

  /**
    * Gets all clusters from single-user clustering that are in this region, outputs in format needed for clustering.
    *
    * @param regionId
    * @return
    */
  def getClusteredLabelsInRegion(regionId: Int): List[LabelToCluster] = db.withTransaction { implicit session =>
    val labelsInRegion = for {
      _sess <- userClusteringSessions
      _att <- UserAttributeTable.userAttributes if _sess.userClusteringSessionId === _att.userClusteringSessionId
      _type <- LabelTypeTable.labelTypes if _att.labelTypeId === _type.labelTypeId
      if _att.regionId === regionId
    } yield (
      _sess.ipAddress.ifNull(_sess.userId),
      _att.userAttributeId,
      _type.labelType,
      _att.lat.?,
      _att.lng.?,
      _att.severity,
      _att.temporary
    )

    labelsInRegion.list.map(LabelToCluster.tupled)
  }

  /**
    * Truncates user_clustering_session, user_attribute, user_attribute_label, and global_attribute_user_attribute.
    */
  def truncateTables(): Unit = db.withTransaction { implicit session =>
    Q.updateNA("TRUNCATE TABLE user_clustering_session CASCADE").execute
  }

  def save(newSess: UserClusteringSession): Int = db.withTransaction { implicit session =>
    val newId: Int = (userClusteringSessions returning userClusteringSessions.map(_.userClusteringSessionId)) += newSess
    newId
  }
}
