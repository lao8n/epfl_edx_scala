/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue
//import akka.event.LoggingReceive

/**
  * Design choices
  * 1.  Q: Have root be 'removed node' or replace node with first insertion? 
  *     A: Chose latter as avoids extra context, but make explicit by changing
  *        isRootNode to isRootNode 
  * 2.  Q: Handle left and right subtrees separately?
  *     A: Refactored code to use leftOrRightSubtree function 
  * 3.  Q: Maybe there should be an intermediary actor that does the creation as with transfer example?
  *     A: Kept it simple with node doing it
  * 4.  Q: How should instructions be shared? Everything from parent to children? Or from tree set directly 
  *        to all nodes?
  *     A: Go through each generation
  * 5.  Q: Should parents kill children or children kill themselves?
  *     A: Kill themselves
  * 6.  Q: Should you become or unbecome out of new context?
  *     A: Become because I'm not sure if unbecome removes all the stacks
  * 7.  Q: How do you share information upstream?
  *     A: context.parent
  * 8.  Q: How do you copy? Node to node or insert from node above?
  *     A: Node above, because not sure how to get former to work
  */
object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, isRootNode = true))
  var root = createRoot

  // used to stash incoming operations during garbage collection
  var pendingQueue = Queue.empty[Operation]
  def receive = normal

  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case GC =>
      val newRoot = createRoot
      context become garbageCollecting(newRoot)
      root ! CopyTo(newRoot)

    case op: Operation => root forward op // Insert/Contains/Remove
  }

  def garbageCollecting(newRoot: ActorRef): Receive = {
    case CopyFinished => {
      pendingQueue.foreach{ op => newRoot ! op }
      pendingQueue = Queue.empty[Operation]
      root = newRoot
      context become normal
    }
    case op: Operation => pendingQueue = pendingQueue enqueue op // Insert/Contains/Remove
    case GC => // told to ignore GC requests that arrive whilst GC is taking place
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(newRoot: ActorRef)
  /**
   * Acknowledges that a copy has been completed. This message should be sent
   * from a node to its parent, when this node and all its children nodes have
   * finished being copied.
   */
  case object CopyFinished

  def props(elem: Int, isRootNode: Boolean) = Props(classOf[BinaryTreeNode],  elem, isRootNode)
}

class BinaryTreeNode(val elem: Int, isRootNode: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = isRootNode

  def receive = normal

  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = insert orElse contains orElse remove orElse copy

  def leftOrRightSubtree(e: Int) = if (e < elem) Left else Right

  def insert: Receive = {
    case Insert(requester: ActorRef, id: Int, e: Int) =>
      if(e == elem && !isRootNode){
        removed = false
        requester ! OperationFinished(id)
      }
      else {
        val leftOrRight = leftOrRightSubtree(e)
        subtrees.get(leftOrRight) match {
          case Some(_) => subtrees(leftOrRight) forward Insert(requester, id, e)
          case None => {
            subtrees += leftOrRight -> context.actorOf(props(e, false))
            requester ! OperationFinished(id)
          }
        }
      }
  }

  def contains: Receive = {
    case Contains(requester, id, e) =>
      if(e == elem && !isRootNode){
        requester ! ContainsResult(id, !removed)
      }
      else {
        val leftOrRight = leftOrRightSubtree(e)
        subtrees.get(leftOrRight) match {
          case Some(_) => subtrees(leftOrRight) forward Contains(requester, id, e)
          case None => requester ! ContainsResult(id, false)
        }
    }
  }

  def remove: Receive = {
    case Remove(requester, id, e) =>
      if(e == elem && !isRootNode){
        removed = true
        requester ! OperationFinished(id)
      }
      else {
        val leftOrRight = leftOrRightSubtree(e)
        subtrees.get(leftOrRight) match {
          case Some(_) => subtrees(leftOrRight) forward Remove(requester, id, e)
          case None => requester ! OperationFinished(id)
        }
      }
  }

  def copy: Receive = {
    case CopyTo(newRoot) =>
      val nodesToCopy = subtrees.values.toSet
      // if node is removed then insertConfirmed=true as nothing to copy, o/w false
      context become copying(nodesToCopy, removed) // need to be before any messages can be received
      if(!removed) newRoot ! Insert(self, -1, elem)
      nodesToCopy.foreach { node => node ! CopyTo(newRoot)}
  }

  /** `expected` is the set of ActorRefs whose replies we are waiting for,
  * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
  */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(-1) => isCopyFinished(expected, true)
    case CopyFinished => isCopyFinished(expected - sender, insertConfirmed)
  }

  def isCopyFinished(expected: Set[ActorRef], insertConfirmed: Boolean): Unit = {
    if(expected.isEmpty && insertConfirmed) {
      context.parent ! CopyFinished
      context stop self
    }
    else context become copying(expected, insertConfirmed)
  }
}