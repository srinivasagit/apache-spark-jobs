package sprouts.spark.recommender

import spark.jobserver.SparkJob
import spark.jobserver.SparkJobValid
import org.apache.spark.SparkContext
import sprouts.spark.utils.ReadMySQL
import spark.jobserver.SparkJobValidation
import org.apache.spark.sql.SQLContext
import org.apache.spark.mllib.recommendation.ALS
import com.typesafe.config.Config
import org.apache.spark.mllib.recommendation.Rating
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
import sprouts.spark.utils.WriteMongoDB

case class Recommendation(customer_id: Int, items: Array[Rating])

object RecommendProductsCollaborativeFiltering extends SparkJob {
  override def runJob(sc: SparkContext, jobConfig: Config): Any = {
    val user = jobConfig.getString("input.string").toInt
    execute(sc, user)
  }

  override def validate(sc: SparkContext, config: Config): SparkJobValidation = {
    SparkJobValid // Always valid
  }

  def execute(sc: SparkContext, user: Int): Any = {
    val model = MatrixFactorizationModel.load(sc, "/data/jobserver/models")
    val recommendations = model.recommendProducts(user, 20)

    val sqlContext = SQLContext.getOrCreate(sc)
    
    val recommendationsDf = sqlContext.createDataFrame(recommendations)
    
    // save the recommendations to the warehouse collection collaborative_filtering_recommendations
    WriteMongoDB.persistDF(recommendationsDf, sqlContext, "collaborative_filtering_recommendations")
    
    // return recommendations
    recommendationsDf.collect()
    
  }
}
