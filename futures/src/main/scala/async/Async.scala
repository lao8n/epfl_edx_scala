package async

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

object Async extends AsyncInterface {

  /**
    * Transforms a successful asynchronous `Int` computation
    * into a `Boolean` indicating whether the number was even or not.
    * In case the given `Future` value failed, this method
    * should return a failed `Future` with the same error.
    */
  def transformSuccess(eventuallyX: Future[Int]): Future[Boolean] = {
    // do not need to handle Failure case
    eventuallyX map(_ % 2 == 0)

    // transform = f: (Try[T]) => Try[S] returning a Future[S]
    // eventuallyX transform {
    //   case Success(num) => {
    //     if (num % 2 == 0) Success(true)
    //     else Success(false)
    //   }
    //   case Failure(error) => Failure(error)
    // }
  }

  /**
    * Transforms a failed asynchronous `Int` computation into a
    * successful one returning `-1`.
    * Any non-fatal failure should be recovered.
    * In case the given `Future` value was successful, this method
    * should return a successful `Future` with the same value.
    */
  def recoverFailure(eventuallyX: Future[Int]): Future[Int] = {
    // do not need to handle success case
    eventuallyX recover {
      case NonFatal(_) => -1
    }

    // transform = f: (Try[T]) => Try[S] returning a Future[S]
    // improvement on old 2.11 transform which has
    // transform = s: (T) => S, f: (Throwable) => Throwable
    // and thus to recover from a failure you needed to do one of 
    // 1. map + recover 2. onComplete + promise
    // eventuallyX transform {
    //   case Success(value) => Success(value)
    //   case Failure(_) => Success(-1)
    // }
  }

  /**
    * Perform two asynchronous computation, one after the other. `makeAsyncComputation2`
    * should start ''after'' the `Future` returned by `makeAsyncComputation1` has
    * completed.
    * In case the first asynchronous computation failed, the second one should not even
    * be started.
    * The returned `Future` value should contain the successful result of the first and
    * second asynchronous computations, paired together.
    */
  def sequenceComputations[A, B](
    makeAsyncComputation1: () => Future[A],
    makeAsyncComputation2: () => Future[B]
  ): Future[(A, B)] = {
    // do not need flatMap & map as for is sequential
    for {
      comp1 <- makeAsyncComputation1()
      comp2 <- makeAsyncComputation2()
    } yield (comp1, comp2)
    
    // makeAsyncComputation1() flatMap { comp1 =>
    //   makeAsyncComputation2() map (comp2 => (comp1, comp2))      
    // }
  }

  /**
    * Concurrently perform two asynchronous computations and pair their successful
    * result together.
    * The two computations should be started independently of each other.
    * If one of them fails, this method should return the failure.
    */
  def concurrentComputations[A, B](
    makeAsyncComputation1: () => Future[A],
    makeAsyncComputation2: () => Future[B]
  ): Future[(A, B)] =
    makeAsyncComputation1() zip makeAsyncComputation2()
    
  /**
    * Attempt to perform an asynchronous computation.
    * In case of failure this method should try again to make
    * the asynchronous computation so that at most `maxAttempts`
    * are eventually performed.
    */

  def insist[A](makeAsyncComputation: () => Future[A], maxAttempts: Int): Future[A] = {
    // didn't want to use recover as didn't want to use Throwable
    makeAsyncComputation() transformWith {
      case Success(value) => Future.successful(value)
      case Failure(error) => {
        if(maxAttempts > 1) insist(makeAsyncComputation, maxAttempts - 1)
        else Future.failed(error)
      }
    }
  }

  /**
    * Turns a callback-based API into a Future-based API
    * @return A `FutureBasedApi` that forwards calls to `computeIntAsync` to the `callbackBasedApi`
    *         and returns its result in a `Future` value
    *
    * Hint: Use a `Promise`
    */
  def futurize(callbackBasedApi: CallbackBasedApi): FutureBasedApi = {
    () => {
      val p = Promise[Int]
      callbackBasedApi.computeIntAsync {
        case Success(s) => p.success(s)
        case Failure(e) => p.failure(e)
      }
      p.future
    }
  }

}

/**
  * Dummy example of a callback-based API
  */
trait CallbackBasedApi {
  def computeIntAsync(continuation: Try[Int] => Unit): Unit
}

/**
  * API similar to [[CallbackBasedApi]], but based on `Future` instead
  */
trait FutureBasedApi {
  def computeIntAsync(): Future[Int]
}
