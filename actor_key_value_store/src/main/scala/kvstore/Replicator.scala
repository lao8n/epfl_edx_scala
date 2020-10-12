package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration._

object Replicator {
  /**
   * replica -> replicator  
   * used by the replica actor which request replication of an update, in the case of 
   * insert valueOption will be Some(value) in the case of remove it will be None. 
   * this is sent from the Replica itself
   * @param key
   * @param valueOption
   * @param id
   */
  case class Replicate(key: String, valueOption: Option[String], id: Long)

  /**
   * replicator -> replica
   * is sent as a reply to the corresponding Replicate message once replication of 
   * that update has been successfully completed. The sender of the replicate message
   * is the Replicator.
   *
   * @param key
   * @param id
   */
  case class Replicated(key: String, id: Long)
  
  /**
   * partner replicator -> secondary partner replica
   * replicator communicating with partner replica with Snapshot & SnapshotAck
   * sent by replicator to appropriate secondary replica to indicate a new state 
   * of the given key. 
   * updates for a given secondary replica must be processed in contiguous ascending
   * sequence number order (starting at 0)
   * if a snapshot arrives at a replica with a sequence number > than currently 
   * expected number it is ignored (no change in state and no reaction)
   * if a snapshot arrives arrives at a replica with a sequence number < than 
   * the currently expected number then the snapshot must be ignored and 
   * immediately acknowledge as described below
   * note the sender reference when sending a snapshot message must be the 
   * replicator actor (not the primary replica actor or any other)
   * need to consider case that snap shot message is lost on the way
   */
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)

  /**
   * secondary partner replica -> partner Replicator
   * sent as soon as updated is persisted logically by the secondary replica, this might 
   * never be sent if unable to persist the update
   * acknowledgement is sent immediately for requests whose sequence nubmer is less than 
   * the expected number.
   * the expected number is set to the > of the previously expected number and the seq
   * number just acknowledged, incremented by 1
   * need to consider that snapshotack message is lost along the way
   * 
   * @param key
   * @param seq
   */
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}
/**
  * the replicator can handle multiple snapshots of a given key in parallel (i.e. 
  * replication initiated but not yet completed)
  * the replicator is allowed but not required to batch changes before sending them to 
  * the secondary replica, provided that each replication request is acknowledged 
  * properly and in the right sequence when complete.
  *
  * @param replica
  */
class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   * Each replica (just secondary) has a corresponding replicator whose role is to accept 
   * update events and propagate the changes to its corresponding replica (there must be 
   * exactly one replicator per secondary replica). Also notice that at the creation time of the 
   * Replicator the primary must forward update events for every key-value pair it currently holds
   * to this replicator 
   * Need to consider the case that either a Snapshot or SnapshotAck message is lost on the way
   * The replicator must make sure to periodically retransmit all unacknowledged changes.
   * For grading purposes it is assumed it happens every 100ms and for batching we assume
   * that a lost Snapshot message will lead to a resend at most 200ms after the Replicate request
   * was received.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]
  var unacks = Map.empty[Long, Snapshot]
  
  var _seqCounter = 0L
  def nextSeq() = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  // batch requests at least every 200ms, we set at 150ms
  val batchSnapshotTimeout : Timeout = Timeout(150.milliseconds)
  context.system.scheduler.scheduleWithFixedDelay(Duration.Zero, 150.milliseconds, self, batchSnapshotTimeout)
  // send unacknowledged requests at least every 100ms, we set at 50ms
  val unackSnapshotTimeout : Timeout = Timeout(50.milliseconds)
  context.system.scheduler.scheduleWithFixedDelay(Duration.Zero, 50.milliseconds, self, unackSnapshotTimeout)

  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case Replicate(k, v, id) => {
      val seq = nextSeq()
      // we batch snapshot requests instead of messaging immediately
      // replica ! Snapshot(k, v, seq)
      acks = acks + (seq -> ((sender, Replicate(k, v, id))))
      pending =  pending :+ Snapshot(k, v, seq)
    }
    case SnapshotAck(k, seq) => {
      unacks = unacks - seq
      acks get seq match {
        case Some((requester, Replicate(_, _, id))) => requester ! Replicated(k, id)
        case None => // do nothing - it should be there
      }
    }
    // backticks to pattern match on value not type
    case `batchSnapshotTimeout` => {
      pending foreach { 
        case Snapshot(k, v, seq) => {
          replica ! Snapshot(k, v, seq)
          unacks = unacks + (seq -> Snapshot(k, v, seq))
        } 
      }
      pending = Vector.empty[Snapshot]
    }
    case `unackSnapshotTimeout` => {
      unacks foreach {
        case (_, snapshot) => replica ! snapshot
      }
    }
  }
}
