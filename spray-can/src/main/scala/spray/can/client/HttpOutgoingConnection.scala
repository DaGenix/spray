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

package spray.can
package client

import spray.http.{Confirmed, HttpRequestPart}
import spray.io._
import akka.actor.{ReceiveTimeout, ActorRef}
import akka.io.{Tcp, IO}
import scala.concurrent.duration.Duration


private[can] class HttpOutgoingConnection(connectCommander: ActorRef,
                                          connect: Http.Connect,
                                          pipelineStage: PipelineStage,
                                          settings: ClientSettings) extends ConnectionHandler { actor =>
  import connect._

  log.debug("Attempting connection to {}", remoteAddress)

  IO(Tcp) ! Tcp.Connect(remoteAddress, localAddress, options)

  if (settings.connectingTimeout ne Duration.Undefined)
    context setReceiveTimeout settings.connectingTimeout

  def receive: Receive = {
    case connected@ Tcp.Connected =>
      log.debug("Connected to {}", remoteAddress)
      val tcpConnection = sender
      tcpConnection ! Tcp.Register(self)
      connectCommander ! connected
      context become running(tcpConnection, pipelineStage, pipelineContext(connected))

    case Tcp.CommandFailed(_: Tcp.Connect) =>
      connectCommander ! Http.CommandFailed(connect)
      context stop self

    case ReceiveTimeout ⇒
      log.warning("Configured connecting timeout of {} expired, stopping", settings.connectingTimeout)
      context stop self
  }

  override def running(tcpConnection: ActorRef, pipelines: Pipelines): Receive =
    super.running(tcpConnection, pipelines) orElse {
      case x: HttpRequestPart                  => pipelines commandPipeline Http.MessageCommand(x)
      case x@ Confirmed(_: HttpRequestPart, _) => pipelines commandPipeline Http.MessageCommand(x)
    }

  def pipelineContext(connected: Tcp.Connected) = new SslTlsContext {
    def actorContext = context
    def remoteAddress = connected.remoteAddress
    def localAddress = connected.localAddress
    def log = actor.log
    def sslEngine = sslEngineProvider(this)
  }
}

private[can] object HttpOutgoingConnection {

  def pipelineStage(settings: ClientSettings): PipelineStage = {
    import settings._
    ClientFrontend(requestTimeout) >>
    ResponseChunkAggregation(responseChunkAggregationLimit.toInt) ? (responseChunkAggregationLimit > 0) >>
    ResponseParsing(parserSettings) >>
    RequestRendering(settings) >>
    ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
    SslTlsSupport ? sslEncryption >>
    TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite))
  }

}

