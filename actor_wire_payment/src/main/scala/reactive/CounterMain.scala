package reactive

import akka.actor.Actor
import akka.actor.Props

// trait Actor {
//   implicit val self: ActorRef 
//   implicit val context: ActorContext
//   def receive: Receive 
//   def sender: ActorRef
// }

class CounterMain extends Actor {
  // trait ActorContext {
  //   def become(behavior: Receive, discardOld: Boolean = true): Unit
  //   def unbecome(): Unit
  //   def actorOf(p: Props, name: String): ActorRef // always created by actors
  //   def stop(a: ActorRef): Unit // often applied to self
  //   ...
  // }

  val counter = context.actorOf(Props[Counter], "counter")

  counter ! "incr"
  counter ! "incr"
  counter ! "get"

  def receive = {
    case count: Int ⇒
      println(s"count was $count")
      context.stop(self)
  }
}