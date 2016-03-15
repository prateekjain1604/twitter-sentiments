package com.imaginea

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.routing.RoundRobinPool
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.update.{UpdateRequest, UpdateResponse}
import twitter4j._

import scala.collection.JavaConversions._

case class TweetsSentiment(tweet: Status, sentiment: Double)

trait TwitterInstance {
  val twitter = new TwitterFactory(ConfigurationBuilderUtil.buildConfiguration).getInstance()
}

object QuerySearch extends TwitterInstance {

  val sentimentUtil : SentimentAnalysis = new StandfordSentimentImpl
  val actorSystem = ActorSystem("twitter")
  val router: ActorRef =
    actorSystem.actorOf(RoundRobinPool(Runtime.getRuntime.availableProcessors()).
      props(Props[TwitterQueryFetcher]), "router")


  def fetchAndSaveTweets(terms: (String, Date, String), next: String): TermWithCount = {
    val (term, since, isNewTerm) = terms
    val bulkRequest = EsUtils.client.prepareBulk()
    var query = new Query(term).lang("en")
    query.setCount(100)

    if (!isNewTerm.equalsIgnoreCase("newTerm")) {
      val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      query.setSince(sdf.format(since))
    }

    if(!next.isEmpty){
      query.setSince(next)
    }
    
    var isFinal = false;
    val queryResult = twitter.search(query)
    if(queryResult.hasNext){
      query = queryResult.nextQuery()
      router ! new QueryTwitter(terms, query.getSince)
    } else{
      isFinal = true;
    }
    
    var tweetCount = 0
    var recentTweet = false
    var createdAt = new Date

    import org.json4s._
    import org.json4s.jackson.JsonMethods._
    tweetCount = tweetCount + queryResult.getCount
    queryResult.getTweets.foreach { status =>
      if (!recentTweet) {
        createdAt = status.getCreatedAt
        recentTweet = true
      }
      val tweetAsJson = TwitterObjectFactory.getRawJSON(status)
      val tmpDoc1 = status.getText.replaceAll("[^\\u0009\\u000a\\u000d\\u0020-\\uD7FF\\uE000-\\uFFFD]", "")
      val tmpDoc2 = tmpDoc1.replaceAll("[\\uD83D\\uFFFD\\uFE0F\\u203C\\u3010\\u3011\\u300A\\u166D\\u200C\\u202A\\u202C\\u2049\\u20E3\\u300B\\u300C\\u3030\\u065F\\u0099\\u0F3A\\u0F3B\\uF610\\uFFFC\\u20B9\\uFE0F]", "");
      val sentiment = sentimentUtil.detectSentiment(tmpDoc2)
      val json = parse(tweetAsJson) merge (new JObject(List(("term", JString(term)), ("sentiment", JString(sentiment)))))
      bulkRequest.add(EsUtils.client.prepareIndex("twitter", "tweet").setSource(compact(json)))
    }
    
    bulkRequest.execute()
    if (tweetCount == 0 && isNewTerm.equalsIgnoreCase("newTerm")) {
      EsUtils.client.prepareDelete("tweetedterms", "typetweetedterms", term).execute()
    } else {
      indexTweetedTerms(term, "done", createdAt)
    }
    if(isFinal){
      EsUtils.createWordCount(term)
    }
    TermWithCount(term, tweetCount)
  }

  def indexTweetedTerms(term: String, status: String, createdAt: Date): UpdateResponse = {
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val indexRequest = new IndexRequest("tweetedterms", "typetweetedterms", term).
      source(JsonUtils.toJson(TweetedTerms(term, status, sdf.format(new Date), sdf.format(createdAt))))
    val updateRequest = new UpdateRequest("tweetedterms", "typetweetedterms", term).
      doc(JsonUtils.toJson(TweetedTerms(term, status, sdf.format(new Date),
        sdf.format(createdAt)))).upsert(indexRequest)
    EsUtils.client.update(updateRequest).get()
  }
}

class TwitterQueryFetcher extends Actor {
  override def receive: Receive = {
    case QueryTwitter(term, next) =>
      sender ! QuerySearch.fetchAndSaveTweets(term, next)
  }
}

object JsonUtils {
  def toJson[T <: AnyRef <% Product with Serializable](t: T): String = {
    import org.json4s._
    import org.json4s.jackson.Serialization
    import org.json4s.jackson.Serialization.write
    implicit val formats = Serialization.formats(NoTypeHints)
    write(t)
  }
}


case class QueryTwitter(term: (String, Date, String), since: String)

case class TermWithCount(term: String, count: Int)

case class TermWithCounts(terms: List[TermWithCount])

case class TweetedTerms(searchTerm: String, queryStatus: String, lastUpdated: String, since: String)

case class TermStatus(term:String, status: String)

case class TermWithStatus(termWithStatus : List[TermStatus])

case class Tweet(text :String, sentiment:String, reTweetCount: Int, screenName: String)

case class Sentiments(sentimentsCount: Map[String, Long])

case class TermWithSentiments(term: String, totalCount: Int, tweets: List[Tweet], sentiments: Sentiments)
