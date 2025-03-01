package io.cloudstate.proxy.crdt

import akka.cluster.ddata.{Flag, FlagKey, GCounter, GCounterKey}
import io.cloudstate.crdt._
import io.cloudstate.entity.UserFunctionError

import scala.concurrent.duration._


class FlagCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = Flag
  override protected type S = FlagState
  override protected type D = FlagDelta

  override protected def key(name: String) = FlagKey(name)

  override protected def initial = Flag.empty

  override protected def extractState(state: CrdtState.State) = state.flag.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.flag.value

  def create(value: Boolean) = {
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Flag(FlagState(value))))
  }

  def enable(value: Boolean = true) = {
    CrdtStateAction.Action.Update(CrdtDelta(CrdtDelta.Delta.Flag(FlagDelta(value))))
  }

  "The Flag CrdtEntity" should {

    "allow creating an a new flag" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, create(false))
      eventually {
        get().enabled shouldBe false
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "allow creating an enabled flag" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, create(true))
      eventually {
        get().enabled shouldBe true
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "be initialised from a disabled flag" in {
      update(identity)
      createAndExpectInit().value.value shouldBe false
    }

    "be initialised from an enabled flag" in {
      update(_.switchOn)
      createAndExpectInit().value.value shouldBe true
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update(_.switchOn)
      expectState().value shouldBe true
    }

    "detect and enabled to the user function" in {
      update(identity)
      createAndExpectInit()

      update(_.switchOn)
      expectDelta().value shouldBe true
    }

    "handle enabled deltas" in {
      update(identity)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, enable())
      eventually {
        get().enabled shouldBe true
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "refuse to disable a flag" in {
      update(_.switchOn)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectFailure(cid, enable(false))
      entityDiscovery.expectMsgType[UserFunctionError]
      get().enabled shouldBe true
    }

  }
}
