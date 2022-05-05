package com.rockthejvm.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.lang
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Failure

object Bank {

  // commands = messages

  import PersistentBankAccount._
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._

  // events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateBankAccount(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val newBankAccount = context.spawn(PersistentBankAccount(id), id)
        Effect
          .persist(BankAccountCreated(id))
          .thenReply(newBankAccount)(_ => createCommand)
      case updateCmd @ UpdateBalance(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCmd)
          case None =>
            Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Bank account cannot be found")))) // failed account search
        }
      case getCommand @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCommand)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None))
        }
    }

  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        val account = context.child(id)// exists after command handler
          .getOrElse(context.spawn(PersistentBankAccount(id), id))
          .asInstanceOf[ActorRef[Command]]// does NOT exist in the recovery mode
      state.copy(state.accounts + (id -> account))
    }

  // behaviour
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object BankPlayground {

  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response
  import PersistentBankAccount.Response._

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response]{
        case BankAccountCreatedResponse(id) =>
          logger.info(s"successfully created bank account $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details: $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")

      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

//      bank ! CreateBankAccount("phil", "GBP", 10, responseHandler)
      bank ! GetBankAccount("025b9787-8c4b-43e2-802f-f94d44cc94d7", responseHandler)

//      .flatMap {
//        case BankAccountCreatedResponse(id) =>
//          context.log.info(s"successfully created bank account $id")
//          bank.ask(replyTo => GetBankAccount(id, replyTo))
//      }.foreach{
//        case GetBankAccountResponse(maybeBankAccount) =>
//          context.log.info(s"Account details: $maybeBankAccount")
//      }

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "BankDemo")
  }


}
