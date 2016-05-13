/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet

import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler

/**
 * INTERNAL API
 */
private[akka] class InboundQuarantineCheck(inboundContext: InboundContext) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundQuarantineCheck.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundQuarantineCheck.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      // InHandler
      override def onPush(): Unit = {
        val env = grab(in)
        val association = inboundContext.association(env.originAddress.address)
        // FIXME does this mean boxing (allocation) because of Set[Long]? Use specialized Set. LongMap?
        if (association.associationState.quarantined(env.originAddress.uid)) {
          inboundContext.sendControl(env.originAddress.address, Quarantined)
          pull(in)
        } else
          push(out, env)
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}
