package com.rockthejvm.bank.http

import akka.http.scaladsl.server.Directives._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import com.rockthejvm.bank.actors.PersistentBankAccount.{Command, Response}
import com.rockthejvm.bank.actors.PersistentBankAccount.Command._
import com.rockthejvm.bank.actors.PersistentBankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import Validation._
import akka.http.scaladsl.server.Route
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import com.rockthejvm.bank.http.BankAccountCreationRequest.validator

import scala.util.{Failure, Success}

case class BankAccountCreateRequest(user: String, currency: String, balance: Double) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreateRequest] = (request: BankAccountCreateRequest) => {
    val userValidation = validateRequired(request.user, "user")
    val currencyValidation = validateRequired(request.currency, "currency")
    val balanceValidation = validateMinimum(request.balance, 0, "balance")
      .combine(validateMinimumAbs(request.balance, 0.01, "balance"))

    (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreateRequest.apply)
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = (request: BankAccountUpdateRequest) => {
    val currencyValidation = validateRequired(request.currency, "currency")
    val amountValidation = validateMinimumAbs(request.amount, 0.01, "amount")

    (currencyValidation, amountValidation).mapN(BankAccountUpdateRequest.apply)
  }
}


case class FailureResponse(reason: String)

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(StatusCodes.BadRequest, FailureResponse(failures.toList.map(_.errorMessage).mkString(", ")))
    }

  /*
    POST /bank/
      Payload: bank account creation request as JSON
      Response:
        201 Created
        Location: /bank/uuid


    GET /bank/uuid
      Response:
      200 OK
      JSON repr of bank account details

    PUT /bank/uuid
      Payload: (currency, amount) as JSON
      Response:
        1)  200 OK
            Payload: new bank account as JSON
        2)  404 Not found
        3)  TODO: 400 Bad request if something wrong


   */

  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[BankAccountCreateRequest]) { request =>
            // validation
            validateRequest(request) {
              /*
              - convert the request into a Command for the bank actor
              - send the command to the bank
              - expect a reply
             */
              onSuccess(createBankAccount(request)) {
                // send back an HTTP response
                case BankAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/bank/$id")) {
                    complete(StatusCodes.Created)
                  }
              }
            }
          }
        }
      } ~
        path(Segment) { id =>
          get {
            /*
              - send a command to the bank
              - expect a reply
             */
            onSuccess(getBankAccount(id)) {
              case GetBankAccountResponse(Some(account)) =>
                //send back the HTTP response
                complete(account) // 200 OK
              case GetBankAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Bank Account $id cannot be found."))
            }
          } ~
            put {
              entity(as[BankAccountUpdateRequest]) { request =>
                // validation
                validateRequest(request) {
                  /*
                    - transform the request to a Command
                    - send the command to the bank
                    - expect a reply
                   */
                  // TODO validate the request
                  onSuccess(updateBankAccount(id, request)) {
                    // send back an HTTP response
                    case BankAccountBalanceUpdatedResponse(Success(account)) =>
                      complete(account)
                    case BankAccountBalanceUpdatedResponse(Failure(ex)) =>
                      complete(StatusCodes.BadRequest, FailureResponse(s"${ex.getMessage}"))
                  }
                }
              }
            }
        }
    }
}
