package com.thenewmotion.chargenetwork.ocpp.charger.json

import com.thenewmotion.ocpp.messages._
import com.thenewmotion.ocpp.json._
import v15.Ocpp15J
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Success, Failure}
import com.typesafe.scalalogging.slf4j.Logging
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * A component that, when mixed into something that is also an SRPC component, can send and receive OCPP-JSON messages
 * over that SRPC connection.
 *
 * @tparam OUTREQ Type of outgoing requests
 * @tparam INRES Type of incoming responses
 * @tparam INREQ Type of incoming requests
 * @tparam OUTRES Type of outgoing responses
 */
trait OcppConnectionComponent[OUTREQ <: Req, INRES <: Res, INREQ <: Req, OUTRES <: Res] {
  this: SrpcComponent =>

  trait OcppConnection {
    /** Send an outgoing OCPP request */
    def sendRequest[REQ <: OUTREQ, RES <: INRES](req: REQ)(implicit reqRes: ReqRes[REQ, RES]): Future[RES]

    /** Handle an incoming SRPC message */
    def onSrpcMessage(msg: TransportMessage)
  }

  def ocppConnection: OcppConnection

  def onRequest(req: INREQ): Future[OUTRES]
  def onOcppError(error: OcppError)
}

// TODO support the 'details' field of OCPP error messages
case class OcppError(error: PayloadErrorCode.Value, description: String)
case class OcppException(ocppError: OcppError) extends Exception(s"${ocppError.error}: ${ocppError.description}")
object OcppException {
  def apply(error: PayloadErrorCode.Value, description: String): OcppException =
    OcppException(new OcppError(error, description))
}

trait DefaultOcppConnectionComponent[OUTREQ <: Req, INRES <: Res, INREQ <: Req, OUTRES <: Res]
  extends OcppConnectionComponent[OUTREQ, INRES, INREQ, OUTRES] {

  this: SrpcComponent =>

  trait DefaultOcppConnection extends OcppConnection with Logging {
    /** The operations that the other side can request from us */
    val ourOperations: JsonOperations[INREQ, OUTRES]
    val theirOperations: JsonOperations[OUTREQ, INRES]

    private val callIdGenerator = CallIdGenerator()

    sealed case class OutstandingRequest[REQ <: OUTREQ, RES <: INRES](operation: JsonOperation[REQ, RES],
                                                                      responsePromise: Promise[RES])

    private val callIdCache: mutable.Map[String, OutstandingRequest[_, _]] = mutable.Map()

    def onSrpcMessage(msg: TransportMessage) {
      logger.debug("Incoming SRPC message: {}", msg)

      msg match {
        case req: RequestMessage => handleIncomingRequest(req)
        case res: ResponseMessage => handleIncomingResponse(res)
        case err: ErrorResponseMessage => handleIncomingError(err)
      }
    }

    private def handleIncomingRequest(req: RequestMessage) {
      import ourOperations._

      def respondWithError(errCode: PayloadErrorCode.Value, description: String) =
        srpcConnection.send(ErrorResponseMessage(req.callId, errCode, description))

      val opName = req.procedureName
      jsonOpForActionName(opName) match {
        case NotImplemented => respondWithError(PayloadErrorCode.NotImplemented, s"Unknown operation $opName")
        case Unsupported => respondWithError(PayloadErrorCode.NotSupported, s"We do not support $opName")
        case Supported(operation) =>
          val ocppMsg = operation.deserializeReq(req.payload)
          val responseSrpc = onRequest(ocppMsg) map {
            responseToSrpc(req.callId, _)
          } recover {
            case e: Exception =>
              logger.warn(s"Exception processing OCPP request {}: {} {}",
                req.procedureName, e.getClass.getSimpleName, e.getMessage)

              val ocppError = e match {
                case OcppException(err) => err
                case _ => OcppError(PayloadErrorCode.InternalError, "Unexpected error processing request")
              }
              ErrorResponseMessage(req.callId, ocppError.error, ocppError.description)
          }

          responseSrpc onComplete {
            case Success(json) => srpcConnection.send(json)
            case Failure(e) =>
              logger.error("OCPP response future failed for {} with call ID {}. This ought to be impossible.",
                opName, req.callId)
          }
      }
    }

    private def responseToSrpc[REQ <: INREQ, RES <: OUTRES](callId: String, response: OUTRES): TransportMessage =
        ResponseMessage(callId, Ocpp15J.serialize(response))


    private def handleIncomingResponse(res: ResponseMessage) {
      callIdCache.get(res.callId) match {
        case None =>
          logger.info("Received response for no request: {}", res)
        case Some(OutstandingRequest(op, resPromise)) =>
          val response = op.deserializeRes(res.payload)
          resPromise.success(response)
      }
    }

    private def handleIncomingError(err: ErrorResponseMessage) = err match {
      case ErrorResponseMessage(callId, errCode, description, details) =>
        callIdCache.get(callId) match {
          case None => onOcppError(OcppError(errCode, description))
          case Some(OutstandingRequest(operation, futureResponse)) =>
            futureResponse failure new OcppException(OcppError(errCode, description))
        }
    }

    def sendRequest[REQ <: OUTREQ, RES <: INRES](req: REQ)(implicit reqRes: ReqRes[REQ, RES]): Future[RES] = {
      Try(theirOperations.jsonOpForReqRes(reqRes)) match {
        case Success(operation) => sendRequestWithJsonOperation[REQ, RES](req, operation)
        case Failure(e: NoSuchElementException) =>
          val operationName = getProcedureName(req)
          throw new Exception(s"Tried to send unsupported OCPP request $operationName")
        case Failure(e) => throw e
      }
    }

    private def sendRequestWithJsonOperation[REQ <: OUTREQ, RES <: INRES](req: REQ,
                                                                          jsonOperation: JsonOperation[REQ, RES]) = {
      val callId = callIdGenerator.next()
      val responsePromise = Promise[RES]()

      callIdCache.put(callId, OutstandingRequest[REQ, RES](jsonOperation, responsePromise))
      // TODO have a way to not hardcode the OCPP version number when (de)serializing OCPP
      srpcConnection.send(RequestMessage(callId, getProcedureName(req), Ocpp15J.serialize(req)))
      responsePromise.future
    }

    private def getProcedureName(c: Message) = {
      c.getClass.getSimpleName.replaceFirst("Re[qs]\\$?$", "")
    }
  }

  def onRequest(req: INREQ): Future[OUTRES]
  def onOcppError(error: OcppError): Unit

  def onSrpcMessage(msg: TransportMessage) = ocppConnection.onSrpcMessage(msg)
}

trait ChargePointOcppConnectionComponent
  extends DefaultOcppConnectionComponent[CentralSystemReq, CentralSystemRes, ChargePointReq, ChargePointRes] {
  this: SrpcComponent =>

  class ChargePointOcppConnection extends DefaultOcppConnection {
    val ourOperations = ChargePointOperations
    val theirOperations = CentralSystemOperations
  }
}

