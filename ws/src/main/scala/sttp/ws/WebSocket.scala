package sttp.ws

import sttp.model.Headers
import sttp.monad.MonadError
import sttp.monad.syntax._

/** Effectful interactions with a web socket. Interactions can happen:
  *
  *   - on the frame level, by sending and receiving raw [[WebSocketFrame]] s
  *   - using the provided `receive*` methods to obtain concatenated data frames, or string/byte payloads, and the
  *     `send*` method to send string/binary frames.
  *
  * The `send*` and `receive*` methods may result in a failed effect, with either one of [[WebSocketException]]
  * exceptions, or a backend-specific exception. Specifically, they will fail with [[WebSocketClosed]] if the web socket
  * is closed.
  *
  * See the `either` and `eitherClose` method to lift web socket closed events to the value level.
  */
trait WebSocket[F[_]] {

  /** Receive the next frame from the web socket. This can be a data frame, or a control frame including
    * [[WebSocketFrame.Close]]. After receiving a close frame, no further interactions with the web socket should
    * happen.
    *
    * However, not all implementations expose the close frame, and web sockets might also get closed without the proper
    * close frame exchange. In such cases, as well as when invoking `receive` /`send` after receiving a close frame,
    * this effect will fail with the [[WebSocketClosed]] exception.
    *
    * *Should be only called sequentially!* (from a single thread/fiber). Because web socket frames might be fragmented,
    * calling this method concurrently might result in threads/fibers receiving fragments of the same frame.
    */
  def receive(): F[WebSocketFrame]

  /** Sends a web socket frame. Can be safely called from multiple threads.
    *
    * May result in a failed effect, in case of a network error, or if the socket is closed.
    */
  def send(f: WebSocketFrame, isContinuation: Boolean = false): F[Unit]
  def isOpen(): F[Boolean]

  /** Receive a single data frame, ignoring others. The frame might be a fragment. Will fail with [[WebSocketClosed]] if
    * the web socket is closed, or if a close frame is received.
    *
    * *Should be only called sequentially!* (from a single thread/fiber).
    *
    * @param pongOnPing
    *   Should a [[WebSocketFrame.Pong]] be sent when a [[WebSocketFrame.Ping]] is received.
    */
  def receiveDataFrame(pongOnPing: Boolean = true): F[WebSocketFrame.Data[_]] =
    receive().flatMap {
      case close: WebSocketFrame.Close => monad.error(WebSocketClosed(Some(close)))
      case d: WebSocketFrame.Data[_]   => monad.unit(d)
      case WebSocketFrame.Ping(payload) if pongOnPing =>
        send(WebSocketFrame.Pong(payload))
          // https://github.com/softwaremill/sttp/issues/2236: after a Ping frame is received, the WS might have become
          // closed. This would cause this call to fail with an exception, while the WS was properly used. That's why
          // we ignore the exception here, and allow for closure (or actual I/O exception) to be discovered via the
          // subsequent `receive`.
          .handleError { case _ => monad.unit(()) }
          .flatMap(_ => receiveDataFrame(pongOnPing))
      case _ => receiveDataFrame(pongOnPing)
    }

  /** Receive a single text data frame, ignoring others. The frame might be a fragment. To receive whole messages, use
    * [[receiveText]]. Will fail with [[WebSocketClosed]] if the web socket is closed, or if a close frame is received.
    *
    * *Should be only called sequentially!* (from a single thread/fiber).
    *
    * @param pongOnPing
    *   Should a [[WebSocketFrame.Pong]] be sent when a [[WebSocketFrame.Ping]] is received.
    */
  def receiveTextFrame(pongOnPing: Boolean = true): F[WebSocketFrame.Text] =
    receiveDataFrame(pongOnPing).flatMap {
      case t: WebSocketFrame.Text => t.unit
      case _                      => receiveTextFrame(pongOnPing)
    }

  /** Receive a single binary data frame, ignoring others. The frame might be a fragment. To receive whole messages, use
    * [[receiveBinary]]. Will fail with [[WebSocketClosed]] if the web socket is closed, or if a close frame is
    * received.
    *
    * *Should be only called sequentially!* (from a single thread/fiber).
    *
    * @param pongOnPing
    *   Should a [[WebSocketFrame.Pong]] be sent when a [[WebSocketFrame.Ping]] is received.
    */
  def receiveBinaryFrame(pongOnPing: Boolean = true): F[WebSocketFrame.Binary] =
    receiveDataFrame(pongOnPing).flatMap {
      case t: WebSocketFrame.Binary => t.unit
      case _                        => receiveBinaryFrame(pongOnPing)
    }

  /** Receive a single text message (which might come from multiple, fragmented frames). Ignores non-text frames and
    * returns combined results. Will fail with [[WebSocketClosed]] if the web socket is closed, or if a close frame is
    * received.
    *
    * *Should be only called sequentially!* (from a single thread/fiber).
    *
    * @param pongOnPing
    *   Should a [[WebSocketFrame.Pong]] be sent when a [[WebSocketFrame.Ping]] is received.
    */
  def receiveText(pongOnPing: Boolean = true): F[String] =
    receiveConcat(() => receiveTextFrame(pongOnPing), _ + _)

  /** Receive a single binary message (which might come from multiple, fragmented frames). Ignores non-binary frames and
    * returns combined results. Will fail with [[WebSocketClosed]] if the web socket is closed, or if a close frame is
    * received.
    *
    * *Should be only called sequentially!* (from a single thread/fiber).
    *
    * @param pongOnPing
    *   Should a [[WebSocketFrame.Pong]] be sent when a [[WebSocketFrame.Ping]] is received.
    */
  def receiveBinary(pongOnPing: Boolean): F[Array[Byte]] =
    receiveConcat(() => receiveBinaryFrame(pongOnPing), _ ++ _)

  /** Extracts the received close frame (if available) as the left side of an either, or returns the original result on
    * the right.
    *
    * Will fail with [[WebSocketClosed]] if the web socket is closed, but no close frame is available.
    *
    * @param f
    *   The effect describing web socket interactions.
    */
  def eitherClose[T](f: => F[T]): F[Either[WebSocketFrame.Close, T]] =
    f.map(t => Right(t): Either[WebSocketFrame.Close, T]).handleError { case WebSocketClosed(Some(close)) =>
      (Left(close): Either[WebSocketFrame.Close, T]).unit
    }

  /** Returns an effect computing a:
    *
    *   - `Left` if the web socket is closed - optionally with the received close frame (if available).
    *   - `Right` with the original result otherwise.
    *
    * Will never fail with a [[WebSocketClosed]].
    *
    * @param f
    *   The effect describing web socket interactions.
    */
  def either[T](f: => F[T]): F[Either[Option[WebSocketFrame.Close], T]] =
    f.map(t => Right(t): Either[Option[WebSocketFrame.Close], T]).handleError { case WebSocketClosed(close) =>
      (Left(close): Either[Option[WebSocketFrame.Close], T]).unit
    }

  private def receiveConcat[T, U <: WebSocketFrame.Data[T]](
      receiveSingle: () => F[U],
      combine: (T, T) => T
  ): F[T] = {
    receiveSingle().flatMap {
      case data if !data.finalFragment =>
        receiveConcat(receiveSingle, combine).map { t =>
          combine(data.payload, t)
        }
      case data /* if data.finalFragment */ => data.payload.unit
    }
  }

  /** Sends a web socket frame with the given payload. Can be safely called from multiple threads.
    *
    * May result in a failed effect, in case of a network error, or if the socket is closed.
    */
  def sendText(payload: String): F[Unit] = send(WebSocketFrame.text(payload))

  /** Sends a web socket frame with the given payload. Can be safely called from multiple threads.
    *
    * May result in a failed effect, in case of a network error, or if the socket is closed.
    */
  def sendBinary(payload: Array[Byte]): F[Unit] = send(WebSocketFrame.binary(payload))

  /** Idempotent when used sequentially.
    */
  def close(): F[Unit] = send(WebSocketFrame.close)

  def upgradeHeaders: Headers

  implicit def monad: MonadError[F]
}
