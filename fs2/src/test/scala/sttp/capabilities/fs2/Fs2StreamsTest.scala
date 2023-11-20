package sttp.capabilities.fs2

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import fs2._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AsyncFlatSpec
import sttp.capabilities.StreamMaxLengthExceeded

class Fs2StreamsTest extends AsyncFlatSpec with Matchers {
  behavior of "Fs2Streams"

  it should "Pass all bytes if limit is not exceeded" in {
    // given
    val inputByteCount = 8192
    val maxBytes = 8192L
    val inputStream = Stream.fromIterator[IO](Iterator.fill[Byte](inputByteCount)('5'.toByte), chunkSize = 1024)

    // when
    val stream = Fs2Streams.apply[IO].limitBytes(inputStream, maxBytes)

    // then
    stream.fold(0L)((acc, _) => acc + 1).compile.lastOrError.unsafeToFuture().map { count =>
      count shouldBe inputByteCount
    }
  }

  it should "Fail stream if limit is exceeded" in {
    // given
    val inputByteCount = 8192
    val maxBytes = 8191L
    val inputStream = Stream.fromIterator[IO](Iterator.fill[Byte](inputByteCount)('5'.toByte), chunkSize = 1024)

    // when
    val stream = Fs2Streams.apply[IO].limitBytes(inputStream, maxBytes)

    // then
    stream.compile.drain
      .map(_ => fail("Unexpected end of stream."))
      .handleErrorWith {
        case StreamMaxLengthExceeded(limit) =>
          IO(limit shouldBe maxBytes)
        case other =>
          IO(fail(s"Unexpected failure cause: $other"))
      }
      .unsafeToFuture()
  }
}
