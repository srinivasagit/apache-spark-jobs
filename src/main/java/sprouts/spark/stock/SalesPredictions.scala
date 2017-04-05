package sprouts.spark.stock

import spark.jobserver.SparkJobValid
import org.apache.spark.SparkContext
import spark.jobserver.SparkJobValidation
import org.apache.spark.sql.SQLContext
import com.typesafe.config.Config
import spark.jobserver.SparkJob
import sprouts.spark.utils.ReadMySQL
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.ml.tuning.TrainValidationSplit
import org.apache.spark.sql.DataFrame
import org.apache.spark.ml.tuning.TrainValidationSplitModel
import java.util.Calendar
import sprouts.spark.utils.WriteMongoDB

case class ItemVector(label: Double, features: SparseVector)
case class Sale(month: Int, year: Int, sales: Int)

object SalesPredictions extends SparkJob {
  override def runJob(sc: SparkContext, jobConfig: Config): Any = {
    execute(sc)
  }

  override def validate(sc: SparkContext, config: Config): SparkJobValidation = {
    SparkJobValid // Always valid
  }

  def execute(sc: SparkContext): Any = {
    val sqlContext = SQLContext.getOrCreate(sc)

    val mySQLquery =
      """
(SELECT ordereditem.quantity,
       MONTH(order_.date) as month, YEAR(order_.date) as year
FROM ordereditem
INNER JOIN order_ ON ordereditem.order_id = order_.id AND order_.date >= DATE_SUB(DATE_FORMAT(NOW() ,'%Y-%m-01'), INTERVAL 48 MONTH)
AND order_.date < DATE_FORMAT(NOW() ,'%Y-%m-01')) AS data
      """

    val df = ReadMySQL.read(mySQLquery, sqlContext).rdd
      .map { x => ((x.getLong(1), x.getLong(2)), x.getInt(0)) } // Map ( (month, year), sales). (month, year) as key
      .reduceByKey(_ + _) // We obtain the sales for each month
      .map {
        x => // Map each ((month,year),sales) with a vector, with consists of (label=sales, features=(month,year))
          // SparseVector: 2 = number of features, (0, 1) = indexes
          ItemVector(x._2.doubleValue(), new SparseVector(2, Array(0, 1), Array(x._1._1.doubleValue(), x._1._2.doubleValue())))
      }

    // Let's create a dataframe of label and features
    val data = sqlContext.createDataFrame(df).na.drop()

    // Get the model
    val model = getModel(data)

    // Get the dataframe with ItemVectors representing next 12 months
    val toPredict = sqlContext.createDataFrame(
      sc
        .parallelize(getDates())
        .map {
          x =>
            ItemVector(0.0, new SparseVector(2, Array(0, 1), Array(x._1.toDouble, x._2.toDouble)))
        })

    // Make predictions on test data. model is the model with combination of parameters
    // that performed best.
    val pred = model.transform(toPredict)
      .select("features", "prediction")

    // We turn the predictions to case class objects
    val salesPred = sqlContext.createDataFrame(
      pred.rdd.map {
        x =>
          Sale(x.getAs[SparseVector]("features").toArray(0).intValue, // Gets month
            x.getAs[SparseVector]("features").toArray(1).intValue, // Gets year
            x.getDouble(1).round.toInt) // Gets prediction
      })

    WriteMongoDB.deleteAndPersistDF(salesPred, sqlContext, "sales_predictions")
  }

  // Returns the model
  // I've to research about this algorithm in order to properly configure the params
  def getModel(data: DataFrame): TrainValidationSplitModel = {
    val lr = new LinearRegression()
    val paramGrid = new ParamGridBuilder()
      .addGrid(lr.regParam, Array(0.1, 0.01))
      .addGrid(lr.fitIntercept)
      .addGrid(lr.elasticNetParam, Array(0.0, 0.5, 1.0))
      .build()

    // In this case the estimator is simply the linear regression.
    // A TrainValidationSplit requires an Estimator, a set of Estimator ParamMaps, and an Evaluator.
    val trainValidationSplit = new TrainValidationSplit()
      .setEstimator(lr)
      .setEvaluator(new RegressionEvaluator)
      .setEstimatorParamMaps(paramGrid)
    // 80% of the data will be used for training and the remaining 20% for validation.
    // .setTrainRatio(0.8)

    // Run train validation split, and choose the best set of parameters.
    trainValidationSplit.fit(data)
  }

  def getDates(): Array[(Int, Int)] = {
    val date = Calendar.getInstance()
    date.add(Calendar.MONTH, -1)
    val months = 1.to(12).toArray
    for {i <- months} yield { date.add(Calendar.MONTH, 1); (date.get(Calendar.MONTH) + 1, date.get(Calendar.YEAR)) }
  }

}
