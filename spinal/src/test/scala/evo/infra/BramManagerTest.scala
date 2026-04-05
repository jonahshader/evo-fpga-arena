package evo.infra

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class BramManagerTest extends AnyFunSuite {

  test("BramManager should elaborate and complete a COPY_AND_MUTATE operation") {
    SimConfig.withWave.compile(new BramManager).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.command      #= BramCommand.COPY_AND_MUTATE
      dut.io.readIndex    #= 0
      dut.io.writeIndex   #= 1
      dut.io.rng          #= 0
      dut.io.mutationRate #= 0
      dut.io.go           #= false

      dut.clockDomain.waitRisingEdge(5)

      // Should start as done
      assert(dut.io.done.toBoolean, "Should be done initially")

      // Start a COPY_AND_MUTATE
      dut.io.command      #= BramCommand.COPY_AND_MUTATE
      dut.io.readIndex    #= 0
      dut.io.writeIndex   #= 1
      dut.io.mutationRate #= 10
      dut.io.go           #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go           #= false

      // Should no longer be done
      dut.clockDomain.waitRisingEdge()
      assert(!dut.io.done.toBoolean, "Should not be done while running")

      // Wait for completion (TOTAL_PARAMS cycles + margin)
      var doneSeen = false
      for (_ <- 0 until NnConfig.TOTAL_PARAMS + 10 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) doneSeen = true
      }
      assert(doneSeen, "Should complete COPY_AND_MUTATE")

      println("BramManager COPY_AND_MUTATE test passed")
    }
  }

  test("BramManager should complete a READ_TO_NN_1 operation") {
    SimConfig.withWave.compile(new BramManager).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.command      #= BramCommand.READ_TO_NN_1
      dut.io.readIndex    #= 0
      dut.io.writeIndex   #= 0
      dut.io.rng          #= 0
      dut.io.mutationRate #= 0
      dut.io.go           #= false

      dut.clockDomain.waitRisingEdge(5)

      // Start READ_TO_NN_1
      dut.io.command   #= BramCommand.READ_TO_NN_1
      dut.io.readIndex #= 0
      dut.io.go        #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go        #= false

      // Check paramValidNn1 goes high while running
      var validSeen = false
      var doneSeen = false
      for (_ <- 0 until NnConfig.TOTAL_PARAMS + 10 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.paramValidNn1.toBoolean) validSeen = true
        if (dut.io.done.toBoolean) doneSeen = true
      }
      assert(validSeen, "paramValidNn1 should be asserted during READ_TO_NN_1")
      assert(doneSeen, "Should complete READ_TO_NN_1")

      println("BramManager READ_TO_NN_1 test passed")
    }
  }
}
