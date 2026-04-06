package evo.platform

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.comms.CommsDefs

class TopUartTest extends AnyFunSuite {

  val clksPerBit = 50

  /** Send a byte over serial (start + 8 data + stop). */
  def sendSerialByte(dut: TopUart, byte: Int): Unit = {
    dut.io.rxSerial #= false
    dut.clockDomain.waitRisingEdge(clksPerBit)
    for (bit <- 0 until 8) {
      dut.io.rxSerial #= ((byte >> bit) & 1) == 1
      dut.clockDomain.waitRisingEdge(clksPerBit)
    }
    dut.io.rxSerial #= true
    dut.clockDomain.waitRisingEdge(clksPerBit)
  }

  /** Send a protocol message as serial bytes. */
  def sendMessage(dut: TopUart, msgType: Int, payload: Seq[Int]): Unit = {
    val bytes = Seq(
      msgType & 0xFF,
      0x00,
      payload.length & 0xFF,
      (payload.length >> 8) & 0xFF
    ) ++ payload
    for (b <- bytes) {
      sendSerialByte(dut, b)
    }
  }

  test("TopUart elaborates successfully") {
    SimConfig.compile(new TopUart(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(10)
    }
  }

  test("TopUart should process TEST command and start TX response") {
    SimConfig.withWave.compile(new TopUart(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(20)

      // ProtocolTx should be idle initially
      assert(dut.dbgProtocolTxReady.toBoolean, "ProtocolTx should be ready initially")

      // Send TEST command (zero payload)
      sendMessage(dut, CommsDefs.CmdId.TEST, Seq())

      // Wait for the protocol pipeline to process
      dut.clockDomain.waitRisingEdge(50)

      // ProtocolTx should now be busy sending the TEST_RESP
      // (or may have already finished if UART is fast enough)
      // Check that txSerial eventually goes low (start bit of response)
      var sawStartBit = false
      for (_ <- 0 until 5000 if !sawStartBit) {
        if (!dut.io.txSerial.toBoolean) sawStartBit = true
        dut.clockDomain.waitRisingEdge()
      }
      assert(sawStartBit, "txSerial should show a start bit (ProtocolTx is responding)")

      // Wait for entire response to be transmitted (4 bytes)
      // Each byte: start(50) + data(400) + stop(50) = 500 cycles
      // 4 bytes + gaps: ~2100 cycles
      dut.clockDomain.waitRisingEdge(2200)

      // ProtocolTx should return to ready
      assert(dut.dbgProtocolTxReady.toBoolean,
        "ProtocolTx should be ready after finishing response")
    }
  }

  test("TopUart should process TRAINING_GO and NE responds with STATE_CHANGE") {
    SimConfig.compile(new TopUart(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(20)

      // Send TRAINING_GO
      sendMessage(dut, CommsDefs.CmdId.TRAINING_GO, Seq())

      // Wait for NE to transition and ProtocolTx to start responding
      dut.clockDomain.waitRisingEdge(100)

      // ProtocolTx should become busy (sending STATE_CHANGE message)
      var wentBusy = false
      for (_ <- 0 until 200 if !wentBusy) {
        if (!dut.dbgProtocolTxReady.toBoolean) wentBusy = true
        dut.clockDomain.waitRisingEdge()
      }
      assert(wentBusy, "ProtocolTx should send STATE_CHANGE after TRAINING_GO")

      // Wait for response to finish
      dut.clockDomain.waitRisingEdge(3000)
      assert(dut.dbgProtocolTxReady.toBoolean,
        "ProtocolTx should be ready after sending STATE_CHANGE")
    }
  }
}
