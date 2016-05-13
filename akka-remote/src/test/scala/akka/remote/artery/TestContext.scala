/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success

import akka.Done
import akka.actor.ActorRef
import akka.actor.Address
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.InboundControlJunction.ControlMessageSubject

private[akka] class TestInboundContext(
  override val localAddress: UniqueAddress,
  val controlSubject: TestControlMessageSubject = new TestControlMessageSubject,
  val controlProbe: Option[ActorRef] = None,
  val replyDropRate: Double = 0.0) extends InboundContext {

  private val associations = new ConcurrentHashMap[Address, OutboundContext]

  override def sendControl(to: Address, message: ControlMessage) = {
    if (ThreadLocalRandom.current().nextDouble() >= replyDropRate)
      association(to).sendControl(message)
  }

  override def association(remoteAddress: Address): OutboundContext =
    associations.get(remoteAddress) match {
      case null ⇒
        val a = createAssociation(remoteAddress)
        associations.putIfAbsent(remoteAddress, a) match {
          case null     ⇒ a
          case existing ⇒ existing
        }
      case existing ⇒ existing
    }

  protected def createAssociation(remoteAddress: Address): OutboundContext =
    new TestOutboundContext(localAddress, remoteAddress, controlSubject, controlProbe)
}

private[akka] class TestOutboundContext(
  override val localAddress: UniqueAddress,
  override val remoteAddress: Address,
  override val controlSubject: TestControlMessageSubject,
  val controlProbe: Option[ActorRef] = None) extends OutboundContext {

  // access to this is synchronized (it's a test utility)
  private var _associationState = AssociationState()

  override def associationState: AssociationState = synchronized {
    _associationState
  }

  override def completeHandshake(peer: UniqueAddress): Unit = synchronized {
    _associationState.uniqueRemoteAddressPromise.trySuccess(peer)
    _associationState.uniqueRemoteAddress.value match {
      case Some(Success(`peer`)) ⇒ // our value
      case _ ⇒
        _associationState = _associationState.newIncarnation(Promise.successful(peer))
    }
  }

  override def sendControl(message: ControlMessage) = {
    controlProbe.foreach(_ ! message)
    controlSubject.sendControl(InboundEnvelope(null, remoteAddress, message, None, localAddress))
  }

  // FIXME we should be able to Send without a recipient ActorRef
  override def dummyRecipient: RemoteActorRef = null

}

private[akka] class TestControlMessageSubject extends ControlMessageSubject {

  private var observers = new CopyOnWriteArrayList[ControlMessageObserver]

  override def attach(observer: ControlMessageObserver): Future[Done] = {
    observers.add(observer)
    Future.successful(Done)
  }

  override def detach(observer: ControlMessageObserver): Unit = {
    observers.remove(observer)
  }

  override def stopped: Future[Done] = Promise[Done]().future

  def sendControl(env: InboundEnvelope): Unit = {
    val iter = observers.iterator()
    while (iter.hasNext())
      iter.next().notify(env)
  }

}

private[akka] class ManualReplyInboundContext(
  replyProbe: ActorRef,
  localAddress: UniqueAddress,
  controlSubject: TestControlMessageSubject) extends TestInboundContext(localAddress, controlSubject) {

  private var lastReply: Option[(Address, ControlMessage)] = None

  override def sendControl(to: Address, message: ControlMessage) = {
    lastReply = Some((to, message))
    replyProbe ! message
  }

  def deliverLastReply(): Unit = {
    lastReply.foreach { case (to, message) ⇒ super.sendControl(to, message) }
    lastReply = None
  }
}
