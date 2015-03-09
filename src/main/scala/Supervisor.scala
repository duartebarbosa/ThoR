package wazza.thor

import scala.concurrent._
import ExecutionContext.Implicits.global
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ActorRef}
import java.util.Date
import org.apache.spark._
import scala.collection.mutable.ListBuffer
import jobs._
import wazza.thor.messages._

object Supervisor {

  def props(companyName: String,
    appName: String,
    platforms: List[String],
    start: Date,
    end: Date,
    system: ActorSystem,
    sc: SparkContext,
    thor: ActorRef
  ): Props = {
    Props(new Supervisor(companyName, appName, platforms, start, end, system, sc, thor))
  }
}

class Supervisor(
  companyName: String,
  appName: String,
  platforms: List[String],
  start: Date,
  end: Date,
  system: ActorSystem,
  sc: SparkContext,
  thor: ActorRef
) extends Actor with ActorLogging {
  import context._

  private var jobs = List[ActorRef]()
  private var results = List[JobCompleted]()

  private def generateActor(props: Props, name: String): ActorRef = {
    system.actorOf(props, name = name)
  }

  def initJobs() = {
    log.info("Creating jobs")
    def generateName(name: String) = {
      import java.text.SimpleDateFormat
      val df = new SimpleDateFormat("yyyy_MM_dd")
      val dayId = df.format(start)
      val n = s"${companyName}_${name}_${appName}_${dayId}"
      n.replaceAll(" ","_")
    }
    var buffer = new ListBuffer[ActorRef]

    val ltv = generateActor(
      LifeTimeValue.props(sc),
      generateName("LTV")
    )

    val arpu = generateActor(
      Arpu.props(sc, ltv),
      generateName("Arpu")
    )

    val avgRevenuePerSession = generateActor(
      AverageRevenuePerSession.props(sc),
      generateName("avgRevenueSession")
    )

    val avgPurchasesSession = generateActor(
      PurchasesPerSession.props(sc),
      generateName("avgPurchasesSession")
    )

    val avgPurchasesUser = generateActor(
      AveragePurchasesUser.props(sc),
      generateName("avgPurchasesUser")
    )

    val numberSessionsFirstPurchase = generateActor(
      NumberSessionsFirstPurchases.props(sc),
      generateName("NumberSessionsFirstPurchases")
    )

    val numberSessionsBetweenPurchases = generateActor(
      NumberSessionsBetweenPurchases.props(sc),
      generateName("NumberSessionsBetweenPurchases")
    )

    val totalRevenue = generateActor(
      TotalRevenue.props(sc, List(arpu, avgRevenuePerSession)),
      generateName("totalRevenue")
    )
    val activeUsers = generateActor(
      ActiveUsers.props(sc, List(arpu)),
      generateName("activeUsers")
    )
    val payingUsers = generateActor(
      PayingUsers.props(sc, List(avgPurchasesSession, avgPurchasesUser, numberSessionsBetweenPurchases, numberSessionsFirstPurchase)),
      generateName("payingUsers")
    )
    val numberSessions = generateActor(
      NumberSessions.props(sc, List(avgRevenuePerSession, avgPurchasesSession)),
      generateName("numberSessions")
    )
    val nrSessionsPerUsers = generateActor(
      NumberSessionsPerUser.props(sc, List(ltv)),
      generateName("nrSessionsPerUser")
    )

    /** Define child's dependencies **/
    arpu ! CoreJobDependency(List(totalRevenue, activeUsers))
    avgRevenuePerSession ! CoreJobDependency(List(totalRevenue, numberSessions))
    avgPurchasesSession ! CoreJobDependency(List(payingUsers, numberSessions))
    avgPurchasesUser ! CoreJobDependency(List(payingUsers))
    numberSessionsBetweenPurchases ! CoreJobDependency(List(payingUsers))
    ltv ! CoreJobDependency(List(arpu, nrSessionsPerUsers))
    numberSessionsFirstPurchase ! CoreJobDependency(List(payingUsers))

    buffer += totalRevenue
    buffer += activeUsers
    buffer += payingUsers
    buffer += numberSessions
    buffer += nrSessionsPerUsers
    jobs = buffer.toList
    
    for(jobActor <- jobs) {
      jobActor ! InitJob(companyName, appName, platforms, start, end)
    }
  }

  initJobs()

  def receive = {
    case j: JobCompleted => {
      results = j :: results
      if(jobs.size == results.size) {
        log.info("All jobs have finished")
        thor ! new ThorMessage(self.path.name, new Date, true)
        stop(self)
      }
    }

    case _ => {
      log.info("DEAD LETTER")
      thor ! new ThorMessage(self.path.name, new Date, false)
      stop(self)
    }
  }
}

