package evo.ga

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class GaTest extends AnyFunSuite {

  def initConfig(dut: Ga): Unit = {
    val cfg = dut.io.config
    cfg.maxGen               #= 2
    cfg.runUntilStopCmd      #= false
    cfg.tournamentSize       #= 2
    cfg.populationSizeExp    #= 2   // pop = 4
    cfg.modelHistorySize     #= 1
    cfg.modelHistoryInterval #= 1
    cfg.seed                 #= 0x12345678L
    cfg.referenceCount       #= 0
    cfg.evalInterval         #= 1
    cfg.seedCount            #= 1
    cfg.frameLimit           #= 10
    cfg.recycleSeeds         #= false
    for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
      cfg.mutationRates(i) #= 10
    }
  }

  test("Ga should elaborate and run through INIT_BRAM") {
    SimConfig.withWave.compile(new Ga).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      initConfig(dut)
      dut.io.go      #= false
      dut.io.pause   #= false
      dut.io.resume  #= false
      dut.io.bmDone  #= true  // BRAM manager starts ready
      dut.io.tnDone  #= false
      dut.io.fnDone  #= false
      dut.io.fnReferenceFitnessSum #= 0
      dut.io.dbBramDump      #= false
      dut.io.dbBramDumpIndex #= 0
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.tnWinnerCounts(i) #= 0
      }

      dut.clockDomain.waitRisingEdge(5)

      // Start GA
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // The GA should go through INIT_BRAM
      // Simulate BM: when bmGo pulses, deassert done briefly then reassert
      var fnGoSeen = false
      for (_ <- 0 until 500 if !fnGoSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.fnGo.toBoolean) fnGoSeen = true
        // Respond to bmGo by toggling done
        if (dut.io.bmGo.toBoolean) {
          // bmGo is high this cycle; on next cycle, bmDone goes low
          fork {
            dut.clockDomain.waitRisingEdge()
            dut.io.bmDone #= false
            dut.clockDomain.waitRisingEdge(2)
            dut.io.bmDone #= true
          }
        }
      }

      assert(fnGoSeen, "Ga should launch fitness after BRAM init")
      println("Ga INIT_BRAM test passed")
    }
  }

  test("Ga should elaborate and complete a full run") {
    SimConfig.withWave.compile(new Ga).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      initConfig(dut)
      dut.io.go      #= false
      dut.io.pause   #= false
      dut.io.resume  #= false
      dut.io.bmDone  #= true
      dut.io.tnDone  #= false
      dut.io.fnDone  #= false
      dut.io.fnReferenceFitnessSum #= 0
      dut.io.dbBramDump      #= false
      dut.io.dbBramDumpIndex #= 0
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        dut.io.tnWinnerCounts(i) #= 0
      }

      dut.clockDomain.waitRisingEdge(5)

      // Fork background responders for sub-modules
      // BM responder: when bmGo pulses, briefly deassert done then reassert
      fork {
        while (true) {
          dut.clockDomain.waitRisingEdge()
          if (dut.io.bmGo.toBoolean) {
            dut.clockDomain.waitRisingEdge()
            dut.io.bmDone #= false
            dut.clockDomain.waitRisingEdge(3)
            dut.io.bmDone #= true
          }
        }
      }

      // Fitness responder
      fork {
        while (true) {
          dut.clockDomain.waitRisingEdge()
          if (dut.io.fnGo.toBoolean) {
            dut.clockDomain.waitRisingEdge()
            dut.io.fnDone #= true
            dut.clockDomain.waitRisingEdge()
            dut.io.fnDone #= false
          }
        }
      }

      // Tournament responder
      fork {
        while (true) {
          dut.clockDomain.waitRisingEdge()
          if (dut.io.tnGo.toBoolean) {
            dut.io.tnWinnerCounts(0) #= 2
            dut.io.tnWinnerCounts(1) #= 2
            dut.io.tnWinnerCounts(2) #= 0
            dut.io.tnWinnerCounts(3) #= 0
            dut.clockDomain.waitRisingEdge()
            dut.io.tnDone #= true
            dut.clockDomain.waitRisingEdge()
            dut.io.tnDone #= false
          }
        }
      }

      // Start GA
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for done
      var doneSeen = false
      for (_ <- 0 until 5000 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) doneSeen = true
      }

      assert(doneSeen, "Ga should complete after max_gen generations")
      println("Ga full run test passed")
    }
  }
}
