package com.example

import java.nio.file.{Files, Paths}

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
//import akka.http.scaladsl.server.directives.MethodDirectives.get
//import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.util.Timeout

import scala.concurrent.duration._

trait StaticContentRoutes extends JsonSupport {

  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  private lazy val log = Logging(system, classOf[StaticContentRoutes])

  // other dependencies that UserRoutes use
  def userRegistryActor: ActorRef

  // Required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  val workingDirectory: String = System.getProperty("user.dir")

  private def getExtensions(fileName: String): String = {

    val index = fileName.lastIndexOf('.')
    if (index != 0) {
      fileName.drop(index + 1)
    } else
      ""
  }

  private def getDefaultPage = {

    val fullPath = List(Paths.get("static/index.html"), Paths.get("static/index.htm"))
    val res = fullPath.filter(x => Files.exists(x))
    if (res.nonEmpty)
      res.head
    else
      Paths.get("")
  }

  lazy val staticContentRoutes: Route = //path("static") {
    logRequestResult("akka-http-server") {
      get {
        entity(as[HttpRequest]) { requestData =>
          complete {

            val fullPath = requestData.uri.path.toString match {
              case "/" => getDefaultPage
              case "" => getDefaultPage
              case _ => Paths.get("static/" + requestData.uri.path.toString)
            }

            val ext = getExtensions(fullPath.getFileName.toString)
            val c: ContentType = ContentType(
              MediaTypes.forExtensionOption(ext).getOrElse(MediaTypes.`text/plain`),
              () => HttpCharsets.`UTF-8`
            )
            val byteArray = Files.readAllBytes(fullPath)
            HttpResponse(OK, entity = HttpEntity(c, byteArray))
          }
        }
      }
    }
  //  }
}
