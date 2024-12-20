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
import play.api.Play.current
import play.api.{Logger, Play}

import collection.immutable.Seq
import scala.io.Source


class AttributeController @Inject() (implicit val env: Environment[User, SessionAuthenticator])
  extends Silhouette[User, SessionAuthenticator] with ProvidesHeader {

  /**
    * Returns the clustering webpage with GUI if the user is an admin, otherwise redirects to the landing page.
    *
    * @return
    */
  def index = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      Future.successful(Ok(views.html.clustering("Project Sidewalk", request.identity)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Checks if the user is an administrator.
    *
    * @param user
    * @return Boolean indicating if the user is an admin.
   */
  def isAdmin(user: Option[User]): Boolean = user match {
    case Some(user) =>
      if (user.role.getOrElse("") == "Administrator" || user.role.getOrElse("") == "Owner") true else false
    case _ => false
  }

  /**
    * Reads a key from an env variable and compares against input key, returning true if they match.
    *
    * @return Boolean indicating whether the input key matches the true key.
    */
  def authenticate(key: String): Boolean = {
    key == Play.configuration.getString("internal-api-key").get
  }

  /**
    * Calls the appropriate clustering function(s); either single-user clustering, multi-user clustering, or both.
    *
    * @param clusteringType One of "singleUser", "multiUser", or "both".
    * @return
    */
  def runClustering(clusteringType: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      if (clusteringType == "singleUser" || clusteringType == "both") {
        runSingleUserClusteringAllUsers()
      }
      if (clusteringType == "multiUser" || clusteringType == "both") {
        runMultiUserClusteringAllRegions()
      }

      // Gets the counts of labels/attributes from the affected tables to show how many clusters were created.
      val json = clusteringType match {
        case "singleUser" => Json.obj(
          "user_labels" -> UserAttributeLabelTable.countUserAttributeLabels,
          "user_attributes" -> UserAttributeTable.countUserAttributes
        )
        case "multiUser" => Json.obj(
          "user_attributes" -> UserAttributeTable.countUserAttributes,
          "global_attributes" -> GlobalAttributeTable.countGlobalAttributes
        )
        case "both" => Json.obj(
          "user_labels" -> UserAttributeLabelTable.countUserAttributeLabels,
          "user_attributes" -> UserAttributeTable.countUserAttributes,
          "global_attributes" -> GlobalAttributeTable.countGlobalAttributes
        )
        case _ => Json.obj("error_msg" -> "Invalid clusteringType")
      }
      Future.successful(Ok(json))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Runs single user clustering for each high quality user.
    *
    * @return
    */
  def runSingleUserClusteringAllUsers() = {

    // First truncate the user_clustering_session, user_attribute, and user_attribute_label tables
    UserClusteringSessionTable.truncateTables()

    val key: String = Play.configuration.getString("internal-api-key").get

    // All users
    val goodRegisteredUsers: List[String] = StreetEdgePriorityTable.getIdsOfGoodRegisteredUsers
    val goodAnonymousUsers: List[String] = StreetEdgePriorityTable.getIdsOfGoodAnonymousUsers
    // Test users with a lot of labels
//      val goodRegisteredUsers: List[String] = List("9efaca05-53bb-492e-83ab-2b47219ee863")
//      val goodAnonymousUsers: List[String] = List("73.163.171.105")
    // Test users with fewer labels
//      val goodRegisteredUsers: List[String] = List("53b4a67b-614e-432d-9bfa-8a97e081fea5")
//      val goodAnonymousUsers: List[String] = List("24.22.251.129")
    val nUsers = goodRegisteredUsers.length + goodAnonymousUsers.length
    Logger.info("N users = " + nUsers)

    // Runs clustering for each anonymous user.
    for ((userId, i) <- goodAnonymousUsers.view.zipWithIndex) {
      Logger.info(s"Finished ${f"${100.0 * i / nUsers}%1.2f"}% of users, next: $userId.")
      val clusteringOutput =
        Seq("python", "label_clustering.py", "--key", key, "--user_id_or_ip", userId, "--is_anonymous").!!
//        Logger.info(clusteringOutput)
    }
    // Runs clustering for each registered user.
    for ((userId, i) <- goodRegisteredUsers.view.zipWithIndex) {
      Logger.info(s"Finished ${f"${100.0 * (i + goodAnonymousUsers.length) / nUsers}%1.2f"}% of users, next: $userId.")
      val clusteringOutput =
        Seq("python", "label_clustering.py", "--key", key, "--user_id_or_ip", userId).!!
//        Logger.info(clusteringOutput)
    }
    Logger.info("\nFinshed 100% of users!!\n")
  }

  /**
    * Runs multi user clustering for the user attributes in each region.
    *
    * @return
    */
  def runMultiUserClusteringAllRegions() = {

    // First truncate the global_clustering_session, global_attribute, and global_attribute_user_attribute tables.
    GlobalClusteringSessionTable.truncateTables()

    val key: String = Play.configuration.getString("internal-api-key").get
    val regionIds: List[Int] = RegionTable.selectAllNeighborhoods.map(_.regionId).sortBy(x => x)
//    val regionIds = List(199, 200, 203, 211, 261) // Small test set.
    val nRegions: Int = regionIds.length

    // Runs multi-user clustering within each region.
    for ((regionId, i) <- regionIds.view.zipWithIndex) {
      Logger.info(s"Finished ${f"${100.0 * i / nRegions}%1.2f"}% of regions, next: $regionId.")
      val clusteringOutput = Seq("python", "label_clustering.py", "--key", key, "--region_id", regionId.toString).!!
    }
    Logger.info("\nFinshed 100% of regions!!\n\n")
  }

  /**
    * Returns the set of all labels associated with the given user, in the format needed for clustering.
    *
    * @param key A key used for authentication.
    * @param userIdOrIp The username of IP address of the user who's labels should be retrieved.
    * @param isAnonymous Indicating whether the user is anonymous or registered.
    * @return
    */
  def getUserLabelsToCluster(key: String, userIdOrIp: String, isAnonymous: Boolean) = UserAwareAction.async { implicit request =>

    val json = if (authenticate(key)) {
      val labelsToCluster: List[LabelToCluster] = if (isAnonymous) {
        UserClusteringSessionTable.getAnonymousUserLabelsToCluster(userIdOrIp)
      } else {
        UserClusteringSessionTable.getRegisteredUserLabelsToCluster(userIdOrIp)
      }
      Json.arr(labelsToCluster.map(_.toJSON))
    } else {
      Json.obj("error_msg" -> "Could not authenticate.")
    }
    Future.successful(Ok(json))
  }

  /**
    * Returns the set of clusters from single-user clustering that are in this region as JSON.
    *
    * @param key A key used for authentication.
    * @param regionId The region who's labels should be retrieved.
    * @return
    */
  def getClusteredLabelsInRegion(key: String, regionId: Int) = UserAwareAction.async { implicit request =>
    val json = if (authenticate(key)) {
      val labelsToCluster: List[LabelToCluster] = UserClusteringSessionTable.getClusteredLabelsInRegion(regionId)
      Json.arr(labelsToCluster.map(_.toJSON))
    } else {
      Json.obj("error_msg" -> "Could not authenticate.")
    }
    Future.successful(Ok(json))
  }

  /**
    * Takes in results of single-user clustering, and adds the data to the relevant tables.
    *
    * @param key A key used for authentication.
    * @param userIdOrIp The username of IP address of the user who's labels were clustered.
    * @param isAnonymous Indicating whether the user is anonymous or registered.
    * @return
    */
  def postSingleUserClusteringResults(key: String, userIdOrIp: String, isAnonymous: Boolean) = UserAwareAction.async(BodyParsers.parse.json(maxLength = 1024 * 1024 * 100)) {implicit request =>
    // The maxLength argument above allows a 100MB max load size for the POST request.
    if (authenticate(key)) {
      // Validation https://www.playframework.com/documentation /2.3.x/ScalaJson
      val submission = request.body.validate[AttributeFormats.ClusteringSubmission]
      submission.fold(
        errors => {
          println("Failed to parse JSON POST request for multi-user clustering results.")
          println(Json.prettyPrint(request.body))
          Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
        },
        submission => {
          // Extract the thresholds, clusters, and labels, and put them into separate variables.
          val thresholds: Map[String, Float] = submission.thresholds.map(t => (t.labelType, t.threshold)).toMap
          val clusters: List[AttributeFormats.ClusterSubmission] = submission.clusters
          val labels: List[AttributeFormats.ClusteredLabelSubmission] = submission.labels

          // Group the labels by the cluster they were put into.
          val groupedLabels: Map[Int, List[AttributeFormats.ClusteredLabelSubmission]] = labels.groupBy(_.clusterNum)
          val now = new DateTime(DateTimeZone.UTC)
          val timestamp: Timestamp = new Timestamp(now.getMillis)

          // Add corresponding entry to the user_clustering_session table
          val userSessionId: Int = if (isAnonymous) {
            UserClusteringSessionTable.save(UserClusteringSession(0, isAnonymous, None, Some(userIdOrIp), timestamp))
          } else {
            UserClusteringSessionTable.save(UserClusteringSession(0, isAnonymous, Some(userIdOrIp), None, timestamp))
          }
          // Add the clusters to user_attribute table, and the associated user_attribute_labels after each cluster.
          for (cluster <- clusters) yield {
            val attributeId: Int =
              UserAttributeTable.save(
                UserAttribute(0,
                  userSessionId,
                  thresholds(cluster.labelType),
                  LabelTypeTable.labelTypeToId(cluster.labelType),
                  RegionTable.selectRegionIdOfClosestNeighborhood(cluster.lng, cluster.lat),
                  cluster.lat,
                  cluster.lng,
                  cluster.severity,
                  cluster.temporary
                )
              )
            // Add all the labels associated with that user_attribute to the user_attribute_label table
            groupedLabels get cluster.clusterNum match {
              case Some(group) =>
                for (label <- group) yield {
                  UserAttributeLabelTable.save(UserAttributeLabel(0, attributeId, label.labelId))
                }
              case None =>
                Logger.warn("Cluster sent with no accompanying labels. Seems wrong!")
            }
          }
          Future.successful(Ok(Json.obj("session" -> userSessionId)))
        }
      )
    } else {
      Future.successful(Ok(Json.obj("error_msg" -> "Could not authenticate.")))
    }
  }

  /**
    * Takes in results of multi-user clustering, and adds the data to the relevant tables.
    *
    * @param key A key used for authentication.
    * @param regionId The region who's labels were clustered.
    * @return
    */
  def postMultiUserClusteringResults(key: String, regionId: Int) = UserAwareAction.async(BodyParsers.parse.json(maxLength = 1024 * 1024 * 100)) {implicit request =>
    // The maxLength argument above allows a 100MB max load size for the POST request.
    if (authenticate(key)) {
      // Validation https://www.playframework.com/documentation /2.3.x/ScalaJson
      val submission = request.body.validate[AttributeFormats.ClusteringSubmission]
      submission.fold(
        errors => {
          println("Failed to parse JSON POST request for multi-user clustering results.")
          println(Json.prettyPrint(request.body))
          Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
        },
        submission => {
          // Extract the thresholds, clusters, and labels, and put them into separate variables.
          val thresholds: Map[String, Float] = submission.thresholds.map(t => (t.labelType, t.threshold)).toMap
          val clusters: List[AttributeFormats.ClusterSubmission] = submission.clusters
          val labels: List[AttributeFormats.ClusteredLabelSubmission] = submission.labels

          // Group the labels by the cluster they were put into.
          val groupedLabels: Map[Int, List[AttributeFormats.ClusteredLabelSubmission]] = labels.groupBy(_.clusterNum)
          val now = new DateTime(DateTimeZone.UTC)
          val timestamp: Timestamp = new Timestamp(now.getMillis)

          // Add corresponding entry to the global_clustering_session table
          val globalSessionId: Int = GlobalClusteringSessionTable.save(GlobalClusteringSession(0, regionId, timestamp))

          // Add the clusters to global_attribute table, and the associated user_attributes after each cluster.
          for (cluster <- clusters) yield {
            val attributeId: Int =
              GlobalAttributeTable.save(
                GlobalAttribute(0,
                  globalSessionId,
                  thresholds(cluster.labelType),
                  LabelTypeTable.labelTypeToId(cluster.labelType),
                  RegionTable.selectRegionIdOfClosestNeighborhood(cluster.lng, cluster.lat),
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
          Future.successful(Ok(Json.obj("session" -> globalSessionId)))
        }
      )
    } else {
      Future.successful(Ok(Json.obj("error_msg" -> "Could not authenticate.")))
    }
  }
}
