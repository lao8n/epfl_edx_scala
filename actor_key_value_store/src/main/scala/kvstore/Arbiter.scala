package kvstore

import akka.actor.{ActorRef, Actor}

object Arbiter {
  case object Join

  case object JoinedPrimary
  case object JoinedSecondary

  /**
   * This message contains all replicas currently known to the arbiter, including the primary.
   */
  case class Replicas(replicas: Set[ActorRef])
}

/**
  * Arbiter: A subsystem that is provided in this exercise and which 
  * assigns the primary or secondary roles to your nodes.
  * We assume that membership is handled reliably by a provided subsystem
  */
class Arbiter extends Actor {
  import Arbiter._
  var leader: Option[ActorRef] = None
  var replicas = Set.empty[ActorRef]

  def receive = {
    /**
     * new replicas must find send a join message to the arbiter signalling they are ready to 
     * be used (it is important the join message is sent from the actor itself as replicas - for 
     * primary - will be sent back to it)
     * this is answered with either JoinedPrimary or JoinedSecondary indicating the role
     * of the node
     */
    case Join =>
      if (leader.isEmpty) {
        leader = Some(sender)
        replicas += sender
        sender ! JoinedPrimary
      } else {
        replicas += sender
        sender ! JoinedSecondary
      }
      /**
       * If replicas leave the cluster which is signalled by sending a new Replicas
       * message to the primary then outstanding acknowledgements of these replicas
       * must be waived. This can lead to the generation of an OperationAck triggered
       * indirectly by the Replicas message 
       */
      leader foreach (_ ! Replicas(replicas))
  }

}
