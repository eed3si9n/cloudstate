package io.cloudstate.proxy.crdt

import akka.actor.PoisonPill
import akka.cluster.ddata.{PNCounter, PNCounterKey}
import io.cloudstate.crdt._
import io.cloudstate.proxy.entity.UserFunctionReply

import scala.concurrent.duration._


class CrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  // We just use a PNCounter for testing of non CRDT specific functionality
  override protected type T = PNCounter
  override protected type S = PNCounterState
  override protected type D = PNCounterDelta

  override protected def key(name: String) = PNCounterKey(name)

  override protected def initial = PNCounter.empty

  override protected def extractState(state: CrdtState.State) = state.pncounter.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.pncounter.value

  def updateCounter(update: Long) = {
    CrdtStateAction.Action.Update(CrdtDelta(CrdtDelta.Delta.Pncounter(PNCounterDelta(update))))
  }

  "The CrdtEntity" should {

    "drop all updates received while a command is being handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ -3)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2))
      toUserFunction.expectNoMessage(200.millis)
    }

    "send missed updates once a command has been handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2))
      expectDelta().change shouldBe 9
    }

    "send missed updates once a command has been handled with more than local consitency" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2), CrdtWriteConsistency.ALL)
      expectDelta().change shouldBe 9
    }

    "not send missed updates if there is still another command being handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid1 = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, updateCounter(2))
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid2, updateCounter(4))
      expectDelta().change shouldBe 9
    }

    "not send missed updates if there is still another command being handled with more than local consistency" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid1 = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, updateCounter(2), CrdtWriteConsistency.ALL)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid2, updateCounter(4))
      expectDelta().change shouldBe 9
    }

    "allow streaming messages" in {
      update(_ :+ 5)
      createAndExpectInit()
      val (cid, stream) = sendAndExpectStreamedCommand("cmd", command)
      sendReply(cid, streamed = true)
      stream.expectMsgType[UserFunctionReply]

      sendStreamedMessage(cid, Some(element1))
      val reply = stream.expectMsgType[UserFunctionReply]
      reply.clientAction.value.action.reply.value.payload.value should ===(element1)

      sendStreamedMessage(cid, endStream = true)
      expectTerminated(stream.testActor)
    }

    "drop all updates while a stream cancelled message is being handled, then replay" in {
      update(_ :+ 5)
      createAndExpectInit()
      val (cid, stream) = sendAndExpectStreamedCommand("cmd", command)
      sendReply(cid, streamed = true)
      stream.expectMsgType[UserFunctionReply]

      stream.testActor ! PoisonPill
      expectTerminated(stream.testActor)

      val msg = toUserFunction.expectMsgType[CrdtStreamIn]
      msg.message.streamCancelled.value.id should ===(cid)

      update(_ :+ 2)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)

      fromUserFunction ! CrdtStreamOut(CrdtStreamOut.Message.StreamCancelledResponse(
        CrdtStreamCancelledResponse(cid, stateAction = Some(CrdtStateAction(CrdtWriteConsistency.LOCAL, updateCounter(3))))))

      expectDelta().change should be(8)
      eventually {
        get().value.toLong should be(16)
      }

    }

    "drop all updates while a stream cancelled message is being handled that performs no action, then replay" in {
      update(_ :+ 5)
      createAndExpectInit()
      val (cid, stream) = sendAndExpectStreamedCommand("cmd", command)
      sendReply(cid, streamed = true)
      stream.expectMsgType[UserFunctionReply]

      stream.testActor ! PoisonPill
      expectTerminated(stream.testActor)

      val msg = toUserFunction.expectMsgType[CrdtStreamIn]
      msg.message.streamCancelled.value.id should ===(cid)

      update(_ :+ 2)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)

      fromUserFunction ! CrdtStreamOut(CrdtStreamOut.Message.StreamCancelledResponse(
        CrdtStreamCancelledResponse(cid, stateAction = None)))

      expectDelta().change should be(8)
      eventually {
        get().value.toLong should be(13)
      }

    }

  }
}
