/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor => a, event => e }
import akka.util.Subclassification
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * An Akka EventStream is a pub-sub stream of events both system and user generated,
 * where subscribers are ActorRefs and the channels are Classes and Events are any java.lang.Object.
 * EventStreams employ SubchannelClassification, which means that if you listen to a Class,
 * you'll receive any message that is of that type or a subtype.
 *
 * The debug flag in the constructor toggles if operations on this EventStream should also be published
 * as Debug-Events
 */
class EventStreamImpl(private val debug: Boolean) extends EventStream {
  import e.Logging.simpleName
  import ScalaDSL._
  import EventStreamImpl._

  private implicit val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  override def subscribe[T](subscriber: ActorRef[T], channel: Class[T]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "subscribing " + subscriber + " to channel " + channel))
    registerWithUnsubscriber(subscriber)
    super.subscribe(subscriber, channel)
  }

  override def unsubscribe[T](subscriber: ActorRef[T], channel: Class[T]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    val ret = super.unsubscribe(subscriber, channel)
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from channel " + channel))
    unregisterIfNoMoreSubscribedChannels(subscriber)
    ret
  }

  override def unsubscribe[T](subscriber: ActorRef[T]) {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    super.unsubscribe(subscriber)
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from all channels"))
    unregisterIfNoMoreSubscribedChannels(subscriber)
  }

  /**
   * ''Must'' be called after actor system is "ready".
   * Starts system actor that takes care of unsubscribing subscribers that have terminated.
   */
  def startUnsubscriber(sys: ActorSystem[Nothing]): Unit =
    // sys may be null for backwards compatibility reasons
    if (sys ne null) EventStreamUnsubscriber.start(sys, this)

    val unsubscriber = Deferred { () =>
      if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $this"))
      Full[Command] {
      case Msg(ctx, Register(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
        ctx.watch[Nothing](actor)
        Same

      case Msg(ctx, UnregisterIfNoMoreSubscribedChannels(actor)) if hasSubscriptions(actor) ⇒
      // do nothing
      // hasSubscriptions can be slow, but it's better for this actor to take the hit than the EventStream

      case Msg(ctx, UnregisterIfNoMoreSubscribedChannels(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor, since has no subscriptions"))
        ctx.unwatch[Nothing](actor)
        Same

      case Sig(ctx, Terminated(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $this, because it was terminated"))
        unsubscribe(actor)
        Same
      }
    }
}

object EventStreamImpl {

  sealed trait Command
  final case class Register(actor: ActorRef[Nothing]) extends Command
  final case class UnregisterIfNoMoreSubscribedChannels[T](actor: ActorRef[Nothing]) extends Command

  def(eventStream: EventStream, debug: Boolean = false) extends Actor {

    import EventStreamUnsubscriber._

    override def preStart() {
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $eventStream"))
      eventStream initUnsubscriber self
    }

    def receive = {
      case Register(actor) ⇒
        if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
        context watch actor

      case UnregisterIfNoMoreSubscribedChannels(actor) if eventStream.hasSubscriptions(actor) ⇒
      // do nothing
      // hasSubscriptions can be slow, but it's better for this actor to take the hit than the EventStream

      case UnregisterIfNoMoreSubscribedChannels(actor) ⇒
        if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor, since has no subscriptions"))
        context unwatch actor

      case Terminated(actor) ⇒
        if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $eventStream, because it was terminated"))
        eventStream unsubscribe actor
    }
  }

}
