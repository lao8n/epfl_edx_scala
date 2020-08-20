package reactive

import akka.actor.Actor

// trait Actor {
//   implicit val self: ActorRef 
//   implicit val context: ActorContext
//   def receive: Receive 
//   def sender: ActorRef
// }

class Counter extends Actor {
  var count = 0
  def receive = {
    case "incr" ⇒ count += 1
    case "get"  ⇒ sender() ! count
  }
}