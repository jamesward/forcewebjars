package utils

import java.util.concurrent.TimeUnit

import com.ning.http.util.Base64
import play.api.Application
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.{JsArray, JsObject, Json, JsValue}
import play.api.libs.ws.{WSResponse, WSRequestHolder, WS}
import play.api.http.{Status, HeaderNames}
import play.api.mvc.RequestHeader
import play.api.mvc.Results.EmptyContent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}

import scala.language.implicitConversions

class ForceUtil(implicit app: Application) {

  implicit def toRichFutureWSResponse(f: Future[WSResponse]): RichFutureWSResponse = new RichFutureWSResponse(f)

  val API_VERSION = "32.0"

  val defaultTimeout = FiniteDuration(60, TimeUnit.SECONDS)
  val defaultPollInterval = FiniteDuration(1, TimeUnit.SECONDS)

  val consumerKey = app.configuration.getString("force.oauth.consumer-key").get
  val consumerSecret = app.configuration.getString("force.oauth.consumer-secret").get

  val ENV_PROD = "prod"
  val ENV_SANDBOX = "sandbox"
  val SALESFORCE_ENV = "salesforce-env"

  def loginUrl(env: String)(implicit request: RequestHeader): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s".format(consumerKey, redirectUri)
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s".format(consumerKey, redirectUri)
  }

  def tokenUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/token"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/token"
  }

  def userinfoUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/userinfo"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/userinfo"
  }

  def redirectUri(implicit request: RequestHeader): String = {
    controllers.routes.Application.oauthCallback("").absoluteURL(request.secure).stripSuffix("?code=")
  }

  def ws(path: String, authInfo: AuthInfo): WSRequestHolder = {
    WS.
      url(s"${authInfo.instanceUrl}/services/data/v$API_VERSION/$path").
      withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
  }

  def login(code: String, env: String)(implicit request: RequestHeader): Future[AuthInfo] = {
    val wsFuture = WS.url(tokenUrl(env)).withQueryString(
      "grant_type" -> "authorization_code",
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret,
      "redirect_uri" -> redirectUri,
      "code" -> code
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      val maybeAuthInfo = for {
        idUrl <- (response.json \ "id").asOpt[String]
        accessToken <- (response.json \ "access_token").asOpt[String]
        refreshToken <- (response.json \ "refresh_token").asOpt[String]
        instanceUrl <- (response.json \ "instance_url").asOpt[String]
      } yield AuthInfo(idUrl, accessToken, refreshToken, instanceUrl)

      maybeAuthInfo.fold {
        Future.failed[AuthInfo](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }

  // todo: handle refresh tokens
  /*
  def refreshToken(org: Org): Future[String] = {
    val wsFuture = WS.url(tokenUrl(org.env)).withQueryString(
      "grant_type" -> "refresh_token",
      "refresh_token" -> org.refreshToken,
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        Future.failed[String](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }
  */

  def userInfo(authInfo: AuthInfo): Future[JsValue] = {
    WS
      .url(authInfo.idUrl)
      .withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
      .get()
      .ok(_.json)
  }

  def orgInfo(authInfo: AuthInfo, orgId: String): Future[JsValue] = {
    ws(s"sobjects/Organization/$orgId", authInfo).get().ok(_.json)
  }

  def createResource(authInfo: AuthInfo, webJarId: String, webJarVersion: String, resourceUrl: String): Future[JsValue] = {

    WS.url(resourceUrl).getStream().flatMap { case (headers, enumerator) =>

      val bodyFuture = enumerator |>>> Iteratee.fold(Array.empty[Byte])(_ ++ _)

      bodyFuture.flatMap { rawBody =>
        val body = Base64.encode(rawBody)
        val description = Json.obj(
          "id" -> webJarId,
          "version" -> webJarVersion
        )
        val legalName = "webjar_" + webJarId.replaceAll( """\W""", "_")
        val json = Json.obj(
          "Name" -> legalName,
          "Body" -> body,
          "Description" -> description.toString,
          "ContentType" -> "application/zip",
          "CacheControl" -> "public"
        )
        ws("tooling/sobjects/StaticResource", authInfo).post(json).created(_.json)
      }
    }
  }

  def resourcesWithPrefix(authInfo: AuthInfo, prefix: String): Future[JsArray] = {
    toolingQuery(authInfo, s"select Id, Name, Description from StaticResource where Name like '$prefix%'").map { json =>
      (json \ "records").as[JsArray]
    }
  }

  def toolingQuery(authInfo: AuthInfo, q: String): Future[JsValue] = {
    ws("tooling/query/", authInfo).withQueryString("q" -> q).get().ok(_.json)
  }

}

object ForceUtil {
  def apply(implicit app: Application) = new ForceUtil()
}

case class AuthInfo(idUrl: String, accessToken: String, refreshToken: String, instanceUrl: String)

case class UnauthorizedError(message: String) extends Exception {
  override def getMessage: String = message
}

case class RequestError(message: String) extends Exception {
  override def getMessage: String = message
}

class RichFutureWSResponse(val future: Future[WSResponse]) extends AnyVal {

  def ok[A](f: (WSResponse => A)): Future[A] = status(f)(Status.OK)
  def okF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.OK)

  def created[A](f: (WSResponse => A)): Future[A] = status(f)(Status.CREATED)
  def createdF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.CREATED)

  def noContent[A](f: (WSResponse => A)): Future[A] = status(f)(Status.NO_CONTENT)
  def noContentF[A](f: (WSResponse => Future[A])): Future[A] = statusF(f)(Status.NO_CONTENT)

  def status[A](f: (WSResponse => A))(statusCode: Int): Future[A] = {
    future.flatMap { response =>
      statusF { response =>
        Future.successful(f(response))
      } (statusCode)
    }
  }

  def statusF[A](f: (WSResponse => Future[A]))(statusCode: Int): Future[A] = {
    future.flatMap { response =>
      response.status match {
        case `statusCode` =>
          f(response)
        case Status.UNAUTHORIZED =>
          Future.failed(UnauthorizedError((response.json \\ "message").map(_.as[String]).mkString(" ")))
        case _ =>
          Future.failed(RequestError(response.body))
      }
    }
  }

}

// From: http://stackoverflow.com/questions/16304471/scala-futures-built-in-timeout
object TimeoutFuture {
  def apply[A](timeout: FiniteDuration)(future: Future[A])(implicit app: Application): Future[A] = {

    val promise = Promise[A]()

    Akka.system.scheduler.scheduleOnce(timeout) {
      promise.tryFailure(new java.util.concurrent.TimeoutException)
    }

    promise.completeWith(future)

    promise.future
  }
}