/*
 * Copyright (C) 2011-2012 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can.client

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.io.Tcp
import spray.can.rendering.HttpRequestPartRenderingContext
import spray.can.Http
import spray.http._
import spray.io._
import System.{currentTimeMillis => now}


object ClientFrontend {

  def apply(initialRequestTimeout: Duration): PipelineStage = {
    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = {
        import context.log
        new Pipelines {
          var openRequests = Queue.empty[RequestRecord]
          var requestTimeout = initialRequestTimeout

          val commandPipeline: CPL = {
            case Http.MessageCommand(HttpMessagePartWrapper(x: HttpRequest, ack)) =>
              if (openRequests.isEmpty || openRequests.last.timestamp > 0) {
                render(x, ack)
                openRequests = openRequests enqueue new RequestRecord(x, context.sender, timestamp = now)
              } else log.warning("Received new HttpRequest before previous chunking request was " +
                "finished, ignoring...")

            case Http.MessageCommand(HttpMessagePartWrapper(x: ChunkedRequestStart, ack)) =>
              if (openRequests.isEmpty || openRequests.last.timestamp > 0) {
                render(x, ack)
                openRequests = openRequests enqueue new RequestRecord(x, context.sender, timestamp = 0)
              } else log.warning("Received new ChunkedRequestStart before previous chunking " +
                "request was finished, ignoring...")

            case Http.MessageCommand(HttpMessagePartWrapper(x: MessageChunk, ack)) =>
              if (!openRequests.isEmpty && openRequests.last.timestamp == 0) {
                render(x, ack)
              } else log.warning("Received MessageChunk outside of chunking request context, ignoring...")

            case Http.MessageCommand(HttpMessagePartWrapper(x: ChunkedMessageEnd, ack)) =>
              if (!openRequests.isEmpty && openRequests.last.timestamp == 0) {
                render(x, ack)
                openRequests.last.timestamp = now // only start timer once the request is completed
              } else log.warning("Received ChunkedMessageEnd outside of chunking request " +
                "context, ignoring...")

            case SetRequestTimeout(timeout) => requestTimeout = timeout.toMillis

            case cmd => commandPL(cmd)
          }

          val eventPipeline: EPL = {
            case Http.MessageEvent(x: HttpMessageEnd) =>
              if (!openRequests.isEmpty) {
                val currentRecord = openRequests.head
                openRequests = openRequests.tail
                dispatch(currentRecord.sender, x)
              } else {
                log.warning("Received unmatched {}, closing connection due to protocol error", x)
                commandPL(Http.Close)
              }

            case Http.MessageEvent(x: HttpMessagePart) =>
              if (!openRequests.isEmpty) {
                dispatch(openRequests.head.sender, x)
              } else {
                log.warning("Received unmatched {}, closing connection due to protocol error", x)
                commandPL(Http.Close)
              }

            case Pipeline.AckEvent(ack) =>
              if (!openRequests.isEmpty) dispatch(openRequests.head.sender, ack)
              else throw new IllegalStateException

            case x: Tcp.ConnectionClosed =>
              openRequests.foreach(rec => dispatch(rec.sender, x))
              eventPL(x) // terminates the connection actor

            case TickGenerator.Tick =>
              checkForTimeout()
              eventPL(TickGenerator.Tick)

            case Tcp.CommandFailed(Http.Write(_, _, part, requestSender)) =>
              commandPL(Pipeline.Tell(requestSender, part, context.self))

            case ev => eventPL(ev)
          }

          def render(part: HttpRequestPart, sentAck: Any) {
            val ack = if (sentAck eq None) Tcp.NoAck else sentAck
            commandPL(HttpRequestPartRenderingContext(part, context.sender, ack))
          }

          def dispatch(receiver: ActorRef, msg: Any) {
            commandPL(Pipeline.Tell(receiver, msg, context.self))
          }

          def checkForTimeout() {
            if (!openRequests.isEmpty && requestTimeout > 0) {
              val rec = openRequests.head
              if (rec.timestamp > 0 && rec.timestamp + requestTimeout < now) {
                log.warning("Request timed out after {}, closing connection", requestTimeout)
                commandPL(Http.Close)
              }
            }
          }
        }
      }
    }
  }

  private class RequestRecord(val request: HttpRequest, val sender: ActorRef, var timestamp: Long)

  ////////////// COMMANDS //////////////

  /**
   * Sets a new request-timeout on the connection.
   * Set to `Duration.Undefined` to disable timeout checking.
   */
  case class SetRequestTimeout(timeout: Duration) extends Command {
    require(timeout >= Duration.Zero, "timeout must not be negative")
  }
}