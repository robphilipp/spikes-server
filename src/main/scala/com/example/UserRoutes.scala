package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.{delete, get, post}
import akka.http.scaladsl.server.directives.PathDirectives.path
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.pattern.ask
import akka.util.Timeout
import com.example.UserRegistryActor._

import scala.concurrent.Future
import scala.concurrent.duration._

trait UserRoutes extends JsonSupport {

  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  private lazy val log = Logging(system, classOf[StaticContentRoutes])

  // other dependencies that UserRoutes use
  def userRegistryActor: ActorRef

  // Required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  lazy val userRoutes: Route =
    pathPrefix("users") {
      get {
        val users: Future[Users] =
          (userRegistryActor ? GetUsers).mapTo[Users]
        complete(users)
      } ~ post {
        entity(as[User]) { user =>
          val userCreated: Future[ActionPerformed] =
            (userRegistryActor ? CreateUser(user)).mapTo[ActionPerformed]
          onSuccess(userCreated) { performed =>
            log.info("Created user [{}]: {}", user.name, performed.description)
            complete((StatusCodes.Created, performed))
          }
        }
      }
    } ~ path("users" / Segment) { name =>
      get {
        val maybeUser: Future[Option[User]] =
          (userRegistryActor ? GetUser(name)).mapTo[Option[User]]
        rejectEmptyResponse {
          complete(maybeUser)
        }
      } ~ put {
        entity(as[UserAge]) { update =>
          val userAgeUpdated: Future[ActionPerformed] =
            (userRegistryActor ? UpdateUserAge(name, update.age)).mapTo[ActionPerformed]
          onSuccess(userAgeUpdated) { performed =>
            log.info("Updated user age [{}]: {}", name, performed.description)
            complete((StatusCodes.OK, performed))
          }
        }
      } ~ delete {
        val userDeleted: Future[ActionPerformed] =
          (userRegistryActor ? DeleteUser(name)).mapTo[ActionPerformed]
        onSuccess(userDeleted) { performed =>
          log.info("Deleted user [{}]: {}", name, performed.description)
          complete((StatusCodes.OK, performed))
        }
      }
    }
}
