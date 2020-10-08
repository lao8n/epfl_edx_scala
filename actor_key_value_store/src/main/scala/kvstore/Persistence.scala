package kvstore

import akka.actor.{Props, Actor}
import scala.util.Random

object Persistence {
  /**
    * primary replica or secondary replicator -> persistence actor
    *
    * @param key
    * @param valueOption
    * @param id
    */
  case class Persist(key: String, valueOption: Option[String], id: Long)
  /**
    * persistence actor -> sender (the persistence actor or wrapper actor)
    * either send if successful or don't send at all 
    *
    * @param key
    * @param id
    */
  case class Persisted(key: String, id: Long)

  class PersistenceException extends Exception("Persistence failure")

  def props(flaky: Boolean): Props = Props(classOf[Persistence], flaky)
}
/**
  * Persistence: A subsystem that is provided in this exercise and which 
  * offers to persist updates to stable storage, but might fail spuriously.
  * 
  * Will either be the 
  * primary sending Insert or Remove requests and the confirmation is an 
  * OperationAck
  * or the
  * secondary replicator (not the replica) sending a Snapshot and expecting
  * a SnapshotAck back
  * 
  * replica needs to create and appropriately supervise the persistence actor
  * for the purpose of thisexercise you can use any strategy (whether resuming, 
  * restarting, stopping and recreating the persistence actor)
  * the replica does not receive an ActorRef but a props implying the replica 
  * has to initially create it as well 
  * 
  * for grading purposes it is expected that Persist is retried before the 1s
  * response timemout in case persistence failed the id of the retried persist
  * must match the one used in the first request
  *
  * @param flaky
  */
class Persistence(flaky: Boolean) extends Actor {
  import Persistence._

  def receive = {
    case Persist(key, _, id) =>
      if (!flaky || Random.nextBoolean()) sender ! Persisted(key, id)
      else throw new PersistenceException
  }

}
