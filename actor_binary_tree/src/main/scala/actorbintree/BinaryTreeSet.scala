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
  */

  /**
    * Design choices
    * 1. how to handle the initially removed root, do we keep it, or do we replace it?
    *    we cannot mutate it as it is val right? how do we tell if root is removed? i guess
    *    we have to message it?
    * 2. unclear why we import BinaryTreeSet._ in BinaryTreeNode and vice-versa
    * 3. maybe there should be an intermediary actor that does the creation e.g. with transfer example
    * 4. How does CopyTo work? Is the copy distributed i.e. each node is copied separately? Should a parent node
    *    need to be aware of all children nodes, or just worry about its direct descendents? Don't know 
    *    if there is a clean way to send all the child nodes back to the parent node that is asking. 
    *    Advantage is that can send copy requests to all nodes in parallel, disadvatnage is that root
    *    becomes the bottleneck for all computation
    * 5. Should we traverse each node and as we do it copy the values? Or should we get all the values
    *    and then insert those items?
    * 6. Big problem is how share information upstream? 
    * 7. Should we be copying local subtrees, or just inserting into the root?
    * 8. Why have a separate set of children when already have map? maybe because we want
    *    to remove from it as we get replies back?
    * 9. I find myself using for loops but maybe i should be using for expressions?
    * 10. Why is insertConfirmed an argument to copying, why not just have it a var that is 
    *     accessible in the different states? Does it imply we should move to copying state
    *     after we have tried inserting? At least it suggests we should user pre-built inserts
    *     rather than something new
    * 11. Should we change state through arguments and become or through local var?
    * 12. How do we know the parent in the CopyTo case? Don't we have to use requester again? Apparently there is a context.parent
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
      context.unbecome()
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
  val normal: Receive = insert orElse contains orElse remove orElse copyTo

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

  def copyTo: Receive = {
    case CopyTo(newRoot) =>
      if(!removed) newRoot ! Insert(self, -1, elem)
      val nodesToCopy = subtrees.values.toSet
      nodesToCopy.foreach { node => 
        node ! CopyTo(newRoot)
      }
      // if node is removed then insertConfirmed=true as nothing to copy, o/w false
      context.become(copying(nodesToCopy, removed)) 
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
      context.stop(self)
    }
    else context.become(copying(expected, insertConfirmed))
  }
}