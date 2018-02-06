package bdp.spark.Users

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import scala.io.Source
import scala.xml.pull._
import scala.collection.mutable.ArrayBuffer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import scala.xml.XML
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import java.time.LocalDate 
import java.time.format.DateTimeFormatter

object Users extends kmeansScala{     
  def main(args: Array[String]) {
  val sc = new SparkContext(new SparkConf().setAppName("Spark Word Count"))
  /**Variables for K-Means **/
  var tolerance = 0.00000001
  var numberofruns = 1 // the number of times the loop has exectuted so far
  var numIterations = 300; //max allowed iterations
  var converge = true;
  var cluster_count = 0;	//variable for looping through cluster
  var dimension_count = 0;	//variable for loopinf through feature dimension 


  // idx_reference stores all possible information that can be extracted, idx_binary_array specifies which one to be extracted
  // for example: 100001 means "Reputation" and "Downvotes" are the features
  var idx_reference = Array("Reputation=","CreationDate=","LastAccessDate=","Views=","UpVotes=","DownVotes=")  
  var idx_binary_array = Array( Array(1,0,0,1,1,0),Array(1,0,0,0,1,1))

  for (idx_point <- 0 to idx_binary_array.length-1){
	  var idx_binary = idx_binary_array(idx_point)
	  var idx = new Array[String](idx_binary.sum)
	  var temp = 0;
	  for (i <- 0 to idx_binary.length-1){
	    if (idx_binary(i) == 1){
	      idx(temp) = idx_reference(i)
	      temp = temp+1
	    }
  }
  
  
  val feature_dimension = idx.length
  /**Read textFile into RDD, specify features to be extracted in idx**/ 
  /**Transform the RDD by parsing, myMap_unnormalised is a RDD[(userID: String, feature_vector: Array[Double])]**/
  val records = sc.textFile("hdfs://moonshot-ha-nameservice/data/stackoverflow/Users")
  var myMap_unnormalised = records.map(parseXml(_,idx)).filter(x=> x._1 != "Empty").cache()
  
  val x0_min = myMap_unnormalised.map(x => x._2(0)).min
  val x1_min = myMap_unnormalised.map(x => x._2(1)).min
  val x0_max = myMap_unnormalised.map(x => x._2(0)).max
  val x1_max = myMap_unnormalised.map(x => x._2(1)).max
  val x2_min = myMap_unnormalised.map(x => x._2(2)).min
  val x2_max = myMap_unnormalised.map(x => x._2(2)).max
  
  // Normalise data
  var myMap = myMap_unnormalised.map(x=> (x._1, Array((x._2(0) - x0_min )/x0_max,(x._2(1) - x1_min )/x1_max,(x._2(2) - x2_min )/x2_max)))
  
  // Parse the arary of double into tuples
  var myMap_feature = myMap.map(x => (x._2))
  var myMap_feature_copy = myMap_feature.map(x => (x(0), x(1),x(2)))
  //Loop for different number of clusters
  for (numCluster <- 3 to 6){
  /**Declare variables for K-Means**/
  var centroid_distinct = myMap_feature_copy.distinct.takeSample(false,numCluster)	//To ensure all initial centroids are at different positions
  var centroid = new Array[Array[Double]](numCluster)
  
  for (i <- 0 until numCluster){
      centroid(i) = Array(centroid_distinct(i)._1,centroid_distinct(i)._2,centroid_distinct(i)._3)	
  }
    
  /**First iteration**/
  // function init returns the distance between point and centroids
  // Get distance between point and centroids RDD[UserID: String, feature_vector: Array[Double], distance: Array[Double]]
  var myMap_distance = myMap.map(x=> (x._1,x._2, init(centroid,x._2)))
  // Get cluster: cluster_test RDD[Cluster_class: int, userID: String, feature_vector: Array[Double]]
  var cluster_test = myMap_distance.map(x => (GetCluster(x._3),x._1	,x._2))
  
  var displacement = 0.0;    

  /**Update centroids and calculate displacement of each centroid**/
  for (cluster_count <- 0 to  numCluster-1 ){
    for (dimension_count <- 0 to feature_dimension-1){
      //This line of code gets the average of all points classified as that cluster for each features 
      var new_centroid =  cluster_test.filter(x=> (x._1 == cluster_count)).map(x => x._3(dimension_count)).mean      
      displacement = displacement + (new_centroid - centroid(cluster_count)(dimension_count))*(new_centroid - centroid(cluster_count)(dimension_count))
      //Update centroid
      centroid(cluster_count)(dimension_count) = new_centroid
      }
      //If displacement of all centroids are less than tolerance, K-Means converges, else K-Means still diverges 
      if (displacement < tolerance) converge = converge & true; else converge = converge & false;
  }       
 
  /**K-Means iteration: no need to iterate if converges, same code as above**/
  while (numberofruns < numIterations && !converge){
    // Re-initialise converge and displacement for condition checking
    converge = true
    displacement = 0.0
    myMap_distance = myMap.map(x=> (x._1,x._2, init(centroid,x._2)))
    cluster_test = myMap_distance.map(x => (GetCluster(x._3),x._1,x._2))
  	for (cluster_count <- 0 to  numCluster - 1){
    for (dimension_count <- 0 to feature_dimension-1){
        var new_centroid =  cluster_test.filter(x=> (x._1 == cluster_count)).map(x => x._3(dimension_count)).mean
        displacement = displacement + (new_centroid - centroid(cluster_count)(dimension_count))*(new_centroid - centroid(cluster_count)(dimension_count))
        centroid(cluster_count)(dimension_count) = new_centroid
      }
      if (displacement < tolerance) converge = converge & true; else converge = converge & false;
    }
    numberofruns = numberofruns + 1
    }

    //Save final result to file
    var final_cluster = cluster_test.map(x=> (x._1,x._2,x._3(0),x._3(1),x._3(2)))
    final_cluster.saveAsTextFile("hdfs://moonshot-ha-nameservice/user/cyl30/bdp/Users/".concat(idx_binary.mkString).concat("/").concat(numCluster.toString))
    //Save final centroids to file
    val distData = sc.parallelize(centroid).flatMap(x=>x)
    distData.saveAsTextFile("hdfs://moonshot-ha-nameservice/user/cyl30/bdp/Users/Centroid".concat(idx_binary.mkString).concat("/").concat(numCluster.toString))
 }
}
}
}

class kmeansScala {     

   def CompareDate(begin: String): Double = {
    var answer = -1.0
    var diff = 0.0
    val Dateformat = new SimpleDateFormat("YYYY-MM-DD")
    try {
    val startDate = begin.substring(0,10)
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val oldDate = LocalDate.parse(startDate, formatter)
    val currentDate = "2016-12-25"
    val newDate = LocalDate.parse(currentDate, formatter)
    answer = newDate.toEpochDay() - oldDate.toEpochDay()
    } catch {
      case e: ParseException => 
    }
    answer.toDouble
  }
  
  // function to parse each row, returns a tuple (UserID, feature_vector)
  //The feature vector will be used to calculate k-means
    def parseXml(all_text: String, idx: Array[String]): (String,Array[Double]) = {
    val idxmap = idx
    val MapList = new Array[Double](idx.size)
    var i = 0
    for (i <- 0 to idx.size-1) MapList(i) = 0.0
    val count = 0
    var userID = ""
    
    try {
      val xml = all_text
      val tokens = xml.trim().substring(5, xml.trim().length - 3).split("\"")

      while (i < tokens.length - 1) {
        val key = tokens(i).trim()
        val value = tokens(i + 1)
        var val_int = 0.0
        if (key=="AccountId=") {
          userID = value
          
        } else if (idxmap.indexOf(key) >= 0) {
          if (key=="CreationDate="|| key=="LastAccessDate=") {
            val_int = CompareDate(value)
          }
          if (value.length == 0)  MapList(idxmap.indexOf(key)) = 0.0
          else {
            if (val_int == 0) val_int = java.lang.Double.valueOf(value)
            MapList(idxmap.indexOf(key)) = val_int
          }
        }
        i = i + 2
      }
    } catch {
      case e: StringIndexOutOfBoundsException => 
    }
    if (userID == ""){
    return ("Empty", MapList)}
    else {return (userID, MapList)}
  }
  
  
  
  // takes an array of cnetroid and one data point
def init(centroid : Array[Array[Double]], dataPoint : Array[Double]) :  Array[Double] = {
    var numCentroid = centroid.length
    var distances = new Array[Double](numCentroid)
    var runs = 0;
    for (cen <- 0 to (numCentroid - 1)) {
    distances(cen) = getEuclidianDistance(centroid(cen),dataPoint)
    runs +=1
    }
    return distances
 } 

  
  //get input the current datapoint
  // get input the current centroid
def getEuclidianDistance(centroid : Array[Double], dataPoint : Array[Double]) : Double = {
  var distance = 0.0
  //get num dimensions
  var numDimensions = centroid.length;
    for (dim <- 0 to numDimensions-1 ) {
      var CurCentroid = centroid(dim);
      var CurDataPoint = dataPoint(dim)
      var calc = (CurCentroid - CurDataPoint)
      calc = calc * calc
      distance += calc
      
   }
  distance = Math.sqrt(distance);
  return distance
}

//to retrieve the centroid of closest distance
 def GetCluster(Distance : Array[Double]): Int = {
    var maximum = Distance.min
    return Distance.indexOf(maximum)
  }
 
 //need number of dimension and number of clusters as input like this var centroids = setStartCentroids(3, 2)
  def setStartCentroids(K_kluster : Int, dimensions : Int) : Array[Array[Double]] = {
    println("making starting centroid")
    //makes an arraylist of centroid and assign k num clusters
     var centroidsStart = new Array[Array[Double]](K_kluster)
     val r = new scala.util.Random
        for (k <- 0 to (K_kluster - 1 )) {
            //  println("making starting centroid")
              var newCentroid = new Array[Double](dimensions)
              for(d <- 0 to (dimensions - 1)){
                newCentroid(d) = (r.nextInt(100)).toDouble
              }
          centroidsStart(k) = newCentroid
        }

 return centroidsStart
  }
  
  
  //get the previous distance from an array of stored distances
				//any bigger checks if the current movement is smaller than the tolerance
				//if its smaller for all klusters the loop wil break
  def getEuclidianDistance_vector(centroid : Vector[Double], dataPoint : Vector[Double]) : Double = {
  var distance = 0.0
  //get num dimensions
  var numDimensions = centroid.length;
    for (dim <- 0 to numDimensions-1 ) {
      var CurCentroid = centroid(dim);
      var CurDataPoint = dataPoint(dim)
      var calc = (CurCentroid - CurDataPoint)
      calc = calc * calc
      distance += calc
   }
  distance = Math.sqrt(distance);
  return distance
}
  
  
 
 
}
  
