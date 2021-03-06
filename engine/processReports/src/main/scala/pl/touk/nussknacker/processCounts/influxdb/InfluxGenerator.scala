package pl.touk.nussknacker.processCounts.influxdb

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId, ZonedDateTime}

import argonaut.{DecodeJson, DecodeResult}
import com.typesafe.scalalogging.LazyLogging
import dispatch._
import pl.touk.nussknacker.engine.dispatch.LoggingDispatchClient
import pl.touk.nussknacker.engine.dispatch.utils._
import pl.touk.nussknacker.processCounts.influxdb.InfluxGenerator.{InfluxResponse, InfluxSerie, PointInTimeQuery}

import scala.concurrent.{ExecutionContext, Future}

private[influxdb] class InfluxGenerator(url: String, user: String, password: String, dbName: String, env: String = "test") extends LazyLogging {

  import argonaut.ArgonautShapeless._
  import InfluxGenerator._

  import scala.concurrent.ExecutionContext.Implicits.global

  val httpClient = LoggingDispatchClient(classOf[InfluxGenerator].getSimpleName, Http.default)

  //TODO: query below should work better even in case of restarts, however for large processes/time ranges it can be *really* slow (at least on influx we use...)
  //select sum(diff) from (select non_negative_difference(value) as diff from "$metricName.count" where process = '$processName' and env = '$env' and time > $from and time < $to group by slot) group by action
  def query(processName: String, metricName: String, dateFrom: Option[LocalDateTime], dateTo: LocalDateTime): Future[Map[String, Long]] = {
    val pointInTimeQuery = new PointInTimeQuery(rawQuery, processName, metricName, env)

    for {
      valuesAtEnd <- pointInTimeQuery.query(dateTo)
      valuesAtStart <- dateFrom.map(pointInTimeQuery.query)
        .getOrElse(Future.successful(Map[String, Long]()))
    } yield valuesAtEnd.map {
      case (key, value) => key -> (value - valuesAtStart.getOrElse(key, 0L))
    }
  }

  private def rawQuery(query: String): Future[List[InfluxSerie]] = {
    httpClient {
      dispatch
        .url(url) <<? Map("db" -> dbName, "q" -> query) as_!(user, password) OK
        asJson[InfluxResponse]
    }.map { qr =>
      qr.results.head.series
    }
  }

  def detectRestarts(processName: String, dateFrom: LocalDateTime, dateTo: LocalDateTime): Future[List[LocalDateTime]] = {
    val from = toEpochSeconds(dateFrom)
    val to = toEpochSeconds(dateTo)
    val queryString =
      //TODO: is it always correct? Will it be performant enough for large (many days) ranges?
      s"""SELECT derivative(value) FROM "source.count" WHERE
         |"process" = '$processName' AND env = '$env'
         | AND time >= ${from}s and time < ${to}s GROUP BY slot""".stripMargin
    rawQuery(queryString).map { series =>
      series.headOption.map(readRestartsFromSourceCounts).getOrElse(List())
    }
  }

  private def readRestartsFromSourceCounts(sourceCounts: InfluxSerie) : List[LocalDateTime] = {
    val restarts = sourceCounts.values.collect {
      case (date:String)::(derivative:BigDecimal)::Nil if derivative < 0 => parseInfluxDate(date)
    }
    restarts
  }

  private def parseInfluxDate(date:String) : LocalDateTime =
    ZonedDateTime.parse(date, DateTimeFormatter.ISO_ZONED_DATE_TIME).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime

  def close(): Unit = {
    httpClient.shutdown()
  }


}

object InfluxGenerator {

  case class InfluxResponse(results: List[InfluxResult] = List())

  case class InfluxResult(series: List[InfluxSerie] = List())

  case class InfluxSerie(name: String, tags: Map[String, String], columns: List[String], values: List[List[Any]] = List()) {
    val toMap: List[Map[String, Any]] = values.map(value => columns.zip(value).toMap)
  }

  private[influxdb] implicit val numberOrStringDecoder: DecodeJson[Any] = DecodeJson.apply[Any] { cursor =>
    val focused = cursor.focus
    val bigDecimalDecoder = implicitly[DecodeJson[BigDecimal]].asInstanceOf[DecodeJson[Any]]
    val stringDecoder = implicitly[DecodeJson[String]].asInstanceOf[DecodeJson[Any]]
    DecodeResult.ok(focused.as(bigDecimalDecoder).toOption.getOrElse(focused.as(stringDecoder).toOption.getOrElse("")))
  }

  private[influxdb] def toEpochSeconds(d: LocalDateTime): Long = {
    d.atZone(ZoneId.systemDefault()).toInstant.toEpochMilli / 1000
  }

  //influx cannot give us result for "give me value nearest in time to t1", so we try to do it by looking for
  //last point before t1 and first after t1.
  // TODO: probably we should just take one of them, but the one which is closer to t1?
  class PointInTimeQuery(query: String => Future[List[InfluxSerie]], processName: String, metricName: String, env: String)(implicit ec: ExecutionContext) extends LazyLogging {

    //two hour window is for possible delays in sending metrics from taskmanager to jobmanager (or upd sending problems...)
    //it's VERY unclear how large it should be. If it's too large, we may overlap with end and still generate
    //bad results...
    def query(date: LocalDateTime): Future[Map[String, Long]] = {
      val around = toEpochSeconds(date)
      for {
        valuesBefore <- retrieveOnlyResultFromActionValueQuery(s"""select action, last(value) as value from "$metricName.count" where process = '$processName' """ +
                    s"and time <= ${around}s and time > ${around}s - 1h and env = '$env' group by slot, action")
        valuesAfter <- retrieveOnlyResultFromActionValueQuery(s"""select action, first(value) as value from "$metricName.count" where process = '$processName' """ +
                    s"and time >= ${around}s and time < ${around}s + 1h and env = '$env' group by slot, action")
      } yield valuesBefore ++ valuesAfter
    }

    //see InfluxGeneratorSpec for influx return format...
    def retrieveOnlyResultFromActionValueQuery(queryString: String): Future[Map[String, Long]] = {
      val groupedResults = query(queryString).map { seriesList =>
        seriesList.map { oneSeries =>
          //in case of our queries we know there will be only one result (we use only first/last aggregations), rest will be handled by aggregations
          val firstResult = oneSeries.toMap.headOption.getOrElse(Map())
          (firstResult.getOrElse("action", "UNKNOWN").asInstanceOf[String], firstResult.getOrElse("value", 0L).asInstanceOf[Number].longValue())
        }.groupBy(_._1).mapValues(_.map(_._2).sum)
      }
      groupedResults.foreach {
        evaluated => logger.debug(s"Query: $queryString retrieved grouped results: $evaluated")
      }
      groupedResults
    }
  }

}
