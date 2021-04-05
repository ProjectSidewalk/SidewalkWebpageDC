package models.user

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URL
import java.util.Base64
import java.util.UUID
import scala.io.Source

import models.utils.MyPostgresDriver.simple._
import play.api.Play
import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

case class WebpageActivity(webpageActivityId: Int, userId: String, ipAddress: String, description: String, timestamp: java.sql.Timestamp)

class WebpageActivityTable(tag: Tag) extends Table[WebpageActivity](tag, Some("sidewalk"), "webpage_activity") {
  def webpageActivityId = column[Int]("webpage_activity_id", O.PrimaryKey, O.AutoInc)
  def userId = column[String]("user_id", O.NotNull)
  def ipAddress = column[String]("ip_address", O.NotNull)
  def activity = column[String]("activity", O.NotNull)
  def timestamp = column[java.sql.Timestamp]("timestamp", O.NotNull)

  def * = (webpageActivityId, userId, ipAddress, activity, timestamp) <> ((WebpageActivity.apply _).tupled, WebpageActivity.unapply)
}

object WebpageActivityTable {
  val db = play.api.db.slick.DB
  val activities = TableQuery[WebpageActivityTable]

  // Grab secret from ENV variable.
  val secretKeyString: String = Play.configuration.getString("google-maps-secret").get

  // Decode secret key as Byte[].
  val secretKey: Array[Byte] = Base64.getDecoder().decode(secretKeyString.replace('-', '+').replace('_', '/'))

  // Get an HMAC-SHA1 signing key from the raw key bytes.
  val sha1Key: SecretKeySpec = new SecretKeySpec(secretKey, "HmacSHA1")

  // Get an HMAC-SHA1 Mac instance and initialize it with the HMAC-SHA1 key.
  val mac: Mac = Mac.getInstance("HmacSHA1")
  mac.init(sha1Key)

  def save(activity: WebpageActivity): Int = db.withTransaction { implicit session =>
    if (activity.ipAddress == "128.8.132.187") {
      // Don't save data if the activity is from the remote proxy. Todo: The IP address of the remote proxy server should be store
      0
    } else {
      val webpageActivityId: Int =
        (activities returning activities.map(_.webpageActivityId)) += activity
      webpageActivityId
    }
  }

  /**
   * Signs a Google Maps request using a signing secret.
   * https://developers.google.com/maps/documentation/maps-static/get-api-key#dig-sig-manual
   */
  def signUrl(urlString: String): String = {
    // Convert to Java URL for easy parsing of URL parts.
    val url: URL = new URL(urlString)

    // Gets everything but URL protocol and host that we want to sign.
    val resource: String = url.getPath() + '?' + url.getQuery()

    // Compute the binary signature for the request.
    val sigBytes: Array[Byte] = mac.doFinal(resource.getBytes())

    // Base 64 encode the binary signature and convert the signature to 'web safe' base 64.
    val signature: String = Base64.getEncoder().encodeToString(sigBytes).replace('+', '-').replace('/', '_')

    // Return signed url.
    urlString + "&signature=" + signature
  }

  /**
    * Returns the last log in timestamp
    * @param userId User id
    * @return
    */
  def selectLastSignInTimestamp(userId: UUID): Option[java.sql.Timestamp] = db.withTransaction { implicit session =>
    val signInActivities: List[WebpageActivity] = activities.filter(_.userId === userId.toString).filter(_.activity === "SignIn").sortBy(_.timestamp.desc).list

    if (signInActivities.nonEmpty) {
      Some(signInActivities.head.timestamp)
    } else {
      None
    }
  }

  /**
    * Returns the signup timestamp
    * @param userId User id
    * @return
    */
  def selectSignUpTimestamp(userId: UUID): Option[java.sql.Timestamp] = db.withTransaction { implicit session =>
    val signUpActivities: List[WebpageActivity] = activities.filter(_.userId === userId.toString).filter(_.activity === "SignUp").sortBy(_.timestamp.desc).list

    if (signUpActivities.nonEmpty) {
      Some(signUpActivities.head.timestamp)
    } else {
      None
    }
  }

  /**
    * Returns the signin count
    * @param userId User id
    * @return
    */
  def selectSignInCount(userId: UUID): Option[Integer] = db.withTransaction { implicit session =>
    val signInActivities: List[WebpageActivity] = activities.filter(_.userId === userId.toString).filter(_.activity === "SignIn").list
    Some(signInActivities.length)
  }

  /**
    * Returns a list of signin counts, each element being a count of logins for a user
    *
    * @return List[(userId: String, count: Int)]
    */
  def selectAllSignInCounts: List[(String, Int)] = db.withTransaction { implicit session =>
    activities.filter(_.activity === "SignIn").groupBy(x => x.userId).map{
      case (id, group) => (id, group.map(_.activity).length)
    }.list
  }

  /**
    * Returns all WebpageActivities that contain the given string in their 'activity' field
    */
  def find(activity: String): List[WebpageActivity] = db.withSession { implicit session =>
    activities.filter(_.activity.like("%"++activity++"%")).list
  }

  /** Returns all WebpageActivities that contain the given string and keyValue pairs in their 'activity' field
    *
    * Partial activity searches work (for example, if activity is "Cli" then WebpageActivities whose activity begins
    * with "Cli...", such as "Click" will be matched)
    *
    * @param activity
    * @param keyVals
    * @return
    */
  def findKeyVal(activity: String, keyVals: Array[String]): List[WebpageActivity] = db.withSession { implicit session =>
    var filteredActivities = activities.filter(x => (x.activity.startsWith(activity++"_") || x.activity === activity))
    for(keyVal <- keyVals) yield {
      filteredActivities = filteredActivities.filter(x => (x.activity.indexOf("_"++keyVal++"_") >= 0) || x.activity.endsWith("_"+keyVal))
    }
    filteredActivities.list
  }

  // Returns all webpage activities
  def getAllActivities: List[WebpageActivity] = db.withSession{implicit session =>
    activities.list
  }

  def webpageActivityListToJson(webpageActivities: List[WebpageActivity]): List[JsObject] = {
    webpageActivities.map(webpageActivity => webpageActivityToJson(webpageActivity)).toList
  }

  def webpageActivityToJson(webpageActivity: WebpageActivity): JsObject = {
    Json.obj(
      "webpageActivityId" -> webpageActivity.webpageActivityId,
      "userId" -> webpageActivity.userId,
      "ipAddress" -> webpageActivity.ipAddress,
      "activity" -> webpageActivity.description,
      "timestamp" -> webpageActivity.timestamp
    )
  }
}
