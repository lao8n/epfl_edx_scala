package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor }
import kvstore.Arbiter._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout

/**
  * Design choices
  * 1.  Q: How wait 1 second without blocking? How keep track of all the requests etc and timers?
  *     A: ?
  * 2.  Q: 
  */

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  /**
    * Insert(key, value, id) - This message instructs the primary to insert the (key, value) 
    * pair into the storage and replicate it to the secondaries: id is a client-chosen unique 
    * identifier for this request.
    *
    * @param key
    * @param value
    * @param id
    */
  case class Insert(key: String, value: String, id: Long) extends Operation
  /**
    * Remove(key, id) - This message instructs the primary to remove the key (and its corresponding 
    * value) from the storage and then remove it from the secondaries.
    *
    * @param key
    * @param id
    */
  case class Remove(key: String, id: Long) extends Operation
  /**
    * Instructs the replica to look up current (= ?) key in the storage and reply with the stored
    * value
    *
    * @param key
    * @param id
    */
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  /**
    * If an update (insert or remove) is successful it results in a OperationAck message to 
    * the client (specifically the sender)
    * @param id
    */
  case class OperationAck(id: Long) extends OperationReply
  /**
    * A failed update (insert or remove) results in an OperationFailed(id) reply. Failure is
    * inability to perform the operation within 1 second.
    *
    * @param id
    */
  case class OperationFailed(id: Long) extends OperationReply
  /**
    * Get operation results in a GetResult message to be sent back to the sender of the lookup 
    * request. The id is from the Get message and the valueOption field should contain None if
    * the key is not present in the replica or Some(value) if a value is currently assigned to 
    * the given key of the replica
    *
    * @param key
    * @param valueOption
    * @param id
    */
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  // send arbiter request to join
  arbiter ! Join

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. 
   * Primary replica A distinguished node in the cluster that accepts updates to 
   * keys and propagates the changes to secondary replicas.
   * If primary node, is responsible for replicating all changes to a set of secondary nodes
   * Only the primary replica accepts modification events (insertions and removals)
   * Both the primary and secondary nodes will accept lookup (read) events.
   * Updates are only possible on the primary receive
   * The primary node does not fail during the uptime of the system
   * If replicas leave the cluster which is signalled by sending a new Replicas
   * message to the primary then outstanding acknowledgements of these replicas
   * must be waived. This can lead to the generation of an OperationAck triggered
   * indirectly by the Replicas message 
   * */
  val leader: Receive = {
    case Insert(k, v, id) => {
      kv += (k -> v)
      // TODO add 1s test for success/failure
      // val future = replica ? Insert(k, v, id)
      // val result = Await.result(future, 1 second).

      sender ! OperationAck(id)
      // sender ! OperationFailed(id)
    } 
    case Remove(k, id) => {
      kv -= k
      // TODO add 1s test for success/failure 
      sender ! OperationAck(id)
    }
    case Get(k, id) => {
      sender ! GetResult(k, kv.get(k), id)
    }
  }

  /* TODO Behavior for the replica role. 
   * Secondary replicas Nodes that are in contact with the primary replica, 
   * accepting updates from it and serving clients for read-only operations.
   * Replica nodes might join and leave at arbitrary times.
   * Both the primary and secondary nodes will accept lookup (read) events, although the 
   * secondary nodes can be 'out-of-date' since it takes time for the replicas to keep up with 
   * the changes on the primary replica.
   * Each replica has the freedom to immediately hand out the updated value to subsequently 
   * reading clients, even before the new value has been persisted locally and no rollback
   * is attempted in case of failure.
   * */
  val replica: Receive = {
    case _ =>
  }

}

