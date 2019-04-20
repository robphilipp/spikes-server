package com.example

import akka.actor.{ Actor, ActorLogging, Props }

class UserRegistryActor extends Actor with ActorLogging {
  import UserRegistryActor._

  var users = Set.empty[User]

  private def remove(name: String): Unit = {
    users.find(_.name == name) foreach { user => users -= user }
  }

  private def add(user: User): Unit = {
    users += user
  }

  def receive: Receive = {
    case GetUsers =>
      sender() ! Users(users.toSeq)

    case CreateUser(user) =>
      add(user)
      sender() ! ActionPerformed(s"User ${user.name} created.")

    case UpdateUserAge(name, age) =>
      users.find(_.name == name).foreach(user => {
        remove(user.name)
        add(User(user.name, age, user.countryOfResidence))
      })
      sender() ! ActionPerformed(s"User $name's age updated to $age years.")

    case GetUser(name) =>
      sender() ! users.find(_.name == name)

    case DeleteUser(name) =>
      remove(name)
      sender() ! ActionPerformed(s"User $name deleted.")
  }
}

object UserRegistryActor {
  final case class ActionPerformed(description: String)
  final case object GetUsers
  final case class CreateUser(user: User)
  final case class UpdateUserAge(name: String, age: Int)
  final case class GetUser(name: String)
  final case class DeleteUser(name: String)

  def props: Props = Props[UserRegistryActor]
}

final case class User(name: String, age: Int, countryOfResidence: String)
final case class UserAge(age: Int)
final case class Users(users: Seq[User])



