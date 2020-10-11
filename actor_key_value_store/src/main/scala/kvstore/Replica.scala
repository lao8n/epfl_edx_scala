package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor }
import kvstore.Arbiter._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout

/**
  * Design choices
  * 1.  Q: How wait 1 second without blocking? How keep track of all the requests etc and timers?
  *     A: Is it something to do with manually waiting time or can be it be done more robustly using
  *        the Akka APIs?
  * 2.  Q: Should the read-only replicas ignored Insert & Remove requests or respond with OperationFailed?
  *     A: ?
  * 3.  Q: The protocol is designed that basically the same message of insert/replicate is differentiated
  *        not by different senders but rather by different message protocols? Does this make sense?
  *     A: I guess the dis of current is you could have rogue node sending replicate messages, adv of 
  *        current is you don't have to maintain a list of replicators (although we do this anyway...)
  * 4.  Q: How do we handle seq number? should it be an immutable become associated argument?
  *        Or just a var - but if a var then do we reset if a secondary replica becomes a primary replica?
  *        And vice-versa?
  *     A: ?
  * 5.  Q: What is the point of adding the additional replicator? 
  *     A: Is it to add an interface to the replica which can handle failure etc? The videos talk more
  *        about explicit failures rather than just not getting anything back like in our case, so I think
  *        I should just do it manually
  * 6.  Q: How to cycle through acks every 100 ms? The architecture to do it system.scheduler.schedule
  *        is on a per-message basis so how to trigger a potential list of messages?
  *     A: There is an example with 
  *        context.system.scheduler.scheduleOnce(10.seconds, self, Timeout)
  *        def receive = { case Timeout => children foreach(_ ! Getter.Abort)} i.e. Timeout is a message!!!
  *   * 7.  Q: Should acks be a list of unacknowledged messages or all messages over the lifecycle?
  *     A: ?
  * 8.  Q: When use ask vs when use tell?
  *     A: Ask is blocking so not great? https://doc.akka.io/docs/akka/2.5/futures.html
  * 9.  Q: Should i use ack-retry? https://www.mjlivesey.co.uk/2016/02/19/akka-delivery-guarantees.html
  *     A: Surely not because it seems to be blocking going to a new waiting context with a time out 
  * 10. Q: Should the at-least-once machinery be push or pull led? https://www.lightbend.com/blog/how-akka-works-at-least-once-message-delivery
  *     A: Maybe pull?. Notes such that it is push because 'the replicator must make sure to periodically
  *        retransmit all unacknowledged changes'
  * 11. Q: Unclear if we need acks to be maintained with all operations or we can use it as a list of 
  *        unacknowledge messages? 
  *     A: Have an additional unacks map which we use for resending and if it turns out don't need acks
  *        then will delete it. Actual no need - this seems to be handled by the ask machinery
  * 12. Q: How pattern match on multiple timeouts?
  *     A: Current solution is to create two vals with different timeout values - but same types? Can it 
  *        differentiate?
  * 13. Q: How remove items from vector? 
  *     A: There doesn't seem to be a remove method so I guess just loop through and then re-assign
  *        var to an empty Snapshot
  * 14. Q: Maybe do not need an explicit unacks but instead just need to figure out what longs are not in
  *        acks?
  *     A: But what about the latest number? Not clear about this.
  * 15. Q: Why is there is a separate seq from id? surely these can be the same ascending order?
  *     A: I thikn the idea is that id is about replica to replicator , and seq is replicator to secondary 
  *        replica and they shouldn't be mixed
  * 16. Q: How to handle pending and unacks? 
  *     A: Actually unacks doesn't need long or actorref and we can just use pending as the queue
  *        for unacknowledge requests as well. Actually we can go even further combining the batch 
  *        and unacknowledged timeouts together. Actually we cannot combine them as we need a key
  *        to handle unacknowledged requests. We make the implementation more sophisticated by only 
  *        adding to unacknowledged once it has been batched and sent.
  * 17. Q: What do with acks?
  *     A: We are now using it is a list of - not even acknowledgements but a list of Replicate requests
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
  // seq number
  var replicaSeq = 0
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
    case Get(k, id) => {
      sender ! GetResult(k, kv.get(k), id)
    }
    case Snapshot(k, v, seq) => {
      if(seq > replicaSeq){
        // ignore
      }
      else if (seq < replicaSeq) {
        sender ! SnapshotAck(k, seq)
      }
      else {
        v match {
          // insert
          case Some(v) => {
            kv += (k -> v)
          }
          case None => kv -= k
        }
        replicaSeq += 1
        sender ! SnapshotAck(k, seq)
      }
    }
    case Replicated(k, id) => // TODO
    case _ =>
  }

}

