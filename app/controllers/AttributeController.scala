package controllers

import java.sql.Timestamp
import javax.inject.Inject

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import play.api.libs.json._
import controllers.headers.ProvidesHeader
import models.user.User

import scala.concurrent.Future
import scala.sys.process._
import play.api.mvc._
import play.api.libs.json.Json
import formats.json.AttributeFormats
import models.attribute._
import models.label.LabelTypeTable
import models.region.RegionTable
import models.street.StreetEdgePriorityTable
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger

import collection.immutable.Seq
import scala.io.Source


class AttributeController @Inject() (implicit val env: Environment[User, SessionAuthenticator])
  extends Silhouette[User, SessionAuthenticator] with ProvidesHeader {

  // Helper methods
  def isAdmin(user: Option[User]): Boolean = user match {
    case Some(user) =>
      if (user.role.getOrElse("") == "Administrator" || user.role.getOrElse("") == "Owner") true else false
    case _ => false
  }

  /**
    * Reads a key from a file and compares against input key, returning true if they match.
    *
    * @param key
    * @return
    */
  def authenticate(key: String): Boolean = {
    val bufferedSource = Source.fromFile("special_api_key.txt")
    val lines = bufferedSource.getLines()
    val keyMatch: Boolean = if (lines.hasNext) lines.next() == key else false
    bufferedSource.close
    keyMatch
  }

  /**
    * Calls the appropriate clustering functions; either single-user clustering, multi-user clustering, or both.
    *
    * @param clusteringType
    * @return
    */
  def runClustering(clusteringType: String) = UserAwareAction.async { implicit request =>
    if (clusteringType == "singleUser" || clusteringType == "both") {
      runSingleUserClusteringAllUsers()
    }
    if (clusteringType == "multiUser" || clusteringType == "both") {
      runMultiUserClusteringAllRegions()
    }

    val testJson = Json.obj("clustering_type" -> clusteringType)
    Future.successful(Ok(testJson))
  }

  /**
    * Runs single user clustering for each high quality user.
    *
    * @return
    */
  def runSingleUserClusteringAllUsers() = {

    // First truncate the user_clustering_session table
    UserClusteringSessionTable.truncateTable()

    val goodRegisteredUsers: List[String] = StreetEdgePriorityTable.getIdsOfGoodRegisteredUsers
    val goodAnonymousUsers: List[String] = StreetEdgePriorityTable.getIdsOfGoodAnonymousUsers
//    val goodRegisteredUsers: List[String] = List("9efaca05-53bb-492e-83ab-2b47219ee863")
//    val goodAnonymousUsers: List[String] = List("73.163.171.105")
    val nUsers = goodRegisteredUsers.length + goodAnonymousUsers.length
    println("N users = " + nUsers)

    for ((userId, i) <- goodAnonymousUsers.view.zipWithIndex) {
      println(s"Finished ${f"${100.0 * i / nUsers}%1.2f"}% of users, next: $userId.")
      val clusteringOutput = Seq("python", "label_clustering.py", "--user_id_or_ip", userId, "--is_anonymous").!!
//      println(clusteringOutput)
    }
    for ((userId, i) <- goodRegisteredUsers.view.zipWithIndex) {
      println(s"Finished ${f"${100.0 * (i + goodAnonymousUsers.length) / nUsers}%1.2f"}% of users, next: $userId.")
      val clusteringOutput = Seq("python", "label_clustering.py", "--user_id_or_ip", userId).!!
//      println(clusteringOutput)
    }
    println("\nFinshed 100% of users!!\n")
  }

  /**
    * Runs multi user clustering for the user attributes in each region.
    *
    * @return
    */
  def runMultiUserClusteringAllRegions() = {

    // First truncate the global_clustering_session table
    GlobalClusteringSessionTable.truncateTable()
    val regionIds: List[Int] = RegionTable.selectAllNeighborhoods.map(_.regionId).sortBy(x => x)
    //    val regionIds = List(199, 200,s 203, 211, 261)
    val nRegions: Int = regionIds.length

    for ((regionId, i) <- regionIds.view.zipWithIndex) {
      println(s"Finished ${f"${100.0 * i / nRegions}%1.2f"}% of regions, next: $regionId.")
      val clusteringOutput = ("python label_clustering.py --region_id " + regionId).!!
    }
    println("\nFinshed 100% of regions!!\n\n")
  }

  /**
    * Returns the set of all labels associated with the given user, in the format needed for clustering.
    *
    * @param userIdOrIp
    * @return
    */
  def getUserLabelsToCluster(userIdOrIp: String, isAnonymous: Boolean) = UserAwareAction.async { implicit request =>

    // TODO add check for key
    val labelsToCluster: List[LabelToCluster] = if(isAnonymous) {
      UserClusteringSessionTable.getAnonymousUserLabelsToCluster(userIdOrIp)
    } else {
      UserClusteringSessionTable.getRegisteredUserLabelsToCluster(userIdOrIp)
    }
    val json = Json.arr(labelsToCluster.map(_.toJSON))
    Future.successful(Ok(json))
  }

  /**
    * Returns the set of clusters from single-user clustering that are in this region as JSON.
    *
    * @param regionId
    * @return
    */
  def getClusteredLabelsInRegion(regionId: Int) = UserAwareAction.async { implicit request =>
    val labelsToCluster: List[LabelToCluster] = UserClusteringSessionTable.getClusteredLabelsInRegion(regionId)
    val json = Json.arr(labelsToCluster.map(_.toJSON))
    Future.successful(Ok(json))
  }

  /**
    * Takes in results of single-user clustering, and adds the data to the relevant tables.
    *
    * @param userIdOrIp
    * @param isAnonymous
    * @return
    */
  def postSingleUserClusteringResults(userIdOrIp: String, isAnonymous: Boolean) = UserAwareAction.async(BodyParsers.parse.json(maxLength = 1024 * 1024 * 100)) {implicit request =>
    // 100MB max size
    // Validation https://www.playframework.com/documentation /2.3.x/ScalaJson
    val submission = request.body.validate[AttributeFormats.ClusteringSubmission]
    submission.fold(
      errors => {
        println("Failed to parse JSON POST request for multi-user clustering results.")
        println(Json.prettyPrint(request.body))
        Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
      },
      submission => {
        val thresholds: Map[String, Float] = submission.thresholds.map(t => (t.labelType, t.threshold)).toMap
        val clusters: List[AttributeFormats.ClusterSubmission] = submission.clusters
        val labels: List[AttributeFormats.ClusteredLabelSubmission] = submission.labels

        val groupedLabels: Map[Int, List[AttributeFormats.ClusteredLabelSubmission]] = labels.groupBy(_.clusterNum)
        val now = new DateTime(DateTimeZone.UTC)
        val timestamp: Timestamp = new Timestamp(now.getMillis)

        // Add corresponding entry to the user_clustering_session table
        val userSessionId: Int = if (isAnonymous) {
          UserClusteringSessionTable.save(UserClusteringSession(0, isAnonymous, None, Some(userIdOrIp), timestamp))
        } else {
          UserClusteringSessionTable.save(UserClusteringSession(0, isAnonymous, Some(userIdOrIp), None, timestamp))
        }
        // Add the clusters to user_attribute table
        for (cluster <- clusters) yield {
          val attributeId: Int =
            UserAttributeTable.save(
              UserAttribute(0,
                userSessionId,
                thresholds(cluster.labelType),
                LabelTypeTable.labelTypeToId(cluster.labelType),
                cluster.lat,
                cluster.lng,
                cluster.severity,
                cluster.temporary
              )
            )
          // Add all the associated labels to the user_attribute_label table
          groupedLabels get cluster.clusterNum match {
            case Some(group) =>
              for (label <- group) yield {
                UserAttributeLabelTable.save(UserAttributeLabel(0, attributeId, label.labelId))
              }
            case None =>
              Logger.warn("Cluster sent with no accompanying labels. Seems wrong!")
          }
        }
        val json = Json.obj("session" -> userSessionId)
        Future.successful(Ok(json))
      }
    )
  }

  /**
    * Takes in results of multi-user clustering, and adds the data to the relevant tables.
    *
    * @param regionId
    * @return
    */
  def postMultiUserClusteringResults(regionId: Int) = UserAwareAction.async(BodyParsers.parse.json(maxLength = 1024 * 1024 * 100)) {implicit request =>
    // 100MB max size
    // Validation https://www.playframework.com/documentation /2.3.x/ScalaJson
    val submission = request.body.validate[AttributeFormats.ClusteringSubmission]
    submission.fold(
      errors => {
        println("Failed to parse JSON POST request for multi-user clustering results.")
        println(Json.prettyPrint(request.body))
        Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
      },
      submission => {
        val thresholds: Map[String, Float] = submission.thresholds.map(t => (t.labelType, t.threshold)).toMap
        val clusters: List[AttributeFormats.ClusterSubmission] = submission.clusters
        val labels: List[AttributeFormats.ClusteredLabelSubmission] = submission.labels

        val groupedLabels: Map[Int, List[AttributeFormats.ClusteredLabelSubmission]] = labels.groupBy(_.clusterNum)
        val now = new DateTime(DateTimeZone.UTC)
        val timestamp: Timestamp = new Timestamp(now.getMillis)

        // Add corresponding entry to the global_clustering_session table
        val globalSessionId: Int = GlobalClusteringSessionTable.save(GlobalClusteringSession(0, regionId, timestamp))

        // Add the clusters to global_attribute table
        for (cluster <- clusters) yield {
          val attributeId: Int =
            GlobalAttributeTable.save(
              GlobalAttribute(0,
                globalSessionId,
                thresholds(cluster.labelType),
                LabelTypeTable.labelTypeToId(cluster.labelType),
                cluster.lat,
                cluster.lng,
                cluster.severity,
                cluster.temporary)
            )
          // Add all the associated labels to the global_attribute_user_attribute table
          groupedLabels get cluster.clusterNum match {
            case Some(group) =>
              for (label <- group) yield {
                GlobalAttributeUserAttributeTable.save(GlobalAttributeUserAttribute(0, attributeId, label.labelId))
              }
            case None =>
              Logger.warn("Cluster sent with no accompanying labels. Seems wrong!")
          }
        }
        val json = Json.obj("session" -> globalSessionId)
        Future.successful(Ok(json))
      }
    )
  }
}