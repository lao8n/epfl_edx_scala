/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue
import akka.event.LoggingReceive

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

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  /**
    * Design choices
    * 1. how to handle the initially removed root, do we keep it, or do we replace it?
    *    we cannot mutate it as it is val right? how do we tell if root is removed? i guess
    *    we have to message it?
    * 2. unclear why we import BinaryTreeSet._ in BinaryTreeNode and vice-versa
    * 3. maybe there should be an intermediary actor that does the creation e.g. with transfer example
    */
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue = Queue.empty[Operation]

  // optional
  /** Accepts `Operation` and `GC` messages. */
  def receive = emptyTree // default mode
  
  val emptyTree: Receive = LoggingReceive { 
    case Insert(requester, id, insertElem) => {
      root = context.actorOf(BinaryTreeNode.props(insertElem, initiallyRemoved=false))
      requester ! OperationFinished(id)
      context.become(treeWithItems())
    }
    case Contains(requester, id, containsElem) => {
      root ! Contains(requester, id, containsElem)
    }
    case Remove(requester, id, elem) => {
      // tree is empty so elem will not be found but told to still return OperationFinished message
      requester ! OperationFinished(id)
    }
    case GC => // do nothing, empty tree means nothing to GC
  }

  def treeWithItems(): Receive = LoggingReceive { 
    case Insert(requester, id, insertElem) => {
      root ! Insert(requester, id, insertElem)
    }
    case Contains(requester, id, containsElem) => {
      root ! Contains(requester, id, containsElem)
    }
    case Remove(requester, id, removeElem) => {
      root ! Remove(requester, id, removeElem)
    }
    case GC => ???
  }
  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = ???

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  /**
   * Acknowledges that a copy has been completed. This message should be sent
   * from a node to its parent, when this node and all its children nodes have
   * finished being copied.
   */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */

  def receive = LoggingReceive { 
    case OperationFinished(id) => ???
    case Insert(requester, id, insertElem) => insert(requester, id, insertElem)
    case Contains(requester, id, containsElem) => contains(requester, id, containsElem)
    case Remove(requester, id, removeElem) => remove(requester, id, removeElem)
  }

  def insert(requester: ActorRef, id: Int, insertElem: Int): Unit = {
    // element exists but has been removed
    if(insertElem == elem && removed == true){
      removed = false
      requester ! OperationFinished(id)
    }
    // element exists
    else if(insertElem == elem){
      requester ! OperationFinished(id)
    }
    // we continue past nodes that are removed to find children below
    else if (insertElem < elem){
      subtrees.get(Left) match {
        case Some(leftActorRef) => leftActorRef ! Insert(requester, id, insertElem)
        case None => {
          val leftActorRef = context.actorOf(props(insertElem, false))
          // https://alvinalexander.com/scala/how-to-add-update-remove-elements-immutable-maps-scala/
          // we have declared immutable map as var, i.e. we replaced the entire map with new map
          subtrees += (Left -> leftActorRef)
          requester ! OperationFinished(id)
        }
      }
    }
    else {
      subtrees.get(Right) match {
        case Some(rightActorRef) => rightActorRef ! Insert(requester, id, insertElem)
        case None => {
          val rightActorRef = context.actorOf(props(insertElem, false))
            // https://alvinalexander.com/scala/how-to-add-update-remove-elements-immutable-maps-scala/
          // we have declared immutable map as var, i.e. we replaced the entire map with new map
          subtrees += (Right -> rightActorRef)
          requester ! OperationFinished(id)
        }
      }
    }
  }

  def contains(requester: ActorRef, id: Int, containsElem: Int): Unit = {
    // element exists but has been removed
    if(containsElem == elem && removed == true){
      requester ! ContainsResult(id, false)
    }
    // element exists
    else if (containsElem == elem && removed == false){
      requester ! ContainsResult(id, true)
    }
    // even if node is removed we continue traversing beyond it.
    else if (containsElem < elem){
      subtrees.get(Left) match {
        case Some(leftActorRef) => leftActorRef ! Contains(requester, id, containsElem)
        case None => requester ! ContainsResult(id, false) 
      }
    }
    else {
      subtrees.get(Right) match {
        case Some(rightActorRef) => rightActorRef ! Contains(requester, id, containsElem)
        case None => requester ! ContainsResult(id, false)
      }
    }
  }

  def remove(requester: ActorRef, id: Int, removeElem: Int): Unit = {
    if(removeElem == elem){
      removed = true
      requester ! OperationFinished(id)
    }
    else if (removeElem < elem){
      subtrees.get(Left) match {
        case Some(leftActorRef) => leftActorRef ! Remove(requester, id, removeElem)
        case None => requester ! OperationFinished(id)
      }
    }
    else {
      subtrees.get(Right) match {
        case Some(rightActorRef) => rightActorRef ! Remove(requester, id, removeElem)
        case None => requester ! OperationFinished(id)
      }
    }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = ???


}
