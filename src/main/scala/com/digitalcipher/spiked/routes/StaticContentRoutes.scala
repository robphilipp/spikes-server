package com.digitalcipher.spiked.routes

import java.nio.file.{ Files, Path, Paths }

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout

/**
 * Routes for retrieving the static content for the spikes-ui application
 */
class StaticContentRoutes(baseUrl: String, defaultPages: Seq[Path], timeout: Timeout) {
  private val defaultPage: Path = defaultPages.find(page => Files.exists(page)).getOrElse(Paths.get(""))

  /**
   * Extracts the extension of the filename. If the filename is only an extension, or
   * the filename has no extension, then returns an empty string
   *
   * @param fileName The file name
   * @return The extension or an empty string
   */
  private def fileExtension(fileName: String): String = {
    val index = fileName.lastIndexOf('.')
    if (index != 0) {
      fileName.drop(index + 1)
    } else {
      ""
    }
  }

  lazy val staticContentRoutes: Route =
    logRequestResult("akka-http-server") {
      get {
        entity(as[HttpRequest]) { requestData =>
          complete {
            val staticContent = requestData.uri.path.toString match {
              // grab the default page
              case "/" | "" => defaultPage

              case _ => Paths.get(baseUrl, requestData.uri.path.toString)
            }

            // calculate the content type based on the file extension
            val contentType: ContentType = ContentType(
              MediaTypes
                .forExtensionOption(fileExtension(staticContent.getFileName.toString))
                .getOrElse(MediaTypes.`text/plain`),
              () => HttpCharsets.`UTF-8`)

            // return the response
            HttpResponse(OK, entity = HttpEntity(contentType, Files.readAllBytes(staticContent)))
          }
        }
      }
    }
}

object StaticContentRoutes {
  def apply(baseUrl: String, defaultPages: Seq[Path], timeout: Timeout): StaticContentRoutes =
    new StaticContentRoutes(baseUrl, defaultPages, timeout)
}
