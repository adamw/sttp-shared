package sttp.monad

import sttp.shared.Identity

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** A basic monad interface, allowing abstract manipulation of effectful values, represented using the type constructor
  * `F`.
  *
  * A computation yielding results of type `T` is represented as a value of type `F[T]`. Such values: * can be
  * transformed using `map` * can be run in sequence using `flatMap` * errors can be handled using `handleError` * and
  * new computations can be created using `unit`, `eval` and `suspend`
  *
  * To use convenient `.map`, `.flatMap` syntax, make sure an implicit instance of `MonadError` is in scope, and import:
  * `import sttp.monad.syntax._`. This adds the appropriate extension methods.
  */
trait MonadError[F[_]] {
  def unit[T](t: T): F[T]
  def map[T, T2](fa: F[T])(f: T => T2): F[T2]
  def flatMap[T, T2](fa: F[T])(f: T => F[T2]): F[T2]

  def error[T](t: Throwable): F[T]
  protected def handleWrappedError[T](rt: F[T])(h: PartialFunction[Throwable, F[T]]): F[T]
  def handleError[T](rt: => F[T])(h: PartialFunction[Throwable, F[T]]): F[T] = {
    try handleWrappedError(rt)(h)
    catch {
      case e: Throwable if h.isDefinedAt(e) => h(e)
      case e: Throwable                     => error(e)
    }
  }

  def eval[T](t: => T): F[T] = map(unit(()))(_ => t)
  def suspend[T](t: => F[T]): F[T] = flatten(eval(t))
  def flatten[T](ffa: F[F[T]]): F[T] = flatMap[F[T], T](ffa)(identity)

  def flatTap[T, U](fa: F[T])(f: T => F[U]): F[T] = flatMap(fa)(t => map(f(t))(_ => t))

  def fromTry[T](t: Try[T]): F[T] =
    t match {
      case Success(v) => unit(v)
      case Failure(e) => error(e)
    }

  def ensure[T](f: F[T], e: => F[Unit]): F[T]

  def blocking[T](t: => T): F[T] = eval(t)
}

object MonadError {
  def apply[F[_]: MonadError]: MonadError[F] = implicitly[MonadError[F]]
}

trait MonadAsyncError[F[_]] extends MonadError[F] {
  def async[T](register: (Either[Throwable, T] => Unit) => Canceler): F[T]
}

case class Canceler(cancel: () => Unit)

object syntax {
  implicit final class MonadErrorOps[F[_], A](r: => F[A]) {
    def map[B](f: A => B)(implicit ME: MonadError[F]): F[B] = ME.map(r)(f)
    def flatMap[B](f: A => F[B])(implicit ME: MonadError[F]): F[B] = ME.flatMap(r)(f)
    def handleError[T](h: PartialFunction[Throwable, F[A]])(implicit ME: MonadError[F]): F[A] = ME.handleError(r)(h)
    def ensure(e: => F[Unit])(implicit ME: MonadError[F]): F[A] = ME.ensure(r, e)
    def flatTap[B](f: A => F[B])(implicit ME: MonadError[F]): F[A] = ME.flatTap(r)(f)
  }

  implicit final class MonadErrorValueOps[F[_], A](private val v: A) extends AnyVal {
    def unit(implicit ME: MonadError[F]): F[A] = ME.unit(v)
  }
}

object EitherMonad extends MonadError[Either[Throwable, *]] {
  type R[+T] = Either[Throwable, T]

  override def unit[T](t: T): R[T] =
    Right(t)

  override def map[T, T2](fa: R[T])(f: T => T2): R[T2] =
    fa match {
      case Right(b) => Right(f(b))
      case _        => fa.asInstanceOf[R[T2]]
    }

  override def flatMap[T, T2](fa: R[T])(f: T => R[T2]): R[T2] =
    fa match {
      case Right(b) => f(b)
      case _        => fa.asInstanceOf[R[T2]]
    }

  override def error[T](t: Throwable): R[T] =
    Left(t)

  override protected def handleWrappedError[T](rt: R[T])(h: PartialFunction[Throwable, R[T]]): R[T] =
    rt match {
      case Left(a) if h.isDefinedAt(a) => h(a)
      case _                           => rt
    }

  override def ensure[T](f: Either[Throwable, T], e: => Either[Throwable, Unit]): Either[Throwable, T] = {
    def runE =
      Try(e) match {
        case Failure(f) => Left(f)
        case Success(v) => v
      }
    f match {
      case Left(f)  => runE.right.flatMap(_ => Left(f))
      case Right(v) => runE.right.map(_ => v)
    }
  }
}

object TryMonad extends MonadError[Try] {
  override def unit[T](t: T): Try[T] = Success(t)
  override def map[T, T2](fa: Try[T])(f: (T) => T2): Try[T2] = fa.map(f)
  override def flatMap[T, T2](fa: Try[T])(f: (T) => Try[T2]): Try[T2] =
    fa.flatMap(f)

  override def error[T](t: Throwable): Try[T] = Failure(t)
  override protected def handleWrappedError[T](rt: Try[T])(h: PartialFunction[Throwable, Try[T]]): Try[T] =
    rt.recoverWith(h)

  override def eval[T](t: => T): Try[T] = Try(t)

  override def fromTry[T](t: Try[T]): Try[T] = t

  override def ensure[T](f: Try[T], e: => Try[Unit]): Try[T] =
    f match {
      case Success(v) => Try(e).flatten.map(_ => v)
      case Failure(f) => Try(e).flatten.flatMap(_ => Failure(f))
    }
}
class FutureMonad(implicit ec: ExecutionContext) extends MonadAsyncError[Future] {
  override def unit[T](t: T): Future[T] = Future.successful(t)
  override def map[T, T2](fa: Future[T])(f: (T) => T2): Future[T2] = fa.map(f)
  override def flatMap[T, T2](fa: Future[T])(f: (T) => Future[T2]): Future[T2] =
    fa.flatMap(f)

  override def error[T](t: Throwable): Future[T] = Future.failed(t)
  override protected def handleWrappedError[T](rt: Future[T])(h: PartialFunction[Throwable, Future[T]]): Future[T] =
    rt.recoverWith(h)

  override def eval[T](t: => T): Future[T] = Future(t)
  override def suspend[T](t: => Future[T]): Future[T] = Future(t).flatMap(identity)

  override def fromTry[T](t: Try[T]): Future[T] = Future.fromTry(t)

  override def async[T](register: (Either[Throwable, T] => Unit) => Canceler): Future[T] = {
    val p = Promise[T]()
    register {
      case Left(t)  => p.failure(t)
      case Right(t) => p.success(t)
    }
    p.future
  }

  override def ensure[T](f: Future[T], e: => Future[Unit]): Future[T] = {
    val p = Promise[T]()
    def runE =
      Try(e) match {
        case Failure(f) => Future.failed(f)
        case Success(v) => v
      }
    f.onComplete {
      case Success(v) => runE.map(_ => v).onComplete(p.complete(_))
      case Failure(f) => runE.flatMap(_ => Future.failed(f)).onComplete(p.complete(_))
    }
    p.future
  }

  override def blocking[T](t: => T): Future[T] = Future(scala.concurrent.blocking(t))
}

object IdentityMonad extends MonadError[Identity] {
  override def unit[T](t: T): Identity[T] = t
  override def map[T, T2](fa: Identity[T])(f: T => T2): Identity[T2] = f(fa)
  override def flatMap[T, T2](fa: Identity[T])(f: T => Identity[T2]): Identity[T2] = f(fa)
  override def error[T](t: Throwable): Identity[T] = throw t
  override protected def handleWrappedError[T](rt: Identity[T])(
      h: PartialFunction[Throwable, Identity[T]]
  ): Identity[T] = rt
  override def eval[T](t: => T): Identity[T] = t
  override def ensure[T](f: Identity[T], e: => Identity[Unit]): Identity[T] =
    try f
    finally e
}
