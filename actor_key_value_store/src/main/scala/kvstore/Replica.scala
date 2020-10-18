package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor }
import kvstore.Arbiter._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout

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

  val secondary: Receive = {
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
        sender ! SnapshotAck(k, seq)
      }
    }
    case _ => // Insert & Remove requests
  }

  // Helper methods
  def insert(key: String, value: String){
    kv += (key -> value)
  }
  def remove(key: String){
    kv -= key
  }
}

