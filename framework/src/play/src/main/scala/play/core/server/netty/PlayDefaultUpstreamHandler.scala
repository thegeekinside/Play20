package play.core.server.netty

import org.jboss.netty.buffer._
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel.socket.nio._
import org.jboss.netty.handler.stream._
import org.jboss.netty.handler.codec.http.HttpHeaders._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values._

import org.jboss.netty.channel.group._
import java.util.concurrent._

import play.core._
import server.Server
import play.api._
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.concurrent._

import scala.collection.JavaConverters._

private[server] class PlayDefaultUpstreamHandler(server: Server, allChannels: DefaultChannelGroup) extends SimpleChannelUpstreamHandler with Helpers with WebSocketHandler with RequestBodyHandler {

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause.printStackTrace()
    e.getChannel.close()
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {

    allChannels.add(e.getChannel)

    e.getMessage match {
      case nettyHttpRequest: HttpRequest =>
        val keepAlive = isKeepAlive(nettyHttpRequest)
        var version = nettyHttpRequest.getProtocolVersion
        val nettyUri = new QueryStringDecoder(nettyHttpRequest.getUri)
        val parameters = Map.empty[String, Seq[String]] ++ nettyUri.getParameters.asScala.mapValues(_.asScala)

        val rHeaders = getHeaders(nettyHttpRequest)
        val rCookies = getCookies(nettyHttpRequest)

        import org.jboss.netty.util.CharsetUtil;

        val requestHeader = new RequestHeader {
          def uri = nettyHttpRequest.getUri
          def path = nettyUri.getPath
          def method = nettyHttpRequest.getMethod.getName
          def queryString = parameters
          def headers = rHeaders
          def cookies = rCookies
          def username = None
        }

        val response = new Response {
          def handle(result: Result) = result match {

            case AsyncResult(p) => p.extend1 {
              case Redeemed(v) => handle(v)
              case Thrown(e) => {
                Logger("play").error("Waiting for a promise, but got an error: " + e.getMessage, e)
                handle(Results.InternalServerError)
              }
            }

            case _ if (isWebSocket(nettyHttpRequest)) => handle(Results.BadRequest)

            case r @ SimpleResult(ResponseHeader(status, headers), body) =>
              val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status))
              headers.foreach {

                // Fix a bug for Set-Cookie header. 
                // Multiple cookies could be merge in a single header
                // but it's not properly supported by some browsers
                case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {

                  import scala.collection.JavaConverters._
                  import play.api.mvc._

                  nettyResponse.setHeader(name, Cookies.decode(value).map { c => Cookies.encode(Seq(c)) }.asJava)

                }

                case (name, value) => nettyResponse.setHeader(name, value)
              }
              val channelBuffer = ChannelBuffers.dynamicBuffer(512)
              val writer: Function2[ChannelBuffer, r.BODY_CONTENT, Unit] = (c, x) => c.writeBytes(r.writeable.transform(x))
              val stringIteratee = Iteratee.fold(channelBuffer)((c, e: r.BODY_CONTENT) => { writer(c, e); c })
              val p = body |>> stringIteratee
              p.flatMap(i => i.run)
                .onRedeem { buffer =>
                  nettyResponse.setContent(buffer)
                  if (keepAlive) {
                    nettyResponse.setHeader(CONTENT_LENGTH, nettyResponse.getContent.readableBytes)
                    if (version == HttpVersion.HTTP_1_0) {
                      // Response header Connection: Keep-Alive is needed for HTTP 1.0
                      nettyResponse.setHeader(CONNECTION, KEEP_ALIVE)
                    }
                  }
                  val f = e.getChannel.write(nettyResponse)
                  if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE)
                }

            case r @ ChunkedResult(ResponseHeader(status, headers), chunks) =>
              val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status))
              headers.foreach {

                // Fix a bug for Set-Cookie header. 
                // Multiple cookies could be merge in a single header
                // but it's not properly supported by some browsers
                case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {

                  import scala.collection.JavaConverters._
                  import play.api.mvc._

                  nettyResponse.setHeader(name, Cookies.decode(value).map { c => Cookies.encode(Seq(c)) }.asJava)

                }

                case (name, value) => nettyResponse.setHeader(name, value)
              }
              nettyResponse.setHeader(TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
              nettyResponse.setChunked(true)

              val writer: Function1[r.BODY_CONTENT, ChannelFuture] = x => e.getChannel.write(new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(r.writeable.transform(x))))

              val chunksIteratee = Enumeratee.breakE[r.BODY_CONTENT](_ => !e.getChannel.isConnected())(Iteratee.fold(e.getChannel.write(nettyResponse))((_, e: r.BODY_CONTENT) => writer(e))).mapDone { _ =>
                if (e.getChannel.isConnected()) {
                  val f = e.getChannel.write(HttpChunk.LAST_CHUNK);
                  if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE)
                }
              }

              chunks(chunksIteratee)

          }
        }

        val handler = server.getHandlerFor(requestHeader)

        handler match {
          case Right((action: Action[_], app)) => {

            val bodyParser = action.parser

            e.getChannel.setReadable(false)
            ctx.setAttachment(scala.collection.mutable.ListBuffer.empty[org.jboss.netty.channel.MessageEvent])

            val eventuallyBodyParser = server.getBodyParser[action.BODY_CONTENT](requestHeader, bodyParser)

            val eventuallyResultOrBody =
              eventuallyBodyParser.flatMap { bodyParser =>
                if (nettyHttpRequest.isChunked) {

                  val (result, handler) = newRequestBodyHandler(bodyParser, allChannels, server)

                  val intermediateChunks = ctx.getAttachment.asInstanceOf[scala.collection.mutable.ListBuffer[org.jboss.netty.channel.MessageEvent]]
                  intermediateChunks.foreach(handler.messageReceived(ctx, _))
                  ctx.setAttachment(null)

                  val p: ChannelPipeline = ctx.getChannel().getPipeline()
                  p.replace("handler", "handler", handler)
                  e.getChannel.setReadable(true)

                  result
                } else {
                  e.getChannel.setReadable(true)
                  lazy val bodyEnumerator = {
                    val body = {
                      val cBuffer = nettyHttpRequest.getContent()
                      val bytes = new Array[Byte](cBuffer.readableBytes())
                      cBuffer.readBytes(bytes)
                      bytes
                    }
                    Enumerator(body).andThen(Enumerator.enumInput(EOF))
                  }

                  (bodyEnumerator |>> bodyParser): Promise[Iteratee[Array[Byte], Either[Result, action.BODY_CONTENT]]]
                }
              }

            val eventuallyResultOrRequest =
              eventuallyResultOrBody
                .flatMap(it => it.run)
                .map {
                  _.right.map(b =>
                    new Request[action.BODY_CONTENT] {
                      def uri = nettyHttpRequest.getUri
                      def path = nettyUri.getPath
                      def method = nettyHttpRequest.getMethod.getName
                      def queryString = parameters
                      def headers = rHeaders
                      def cookies = rCookies
                      def username = None
                      val body = b
                    })
                }

            eventuallyResultOrRequest.extend(_.value match {
              case Redeemed(Left(result)) => response.handle(result)
              case Redeemed(Right(request)) =>
                server.invoke(request, response, action.asInstanceOf[Action[action.BODY_CONTENT]], app)
            })

          }

          case Right((ws @ WebSocket(f), app)) if (isWebSocket(nettyHttpRequest)) => {
            try {
              val enumerator = websocketHandshake(ctx, nettyHttpRequest, e)(ws.frameFormatter)
              f(requestHeader)(enumerator, socketOut(ctx)(ws.frameFormatter))
            } catch {
              case e => e.printStackTrace
            }
          }

          case Right((WebSocket(_), _)) => {
            response.handle(Results.BadRequest)
          }

          case Left(e) => response.handle(e)

        }

      case chunk: org.jboss.netty.handler.codec.http.HttpChunk => {
        val intermediateChunks = ctx.getAttachment.asInstanceOf[scala.collection.mutable.ListBuffer[org.jboss.netty.channel.MessageEvent]]
        if (intermediateChunks != null) {
          intermediateChunks += e
          ctx.setAttachment(intermediateChunks)
        }
      }

      case unexpected => Logger("play").error("Oops, unexpected message received in NettyServer (please report this problem): " + unexpected)

    }
  }

}
