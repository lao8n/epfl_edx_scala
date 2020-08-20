package reactive

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.LoggingReceive

object WireTransfer {
  case class Transfer(from: ActorRef, to: ActorRef, amount: BigInt)
  case object Done
  case object Failed
}

// trait Actor {
//   implicit val self: ActorRef 
//   implicit val context: ActorContext
//   def receive: Receive 
//   def sender: ActorRef
// }

class WireTransfer extends Actor {
  import WireTransfer._
  
  // trait ActorContext {
  //   def become(behavior: Receive, discardOld: Boolean = true): Unit
  //   def unbecome(): Unit
  //   def actorOf(p: Props, name: String): ActorRef // always created by actors
  //   def stop(a: ActorRef): Unit // often applied to self
  //   ...
  // }

  def receive = LoggingReceive {
    case Transfer(from, to, amount) =>
      from ! BankAccount.Withdraw(amount)
      context.become(awaitFrom(to, amount, sender()))
  }
  
  def awaitFrom(to: ActorRef, amount: BigInt, customer: ActorRef): Receive = LoggingReceive {
    case BankAccount.Done =>
      to ! BankAccount.Deposit(amount)
      context.become(awaitTo(customer))
    case BankAccount.Failed =>
      customer ! Failed
      context.stop(self)
  }
  
  def awaitTo(customer: ActorRef): Receive = LoggingReceive {
    case BankAccount.Done =>
      customer ! Done
      context.stop(self)
  }
}