package controllers

import java.util.UUID
import javax.inject.Inject
import java.net.URLDecoder

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import com.vividsolutions.jts.geom.Coordinate
import controllers.headers.ProvidesHeader
import formats.json.TaskFormats._
import formats.json.UserRoleSubmissionFormats._
import formats.json.LabelFormats._
import models.attribute.{GlobalAttribute, GlobalAttributeTable}
import models.audit.{AuditTaskInteractionTable, AuditTaskTable, InteractionWithLabel}
import models.daos.slick.DBTableDefinitions.UserTable
import models.gsv.GSVDataTable
import models.label.LabelTable.{LabelMetadata, LabelCVMetadata}
import models.label.{LabelPointTable, LabelTable, LabelTypeTable}
import models.mission.MissionTable
import models.region.RegionCompletionTable
import models.street.StreetEdgeTable
import models.user.{RoleTable, User, UserRoleTable, WebpageActivityTable}
import models.daos.UserDAOImpl
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import play.api.libs.json.{JsArray, JsError, JsObject, JsValue, Json}
import play.extras.geojson
import play.api.mvc.BodyParsers

import scala.concurrent.Future

/**
  * Todo. This controller is written quickly and not well thought out. Someone could polish the controller together with the model code that was written kind of ad-hoc.
  * @param env
  */
class AdminController @Inject() (implicit val env: Environment[User, SessionAuthenticator])
  extends Silhouette[User, SessionAuthenticator] with ProvidesHeader {

  // Helper methods
  def isAdmin(user: Option[User]): Boolean = user match {
    case Some(user) =>
      if (user.role.getOrElse("") == "Administrator" || user.role.getOrElse("") == "Owner") true else false
    case _ => false
  }

  // Pages
  def index = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      Future.successful(Ok(views.html.admin.index("Project Sidewalk", request.identity)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def userProfile(username: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      UserTable.find(username) match {
        case Some(user) => Future.successful(Ok(views.html.admin.user("Project Sidewalk", request.identity, Some(user))))
        case _ => Future.successful(Ok(views.html.admin.user("Project Sidewalk", request.identity)))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def task(taskId: Int) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      AuditTaskTable.find(taskId) match {
        case Some(task) => Future.successful(Ok(views.html.admin.task("Project Sidewalk", request.identity, task)))
        case _ => Future.successful(Redirect("/"))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  // JSON APIs

  /**
    * Get a list of all labels
    *
    * @return
    */
  def getAllLabels = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val labels = LabelTable.selectLocationsAndSeveritiesOfLabels
      val features: List[JsObject] = labels.map { label =>
        val point = geojson.Point(geojson.LatLng(label.lat.toDouble, label.lng.toDouble))
        val properties = Json.obj(
          "audit_task_id" -> label.auditTaskId,
          "label_id" -> label.labelId,
          "gsv_panorama_id" -> label.gsvPanoramaId,
          "label_type" -> label.labelType,
          "severity" -> label.severity
        )
        Json.obj("type" -> "Feature", "geometry" -> point, "properties" -> properties)
      }
      val featureCollection = Json.obj("type" -> "FeatureCollection", "features" -> features)
      Future.successful(Ok(featureCollection))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Get a list of all global attributes
    *
    * @return
    */
  def getAllAttributes = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val attributes: List[GlobalAttribute] = GlobalAttributeTable.getAllGlobalAttributes
      val features: List[JsObject] = attributes.map { attribute =>
        val point = geojson.Point(geojson.LatLng(attribute.lat.toDouble, attribute.lng.toDouble))
        val properties = Json.obj(
          "attribute_id" -> attribute.globalAttributeId,
          "label_type" -> LabelTypeTable.labelTypeIdToLabelType(attribute.labelTypeId),
          "severity" -> attribute.severity
        )
        Json.obj("type" -> "Feature", "geometry" -> point, "properties" -> properties)
      }
      val featureCollection = Json.obj("type" -> "FeatureCollection", "features" -> features)
      Future.successful(Ok(featureCollection))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Returns audit coverage of each neighborhood
    *
    * @return
    */
  def getNeighborhoodCompletionRate = UserAwareAction.async { implicit request =>
    RegionCompletionTable.initializeRegionCompletionTable()

    val neighborhoods = RegionCompletionTable.selectAllNamedNeighborhoodCompletions
    val completionRates: List[JsObject] = for (neighborhood <- neighborhoods) yield {
      val completionRate: Double =
        if (neighborhood.totalDistance > 0) neighborhood.auditedDistance / neighborhood.totalDistance
        else 1.0D
      Json.obj("region_id" -> neighborhood.regionId,
        "total_distance_m" -> neighborhood.totalDistance,
        "completed_distance_m" -> neighborhood.auditedDistance,
        "rate" -> completionRate,
        "name" -> neighborhood.name
      )
    }

    Future.successful(Ok(JsArray(completionRates)))
  }

  /**
    * Gets count of completed missions for each anonymous user (diff users have diff ip addresses)
    *
    * @return
    */
  def getAllAnonUserCompletedMissionCounts = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val counts: List[(Option[String], Int)] = UserDAOImpl.getAnonUserCompletedMissionCounts
      val jsonArray = Json.arr(counts.map(x => {
        Json.obj("ip_address" -> x._1, "count" -> x._2, "is_researcher" -> false)
      }))
      Future.successful(Ok(jsonArray))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Gets count of completed missions for each anonymous user (diff users have diff ip addresses)
    *
    * @return
    */
  def getAllUserSignInCounts = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val counts: List[(String, Int)] = WebpageActivityTable.selectAllSignInCounts
      val jsonArray = Json.arr(counts.map(x => {
        Json.obj("user_id" -> x._1, "count" -> x._2, "is_researcher" -> UserRoleTable.isResearcher(UUID.fromString(x._1)))
      }))
      Future.successful(Ok(jsonArray))
    } else {
      Future.successful(Redirect("/"))
    }
  }


  /**
    * Returns DC coverage percentage by Date
    *
    * @return
    */
  def getCompletionRateByDate = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val streets: Seq[(String, Float)] = StreetEdgeTable.streetDistanceCompletionRateByDate(1)
      val json = Json.arr(streets.map(x => {
        Json.obj(
          "date" -> x._1, "completion" -> x._2
        )
      }))

      Future.successful(Ok(json))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Returns label counts by label type, for each region
    * @return
    */
  def getRegionNegativeLabelCounts() = UserAwareAction.async { implicit request =>

    // Groups by region_id... json looks like: {region_id: 123, labels: {NoCurbRamp: 5, Obstacle: 10, ...}}
    val features: List[JsObject] = GlobalAttributeTable.selectNegativeAttributeCountsByRegion().groupBy(_._1).map {
      case (rId, group) => Json.obj(
        "region_id" -> rId,
        "labels" -> Json.toJson(group.map(x => (x._2, x._3)).toMap)
      )
    }.toList

    val jsonObjectList = features.map(x => Json.toJson(x))
    Future.successful(Ok(JsArray(jsonObjectList)))
  }

  def getLabelsCollectedByAUser(username: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      UserTable.find(username) match {
        case Some(user) =>
          val labels = LabelTable.selectLocationsOfLabelsByUserId(UUID.fromString(user.userId))
          val features: List[JsObject] = labels.map { label =>
            val point = geojson.Point(geojson.LatLng(label.lat.toDouble, label.lng.toDouble))
            val properties = Json.obj(
              "audit_task_id" -> label.auditTaskId,
              "label_id" -> label.labelId,
              "gsv_panorama_id" -> label.gsvPanoramaId,
              "label_type" -> label.labelType
            )
            Json.obj("type" -> "Feature", "geometry" -> point, "properties" -> properties)
          }
          val featureCollection = Json.obj("type" -> "FeatureCollection", "features" -> features)
          Future.successful(Ok(featureCollection))
        case _ => Future.successful(Ok(views.html.admin.user("Project Sidewalk", request.identity)))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def getStreetsAuditedByAUser(username: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      UserTable.find(username) match {
        case Some(user) =>
          val streets = AuditTaskTable.selectStreetsAuditedByAUser(UUID.fromString(user.userId))
          val features: List[JsObject] = streets.map { edge =>
            val coordinates: Array[Coordinate] = edge.geom.getCoordinates
            val latlngs: List[geojson.LatLng] = coordinates.map(coord => geojson.LatLng(coord.y, coord.x)).toList // Map it to an immutable list
          val linestring: geojson.LineString[geojson.LatLng] = geojson.LineString(latlngs)
            val properties = Json.obj(
              "street_edge_id" -> edge.streetEdgeId,
              "source" -> edge.source,
              "target" -> edge.target,
              "way_type" -> edge.wayType
            )
            Json.obj("type" -> "Feature", "geometry" -> linestring, "properties" -> properties)
          }
          val featureCollection = Json.obj("type" -> "FeatureCollection", "features" -> features)
          Future.successful(Ok(featureCollection))
        case _ => Future.successful(Ok(views.html.admin.user("Project Sidewalk", request.identity)))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * This method returns the onboarding interaction data
    *
    * @return
    */
  def getOnboardingTaskInteractions = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val onboardingTransitions = AuditTaskInteractionTable.selectAuditTaskInteractionsOfAnActionType("Onboarding_Transition")
      val jsonObjectList = onboardingTransitions.map(x => Json.toJson(x))

      Future.successful(Ok(JsArray(jsonObjectList)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Get all auditing times
    *
    * @return
    */
  def getAuditTimes() = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val auditTimes = AuditTaskInteractionTable.selectAllAuditTimes().map(auditTime =>
        Json.obj("user_id" -> auditTime.userId, "time" -> auditTime.duration, "ip_address" -> auditTime.ipAddress))
      Future.successful(Ok(JsArray(auditTimes)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Get all auditing times for Turkers
    *
    * @return
    */
  def getTurkerAuditTimes() = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val auditTimes = AuditTaskInteractionTable.selectAllTurkerAuditTimes().map(auditTime =>
        Json.obj("user_id" -> auditTime.userId, "time" -> auditTime.duration, "ip_address" -> auditTime.ipAddress))
      Future.successful(Ok(JsArray(auditTimes)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Get all anonymous auditing times
    *
    * @return
    */
  def getAnonAuditTimes() = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val anonAuditTimes = AuditTaskInteractionTable.selectAllAnonAuditTimes().map(auditTime =>
        Json.obj("user_id" -> auditTime.userId, "time" -> auditTime.duration, "ip_address" -> auditTime.ipAddress))
      Future.successful(Ok(JsArray(anonAuditTimes)))
    } else {
      Future.successful(Redirect("/"))
    }
  }


  /**
    * This method returns the tasks and labels submitted by the given user.
    *
    * @param username Username
    * @return
    */
  def getSubmittedTasksWithLabels(username: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      UserTable.find(username) match {
        case Some(user) =>
          val tasksWithLabels = AuditTaskTable.selectTasksWithLabels(UUID.fromString(user.userId)).map(x => Json.toJson(x))
          Future.successful(Ok(JsArray(tasksWithLabels)))
        case _ => Future.successful(Ok(views.html.admin.user("Project Sidewalk", request.identity)))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def getMissionsCompletedByUsers = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      val missionsCompleted = MissionTable.selectMissionsCompletedByUsers.map(x =>
        Json.obj("username" -> x.username, "label" -> x.label, "level" -> x.level, "distance_m" -> x.distance_m,
          "distance_ft" -> x.distance_ft, "distance_mi" -> x.distance_mi)
      )
      Future.successful(Ok(JsArray(missionsCompleted)))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def completedTasks = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {

      Future.successful(Ok(JsArray()))
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Get records of audit task interactions of a user
    *
    * @param username
    * @return
    */
  def getAuditTaskInteractionsOfAUser(username: String) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      UserTable.find(username) match {
        case Some(user) =>
          val interactions = AuditTaskInteractionTable.selectAuditTaskInteractionsOfAUser(UUID.fromString(user.userId)).map(interaction => Json.toJson(interaction))
          Future.successful(Ok(JsArray(interactions)))
        case _ => Future.successful(Ok(Json.obj("error" -> "no user found")))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def auditTaskInteractions(taskId: Int) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      AuditTaskTable.find(taskId) match {
        case Some(user) =>
          val interactions = AuditTaskInteractionTable.selectAuditTaskInteractions(taskId).map(x => Json.toJson(x))
          Future.successful(Ok(JsArray(interactions)))
        case _ => Future.successful(Ok(Json.obj("error" -> "no user found")))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def getAnAuditTaskPath(taskId: Int) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      AuditTaskTable.find(taskId) match {
        case Some(task) =>
          // Select interactions and format it into a geojson
          val interactionsWithLabels: List[InteractionWithLabel] = AuditTaskInteractionTable.selectAuditInteractionsWithLabels(task.auditTaskId)
          val featureCollection: JsObject = AuditTaskInteractionTable.auditTaskInteractionsToGeoJSON(interactionsWithLabels)
          Future.successful(Ok(featureCollection))
        case _ => Future.successful(Ok(Json.obj("error" -> "no user found")))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  def getLabelData(labelId: Int) = UserAwareAction.async { implicit request =>
    if (isAdmin(request.identity)) {
      LabelPointTable.find(labelId) match {
        case Some(labelPointObj) =>
          val labelMetadata: LabelMetadata = LabelTable.getLabelMetadata(labelId)
          val labelMetadataJson: JsObject = LabelTable.labelMetadataToJson(labelMetadata)
          Future.successful(Ok(labelMetadataJson))
        case _ => Future.successful(Ok(Json.obj("error" -> "no such label")))
      }
    } else {
      Future.successful(Redirect("/"))
    }
  }

  /**
   * Get metadata used for 2022 CV project for all labels, and output as JSON.
   */
  def getAllLabelMetadataForCV = UserAwareAction.async { implicit request =>
    val labels: List[LabelCVMetadata] = LabelTable.getLabelCVMetadata
    val json: JsValue = Json.toJson(labels.map(l => Json.toJson(l)))
    Future.successful(Ok(json))
  }

  // Get the list of pano IDs in our database.
  def getAllPanoIds() = UserAwareAction.async { implicit request =>
    val json: JsValue = Json.toJson(GSVDataTable.getAllPanos().map(p => Json.obj(
      "gsv_panorama_id" -> p._1,
      "width" -> p._2,
      "height" -> p._3,
      "lat" -> p._4,
      "lng" -> p._5,
      "camera_heading" -> p._6,
      "camera_pitch" -> p._7
    )))
    Future.successful(Ok(json))
  }

  /**
    * USER CENTRIC ANALYTICS
    */

  def getAllRegisteredUserLabelCounts = UserAwareAction.async { implicit request =>
    val labelCounts = LabelTable.getLabelCountsPerRegisteredUser
    val json = Json.arr(labelCounts.map(x => Json.obj(
      "user_id" -> x._1, "count" -> x._2, "is_researcher" -> UserRoleTable.isResearcher(UUID.fromString(x._1))
    )))
    Future.successful(Ok(json))
  }

  def getAllTurkerUserLabelCounts = UserAwareAction.async { implicit request =>
    val labelCounts = LabelTable.getLabelCountsPerTurkerUser
    val json = Json.arr(labelCounts.map(x => Json.obj(
      "user_id" -> x._1, "count" -> x._2, "is_researcher" -> UserRoleTable.isResearcher(UUID.fromString(x._1))
    )))
    Future.successful(Ok(json))
  }

  def getAllAnonUserLabelCounts = UserAwareAction.async { implicit request =>
    val labelCounts = LabelTable.getLabelCountsPerAnonUser
    val json = Json.arr(labelCounts.map(x => Json.obj(
      "ip_address" -> x._1, "count" -> x._2, "is_researcher" -> false
    )))
    Future.successful(Ok(json))
  }

  /**
    * If no argument is provided, returns all webpage activity records. O/w, returns all records with matching activity
    * If the activity provided doesn't exist, returns 400 (Bad Request).
    *
    * @param activity
    */
  def getWebpageActivities(activity: String) = UserAwareAction.async{implicit request =>
    if (isAdmin(request.identity)){
      val activities = WebpageActivityTable.webpageActivityListToJson(WebpageActivityTable.findKeyVal(activity, Array()))
      if(activities.length == 0){
        Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> "Invalid activity name")))
      } else {
        Future.successful(Ok(Json.arr(activities)))
      }
    }else{
      Future.successful(Redirect("/"))
    }
  }

  /** Returns all records in the webpage_interactions table as a JSON array. */
  def getAllWebpageActivities = UserAwareAction.async{implicit request =>
    if (isAdmin(request.identity)){
      Future.successful(Ok(Json.arr(WebpageActivityTable.webpageActivityListToJson(WebpageActivityTable.getAllActivities))))
    }else{
      Future.successful(Redirect("/"))
    }
  }

  /**
    * Returns all records in webpage_activity table with activity field containing both activity and all keyValPairs.
    *
    * @param activity
    * @param keyValPairs
    * @return
    */
  def getWebpageActivitiesKeyVal(activity: String, keyValPairs: String) = UserAwareAction.async{ implicit request =>
    if (isAdmin(request.identity)){
      val keyVals: Array[String] = keyValPairs.split("/").map(URLDecoder.decode(_, "UTF-8"))
      val activities = WebpageActivityTable.webpageActivityListToJson(WebpageActivityTable.findKeyVal(activity, keyVals))
      Future.successful(Ok(Json.arr(activities)))
    }else{
      Future.successful(Redirect("/"))
    }
  }

  /** Returns number of records in webpage_activity table containing the specified activity. */
  def getNumWebpageActivities(activity: String) =   UserAwareAction.async{implicit request =>
    if (isAdmin(request.identity)){
      val activities = WebpageActivityTable.webpageActivityListToJson(WebpageActivityTable.findKeyVal(activity, Array()))
      Future.successful(Ok(activities.length + ""))
    }else{
      Future.successful(Redirect("/"))
    }
  }

  /** Returns number of records in webpage_activity table containing the specified activity and other keyValPairs. */
  def getNumWebpageActivitiesKeyVal(activity: String, keyValPairs: String) = UserAwareAction.async{ implicit request =>
    if (isAdmin(request.identity)){
      val keyVals: Array[String] = keyValPairs.split("/").map(URLDecoder.decode(_, "UTF-8")).map(URLDecoder.decode(_, "UTF-8"))
      val activities = WebpageActivityTable.webpageActivityListToJson(WebpageActivityTable.findKeyVal(activity, keyVals))
      Future.successful(Ok(activities.length + ""))
    }else{
      Future.successful(Redirect("/"))
    }
  }

  def setUserRole = UserAwareAction.async(BodyParsers.parse.json){ implicit request =>
    val submission = request.body.validate[UserRoleSubmission]

    submission.fold(
      errors => {
        Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
      },
      submission => {
        val userId = UUID.fromString(submission.userId)
        val newRole = submission.roleId

        if(isAdmin(request.identity)){
          UserTable.findById(userId) match {
            case Some(user) =>
              if(UserRoleTable.getRole(userId) == "Owner") {
                Future.successful(BadRequest("Owner's role cannot be changed"))
              } else if (newRole == "Owner") {
                Future.successful(BadRequest("Cannot set a new owner"))
              } else if (!RoleTable.getRoleNames.contains(newRole)) {
                Future.successful(BadRequest("Invalid role"))
              } else {
                UserRoleTable.setRole(userId, newRole)
                Future.successful(Ok(Json.obj("username" -> user.username, "user_id" -> userId, "role" -> newRole)))
              }
            case None =>
              Future.successful(BadRequest("No user has this user ID"))
          }
        } else {
          Future.successful(Redirect("/"))
        }
      }
    )
  }
}
