package controllers

import models._
import play.api.Play
import play.api.data.validation.ValidationError
import play.api.libs.Crypto
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent

import play.api.mvc._
import utils.{AuthInfo, ForceUtil}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object Application extends Controller {

  val X_ID_URL = "X-ID-URL"
  val X_ACCESS_TOKEN = "X-ACCESS-TOKEN"
  val X_REFRESH_TOKEN = "X-REFRESH-TOKEN"
  val X_INSTANCE_URL = "X-INSTANCE-URL"

  lazy val forceUtil = ForceUtil(Play.current)

  private def errorsToJson(errors: Seq[(JsPath, Seq[ValidationError])]): JsObject = {
    Json.obj("errors" -> errors.toString())
  }

  class RequestWithAuthInfo[A](val authInfo: AuthInfo, request: Request[A]) extends WrappedRequest[A](request)

  object AuthInfoAction extends ActionBuilder[RequestWithAuthInfo] with ActionRefiner[Request, RequestWithAuthInfo] {
    override def refine[A](request: Request[A]): Future[Either[Result, RequestWithAuthInfo[A]]] = {
      Future.successful {
        val maybeAuthInfo = for {
          idUrl <- request.headers.get(X_ID_URL).orElse(request.flash.get(X_ID_URL))
          accessToken <- request.headers.get(X_ACCESS_TOKEN).orElse(request.flash.get(X_ACCESS_TOKEN))
          refreshToken <- request.headers.get(X_REFRESH_TOKEN).orElse(request.flash.get(X_REFRESH_TOKEN))
          instanceUrl <- request.headers.get(X_INSTANCE_URL).orElse(request.flash.get(X_INSTANCE_URL))
        } yield AuthInfo(idUrl, accessToken, refreshToken, instanceUrl)

        maybeAuthInfo.map { authInfo =>
          new RequestWithAuthInfo(authInfo, request)
        } toRight {
          render {
            case Accepts.Html() => Redirect(routes.Application.login())
            case Accepts.Json() => Unauthorized(Json.obj("error" -> s"The auth info was not set"))
          } (request)
        }
      }
    }
  }


  def app() = AuthInfoAction { request =>
    Ok(views.html.app(request.authInfo))
  }

  def login() = Action {
    Ok(views.html.login())
  }

  def orgUserInfo() = AuthInfoAction.async { request =>
    val userOrgInfoFuture = for {
      userInfo <- forceUtil.userInfo(request.authInfo)
      orgId = (userInfo \ "organization_id").as[String]
      orgInfo <- forceUtil.orgInfo(request.authInfo, orgId)
    } yield {
      userInfo.as[JsObject] ++ orgInfo.as[JsObject]
    }

    userOrgInfoFuture.map { json =>
      Ok(json)
    }
  }

  def orgWebJars() = AuthInfoAction.async { request =>
    forceUtil.resourcesWithPrefix(request.authInfo, "webjar_").map { json =>
      Ok(json)
    }
  }

  def orgWebJarsCreate() = AuthInfoAction.async(parse.json) { request =>
    val webJarId = (request.body \ "id").as[String]
    val webJarVersion = (request.body \ "version").as[String]
    val resourceUrl = s"http://repo1.maven.org/maven2/org/webjars/$webJarId/$webJarVersion/$webJarId-$webJarVersion.jar"

    forceUtil.createResource(request.authInfo, webJarId, webJarVersion, resourceUrl).map { json =>
      Created(json)
    }
  }

  def oauthLoginProd() = Action { implicit request =>
    Redirect(forceUtil.loginUrl(forceUtil.ENV_PROD)).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_PROD)
  }

  def oauthLoginSandbox() = Action { implicit request =>
    Redirect(forceUtil.loginUrl(forceUtil.ENV_SANDBOX)).flashing(forceUtil.SALESFORCE_ENV -> forceUtil.ENV_SANDBOX)
  }

  def oauthCallback(code: String) = Action.async { implicit request =>

    val env = request.flash.get(forceUtil.SALESFORCE_ENV).getOrElse(forceUtil.ENV_PROD)

    val loginFuture = forceUtil.login(code, env)

    loginFuture.map { authInfo =>
      Redirect(routes.Application.app())
        .flashing(
          X_ID_URL -> authInfo.idUrl,
          X_ACCESS_TOKEN -> authInfo.accessToken,
          X_REFRESH_TOKEN -> authInfo.refreshToken,
          X_INSTANCE_URL -> authInfo.instanceUrl
        )
    } recover { case e: Error =>
      Redirect(routes.Application.login())
    }
  }

}
