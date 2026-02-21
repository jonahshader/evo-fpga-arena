package evo.ga

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class VictorCopyTest extends AnyFunSuite {

  test("VictorCopy should elaborate") {
    SimConfig.withWave.compile(new VictorCopy).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.winnerCounts(i) #= 0
        dut.io.config.mutationRates(i) #= 0
      }
      dut.io.config.populationSizeExp #= 3  // Population size = 8
      dut.io.go #= false
      dut.io.bramManagerDone #= false

      dut.clockDomain.waitRisingEdge(5)

      // Check initial state: idle, done=true
      assert(dut.io.done.toBoolean, "VictorCopy should be done (idle) initially")

      println("VictorCopy elaboration test passed")
    }
  }

  test("VictorCopy should handle basic copy operation") {
    SimConfig.withWave.compile(new VictorCopy).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Setup: population size = 8
      // Winner counts: index 0 has 3 wins, index 1 has 2 wins, others have 0
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.winnerCounts(i) #= 0
        dut.io.config.mutationRates(i) #= i + 1  // Different mutation rates
      }
      dut.io.winnerCounts(0) #= 3
      dut.io.winnerCounts(1) #= 2
      dut.io.config.populationSizeExp #= 3  // Population size = 8
      dut.io.go #= false
      dut.io.bramManagerDone #= false

      dut.clockDomain.waitRisingEdge(5)

      // Initial state should be idle with done=true
      assert(dut.io.done.toBoolean, "Should be done initially")

      // Trigger the operation
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      dut.clockDomain.waitRisingEdge()

      // After starting, done should be false
      assert(!dut.io.done.toBoolean, "Should not be done after starting")

      // Poll cycle-by-cycle waiting for bramManagerGo (it's a 1-cycle pulse)
      var sawGo = false
      var readIdx = 0
      var writeIdx = 0
      for (_ <- 0 until 20 if !sawGo) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.bramManagerGo.toBoolean) {
          sawGo = true
          readIdx = dut.io.readIndex.toInt
          writeIdx = dut.io.writeIndex.toInt
        }
      }

      assert(sawGo, "Should assert bramManagerGo when copy is initiated")
      assert(
        readIdx == 0 || readIdx == 1,
        s"Read index $readIdx should be a multi-victor (0 or 1)"
      )
      assert(
        writeIdx >= 2 && writeIdx <= 7,
        s"Write index $writeIdx should be a non-victor (2-7)"
      )

      // Signal that the bram_manager is done
      dut.io.bramManagerDone #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.bramManagerDone #= false

      dut.clockDomain.waitRisingEdge(5)

      println(s"VictorCopy basic copy test passed: readIdx=$readIdx writeIdx=$writeIdx")
    }
  }

  test("VictorCopy should complete when no more copies needed") {
    SimConfig.withWave.compile(new VictorCopy).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Setup: population size = 4
      // Winner counts: all have 0 or 1 wins (no multi-victors)
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.winnerCounts(i) #= 0
        dut.io.config.mutationRates(i) #= 0
      }
      dut.io.winnerCounts(0) #= 1
      dut.io.winnerCounts(1) #= 1
      dut.io.config.populationSizeExp #= 2  // Population size = 4
      dut.io.go #= false
      dut.io.bramManagerDone #= false

      dut.clockDomain.waitRisingEdge(5)

      // Trigger the operation
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for seeking to complete (should be quick since no multi-victors)
      dut.clockDomain.waitRisingEdge(20)

      // Should be done since no multi-victors exist
      assert(dut.io.done.toBoolean, "Should complete when no multi-victors found")

      println("VictorCopy completion test passed")
    }
  }

  test("VictorCopy should handle write index wraparound") {
    SimConfig.withWave.compile(new VictorCopy).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Setup: population size = 4
      // Winner counts: index 0 has 3 wins, only index 3 is a non-victor
      // This tests write pointer wrapping from 3 back to 0-2 range
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.winnerCounts(i) #= 0
        dut.io.config.mutationRates(i) #= 0
      }
      dut.io.winnerCounts(0) #= 3
      dut.io.winnerCounts(1) #= 1
      dut.io.winnerCounts(2) #= 1
      // index 3 stays at 0 (non-victor)
      dut.io.config.populationSizeExp #= 2  // Population size = 4
      dut.io.go #= false
      dut.io.bramManagerDone #= false

      dut.clockDomain.waitRisingEdge(5)

      // Trigger the operation
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for seeking to find index 0 as multi-victor and index 3 as non-victor
      dut.clockDomain.waitRisingEdge(10)

      val writeIdx = dut.io.writeIndex.toInt

      // Write index should be 3 (the only non-victor)
      assert(
        writeIdx == 3,
        s"Write index $writeIdx should be 3 (the non-victor)"
      )

      println(s"VictorCopy wraparound test passed: writeIdx=$writeIdx")
    }
  }
}
