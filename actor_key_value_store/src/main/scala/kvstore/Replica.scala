package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor }
import kvstore.Arbiter._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import Persistence.Persisted
import akka.event.LoggingReceive
import scala.concurrent.Future

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  case class RetryPersistence(persistMessage: Future[Persisted], remainingAttempts: Int, persistRetryTimeout: Timeout)

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  var replicaSeq = 0
  // send arbiter request to join
  arbiter ! Join 
  override val supervisorStrategy = OneForOneStrategy() {
    case _: Exception => SupervisorStrategy.Restart
  }
  val replicaPersistence = context.actorOf(persistenceProps, "replicaPersistence")

  def receive = {
    case JoinedPrimary   => context.become(primary)
    case JoinedSecondary => context.become(secondary)
  }

  val primary: Receive = {
    case Get(k, id) => sender ! GetResult(k, kv.get(k), id)
    case Insert(k, v, id) => {
      insert(k, v)
      sender ! OperationAck(id)
    }
    case Remove(k, id) => {
      remove(k)
      sender ! OperationAck(id)
    }
  }

  val secondary: Receive = LoggingReceive {
    case Get(k, id) => sender ! GetResult(k, kv.get(k), id)
    case Snapshot(k, v, seq) => {
      if(seq > replicaSeq){} // do nothing
      else if (seq < replicaSeq) { sender ! SnapshotAck(k, seq) }
      else {
        v match {
          case Some(v) => insert(k, v)
          case None => remove(k)
        }
        replicaSeq += 1
        // at least one attempt before 1 second, max 10 attempts
        self ! RetryPersistence(persistFuture, 5, Timeout(100.milliseconds))
        secondaries = secondaries + (self -> sender)
      }
    }

    // case RetryPersistence(persistFuture, remainingAttempts, persistRetryTimeout) => {
    //   persistFuture map {
    //     case message: Persisted => self ! message
    //   } recover {
    //     case _: akka.pattern.AskTimeoutException => 
    //       if(remainingAttempts - 1 > 0){
    //         self ! RetryPersistence(persistFuture, remainingAttempts - 1, persistRetryTimeout)
    //       }
    //   } 
          // case RetryPersistence(persistMessage, remainingAttempts, persistRetryTimeout) => {
    //   implicit val timeout = persistRetryTimeout
    //   val persistFuture: Future[Persisted] = (replicaPersistence ? persistMessage).mapTo[Persisted]

    //   persistFuture map {
    //     case message: Persisted => self ! message
    //   } recover {
    //     case _: akka.pattern.AskTimeoutException => 
    //       if(remainingAttempts - 1 > 0){
    //         self ! RetryPersistence(persistMessage, remainingAttempts - 1, persistRetryTimeout)
    //       }
    //   } 
      
      // pipe(persistFuture) to self 
      // recover {
      //   case _: akka.pattern.AskTimeoutException => 
      //     if(remainingAttempts - 1 > 0){
      //       self ! RetryPersistence(persistMessage, remainingAttempts - 1, persistRetryTimeout)
      //     }
      // } 
      
      // match {
      //   case message: Future[Persisted]=> {
      //     pipe(message) to self
      //   }
      // } recover {
      //   case _: akka.pattern.AskTimeoutException => 
      //     if(remainingAttempts - 1 > 0){
      //       self ! RetryPersistence(persistMessage, remainingAttempts - 1, persistRetryTimeout)
      //     }
      // }
        // we onComplete the future rather than require Persisted

        // persistFuture map {
        //   case Persist(k, v, seq) => secondaries get self match {
        //     case Some(replicator) => replicator ! SnapshotAck(k, seq)
        //     case None => // error          
        //   } 
        // } recover {
        //   case _: akka.pattern.AskTimeoutException => 
        //     self ! RetryPersistence(persistMessage, remainingAttempts - 1, timeout)
        // }
    // }
    case Persisted(k, seq) => {
      secondaries get self match {
        case Some(replicator) => replicator ! SnapshotAck(k, seq)
        case None => // error
      }
    }
    // case Persisted(k, seq) => {
    //   unacksPersistence = unacksPersistence - seq
    //   secondaries get self match {
    //     case Some(replicator) => replicator ! SnapshotAck(k, seq)
    //     case None => // error
    //   }
    // }
    // case `unacksPersistenceTimeout` => {
    //   unacksPersistence foreach {
    //     case (_, persistMessage) => replicaPersistence ! persistMessage 
    //   }
    // }
    case _ => // ignore Insert & Remove requests
  }

  // helper methods
  def insert(key: String, value: String){
    kv += (key -> value)
  }

  def remove(key: String){
    kv -= key
  }

  // def retryMessage(destination: ActorRef, message: Any, remainingAttempts: Int): Future[Any] = {
  //   val future = (destination ? message) recover {
  //     case _: akka.pattern.AskTimeoutException => 
  //       if(remainingAttempts > 1){
  //         retryMessage(destination, message, remainingAttempts - 1)
  //       }
  //   }
  // }
}