package kvstore

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.util.Timeout
import akka.event.LoggingReceive

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import context.dispatcher
  
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

  def receive: Receive = LoggingReceive {
    case Replicate(k, v, id) => batchReplicate(sender, Replicate(k, v, id))
    case SnapshotAck(k, seq) => confirmDelivery(k, seq)
    case `batchSnapshotTimeout` => {
      pending foreach {
        case Snapshot(k, v, seq) => deliver(replica, Snapshot(k, v, seq), seq)
      }
      pending = Vector.empty[Snapshot]
    }
    case `unackSnapshotTimeout` => {
      unacks foreach {
        case (_, snapshot) => replica ! snapshot
      }
    }
  }

  // helper methods
  def batchReplicate(requester: ActorRef, message: Replicate){
    val seq = nextSeq()
    message match {
      case Replicate(k, v, id) => {
        acks = acks + (seq -> ((requester, message)))
        pending = pending :+ Snapshot(k, v, seq)
      }
    }
  }

  def confirmDelivery(key: String, seq: Long){
    unacks = unacks - seq
    acks get seq match {
      case Some((requester, Replicate(_, _, id))) => requester ! Replicated(key, id)
      case None => // do nothing - it should be there
    }
  }

  def deliver(replica: ActorRef, message: Snapshot, seq: Long){
    replica ! message
    unacks = unacks + (seq -> message)
  }
}
