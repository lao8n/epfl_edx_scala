package reactive

import akka.actor.Actor
import akka.actor.Props
import akka.event.LoggingReceive

// trait Actor {
//   implicit val self: ActorRef 
//   implicit val context: ActorContext
//   def receive: Receive 
//   def sender: ActorRef
// }

class TransferMain extends Actor {
  val accountA = context.actorOf(Props[BankAccount], "accountA")
  val accountB = context.actorOf(Props[BankAccount], "accountB")

  accountA ! BankAccount.Deposit(100)

  def receive = LoggingReceive {
    case BankAccount.Done => transfer(150)
  }

  def transfer(amount: BigInt): Unit = {
    // trait ActorContext {
    //   def become(behavior: Receive, discardOld: Boolean = true): Unit
    //   def unbecome(): Unit
    //   def actorOf(p: Props, name: String): ActorRef // always created by actors
    //   def stop(a: ActorRef): Unit // often applied to self
    //   ...
    // }

    val transaction = context.actorOf(Props[WireTransfer], "transfer")
    transaction ! WireTransfer.Transfer(accountA, accountB, amount)
    context.become(LoggingReceive {
      case WireTransfer.Done =>
        println("success")
        context.stop(self)
      case WireTransfer.Failed =>
        println("failed")
        context.stop(self)
    })
  }
}