package protocols

import akka.actor.typed._
import akka.actor.typed.scaladsl._

import scala.concurrent.duration._

object Transactor {
  // Extending Product with Serializable is only a hint to the type checker to improve 
  // error messages since all case classes extends these traits.
  sealed trait PrivateCommand[T] extends Product with Serializable
  final case class Committed[T](session: ActorRef[Session[T]], value: T) extends PrivateCommand[T]
  final case class RolledBack[T](session: ActorRef[Session[T]]) extends PrivateCommand[T]

  sealed trait Command[T] extends PrivateCommand[T]
  final case class Begin[T](replyTo: ActorRef[ActorRef[Session[T]]]) extends Command[T]

  sealed trait Session[T] extends Product with Serializable
  final case class Extract[T, U](f: T => U, replyTo: ActorRef[U]) extends Session[T]
  final case class Modify[T, U](f: T => T, id: Long, reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Commit[T, U](reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Rollback[T]() extends Session[T]

  /**
    * @return A behavior that accepts public [[Command]] messages. The behavior
    *         should be wrapped in a [[SelectiveReceive]] decorator (with a capacity
    *         of 30 messages) so that beginning new sessions while there is already
    *         a currently running session is deferred to the point where the current
    *         session is terminated.
    * @param value Initial value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    */
  def apply[T](value: T, sessionTimeout: FiniteDuration): Behavior[Command[T]] = 
    SelectiveReceive(30, idle(value, sessionTimeout).narrow)

  /**
    * @return A behavior that defines how to react to any [[PrivateCommand]] when the transactor
    *         has no currently running session.
    *         [[Committed]] and [[RolledBack]] messages should be ignored, and a [[Begin]] message
    *         should create a new session.
    *
    * @param value Value of the transactor
    * @param sessionTimeout Delay before rolling back the pending modifications and
    *                       terminating the session
    *
    * Note: To implement the timeout you have to use `ctx.scheduleOnce` instead of `Behaviors.withTimers`, due
    *       to a current limitation of Akka: https://github.com/akka/akka/issues/24686
    *
    * Hints:
    *   - When a [[Begin]] message is received, an anonymous child actor handling the session should be spawned,
    *   - In case the child actor is terminated, the session should be rolled back,
    *   - When `sessionTimeout` expires, the session should be rolled back,
    *   - After a session is started, the next behavior should be [[inSession]],
    *   - Messages other than [[Begin]] should not change the behavior.
    */
  private def idle[T](value: T, sessionTimeout: FiniteDuration): Behavior[PrivateCommand[T]] = 
    // Cannot use Behaviors.setup as this defers behavior until an actor is started
    Behaviors.receive {
      case (ctx, Begin(replyTo)) =>
        ctx.log.debug("Received message Begin")
        val session = ctx.spawnAnonymous(sessionHandler(value, ctx.self, Set.empty))
        replyTo ! session // so replyTo knows who to send Session[T] message to
        ctx.watchWith(session, RolledBack(session))
        // cannot use ctx.scheduleOnce(sessionTimeout, session, RolledBack(session)) as ctx.scheduleOnce
        // as suggested above requires args = target: ActorRef[U], msg: U 
        // https://doc.akka.io/api/akka/current/akka/actor/typed/scaladsl/ActorContext.html
        // i mistakedly tried ctx.setReceiveTimeout in sessionHandler but that doesn't work as
        // no RolledBack message was sent
        // i also mistakedly sent RolledBack to session resulting in mismatched types
        // i also mistakedly try to send RollBack to session which is also incorrect as no RolledBack is sent
        ctx.scheduleOnce(sessionTimeout, ctx.self, RolledBack(session))
        inSession(value, sessionTimeout, session)
      case _ =>
        Behaviors.same // don't actually want to ignore as want to receive other messages
  }  

  /**
    * @return A behavior that defines how to react to [[PrivateCommand]] messages when the transactor has
    *         a running session.
    *         [[Committed]] and [[RolledBack]] messages should commit and rollback the session, respectively.
    *         [[Begin]] messages should be unhandled (they will be handled by the [[SelectiveReceive]] decorator).
    *
    * @param rollbackValue Value to rollback to
    * @param sessionTimeout Timeout to use for the next session
    * @param sessionRef Reference to the child [[Session]] actor
    */
  private def inSession[T](rollbackValue: T, sessionTimeout: FiniteDuration, sessionRef: ActorRef[Session[T]]): Behavior[PrivateCommand[T]] =
    Behaviors.setup { ctx => 
      Behaviors.receiveMessage[PrivateCommand[T]] {
        case Committed(session, value) =>
          if(session eq sessionRef) idle(value, sessionTimeout)
          else Behaviors.same
        case RolledBack(session) =>
          if(session eq sessionRef){
            ctx stop session
            session ! Rollback() // we let session handle its own stopping
            // counter-intuitively we 1. RolledBack and then 2. Rollback
            idle(rollbackValue, sessionTimeout)
          }
          else 
            Behaviors.same        
        case Begin(_) => Behaviors.unhandled
      }
    }

  /**
    * @return A behavior handling [[Session]] messages. See in the instructions
    *         the precise semantics that each message should have.
    *
    * @param currentValue The session’s current value
    * @param commit Parent actor reference, to send the [[Committed]] message to
    * @param done Set of already applied [[Modify]] messages
    */
  private def sessionHandler[T](currentValue: T, commit: ActorRef[Committed[T]], done: Set[Long]): Behavior[Session[T]] =
    Behaviors.setup { ctx =>   
      Behaviors.receiveMessage {
        case Extract(f, replyTo: ActorRef[Any]) => 
          replyTo ! currentValue
          Behaviors.same
        case Modify(f, id, reply, replyTo: ActorRef[Any]) => 
          if(done contains id){
            replyTo ! reply
            Behaviors.same
          } else {
            replyTo ! reply 
            val modifiedValue = f(currentValue)
            val modifiedDone = done + id
            sessionHandler(modifiedValue, commit, modifiedDone)
          }
        case Commit(reply, replyTo: ActorRef[Any]) => 
          replyTo ! reply
          commit ! Committed(ctx.self, currentValue)
          Behaviors.stopped
        case Rollback() => Behaviors.stopped
    }
  }
}
